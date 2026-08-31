package com.twilio.twilio_voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Owns call audio routing.
 *
 * The FSI rewrite replaced the Telecom ConnectionService with a plain service, so the OS
 * no longer picks a route for us. Without the work below a call always lands on the
 * earpiece even with a headset already connected, and nothing reacts to one being
 * connected mid-call.
 *
 * Telecom also used to arbitrate between an app call and a call on the handset. It no
 * longer does, so [CallAudioFocusListener] carries that duty: losing audio focus — which
 * is what a cellular call arriving mid-call looks like from here — suspends the app call
 * instead of leaving two conversations fighting over the microphone.
 */
class TVAudioManager private constructor(context: Context) {

    /**
     * How the live call is told to step aside while something else owns the audio.
     *
     * Implemented by the connection, which is the only thing that can actually hold and
     * resume the media.
     */
    interface CallAudioFocusListener {
        fun onAudioFocusLost()
        fun onAudioFocusRegained()
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var focusRequest: AudioFocusRequest? = null
    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneOn: Boolean = false
    private var isActive: Boolean = false

    private var focusListener: CallAudioFocusListener? = null
    private var focusLost: Boolean = false
    private var focusLostPermanently: Boolean = false

    /** Set once the user picks a route by hand, so automatic routing stops fighting them. */
    private var routeChosenByUser: Boolean = false
    private var deviceCallback: AudioDeviceCallback? = null

    var isSpeakerOn: Boolean = false
        private set
    var isBluetoothOn: Boolean = false
        private set

    fun onCallActive(listener: CallAudioFocusListener? = null) {
        if (isActive) return
        isActive = true
        routeChosenByUser = false
        focusListener = listener
        previousMode = audioManager.mode
        previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        requestAudioFocus()
        registerDeviceCallback()
        applyPreferredRoute()
    }

    fun onCallEnded() {
        if (!isActive) return
        isActive = false
        routeChosenByUser = false
        focusLost = false
        focusLostPermanently = false
        focusListener = null
        handler.removeCallbacks(nativeCallWatchdog)
        unregisterDeviceCallback()

        // A call on the handset may still be running — this call may even have been
        // suspended for it. Putting the mode and route back the way we found them would
        // take the handset's audio with us and break the conversation the rep is still in.
        val nativeCallInProgress = isOnCellularCall()
        if (nativeCallInProgress) {
            Log.i(TAG, "onCallEnded: a native call is still in progress, leaving audio mode and route alone")
        } else {
            setSpeakerphone(false)
            setBluetooth(false)
        }
        abandonAudioFocus()
        if (!nativeCallInProgress) {
            audioManager.mode = previousMode
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = previousSpeakerphoneOn
        }
        isSpeakerOn = false
        isBluetoothOn = false
    }

    fun setSpeakerphone(on: Boolean): Boolean {
        if (isActive) routeChosenByUser = true
        val result = setRoute(on, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        isSpeakerOn = on
        if (on) isBluetoothOn = false
        return result
    }

    fun setBluetooth(on: Boolean): Boolean {
        if (isActive) routeChosenByUser = true
        val result = routeToBluetooth(on)
        isBluetoothOn = on && result
        if (on && result) isSpeakerOn = false
        return result
    }

    /** Whether a Bluetooth headset is connected, so the UI can offer the option. */
    fun hasBluetoothDevice(): Boolean = bluetoothOutput() != null

    /**
     * Whether the handset is already on a native (cellular) call.
     *
     * `MODE_IN_CALL` is only ever set by the telephony stack; our own calls use
     * `MODE_IN_COMMUNICATION`, so this cannot mistake an in-progress app call for a
     * native one. Deliberately not `TelecomManager.isInCall()`, which needs the
     * READ_PHONE_STATE runtime grant the host app does not ask for.
     */
    fun isOnCellularCall(): Boolean = audioManager.mode == AudioManager.MODE_IN_CALL

    /**
     * One entry per output the user can send the call to.
     *
     * Bluetooth devices are listed individually and by name, because a rep may have more
     * than one paired — a headset and a car kit — and a single "Bluetooth" entry would
     * not say which. Below API 31 the platform offers no per-device routing, so only
     * earpiece, speaker and one combined Bluetooth entry are possible there.
     */
    fun availableRoutes(): List<TVAudioRoute> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val activeId = audioManager.communicationDevice?.id
            return audioManager.availableCommunicationDevices
                .filter { it.type in SELECTABLE_TYPES }
                .distinctBy { it.id }
                .map { TVAudioRoute(it.id, routeName(it), it.type, it.id == activeId) }
        }
        val bluetooth = bluetoothOutput()
        return buildList {
            add(TVAudioRoute(LEGACY_EARPIECE_ID, "Phone", AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, !isSpeakerOn && !isBluetoothOn))
            add(TVAudioRoute(LEGACY_SPEAKER_ID, "Speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, isSpeakerOn))
            if (bluetooth != null) {
                add(TVAudioRoute(LEGACY_BLUETOOTH_ID, routeName(bluetooth), AudioDeviceInfo.TYPE_BLUETOOTH_SCO, isBluetoothOn))
            }
        }
    }

    /**
     * Sends call audio to the route with [routeId] from [availableRoutes].
     *
     * Counts as a user choice, so automatic routing will not override it afterwards.
     */
    fun selectRoute(routeId: Int): Boolean {
        if (!isActive) return false
        routeChosenByUser = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.id == routeId }
            if (device == null) {
                Log.w(TAG, "selectRoute: route $routeId is no longer available")
                return false
            }
            // The earpiece is the platform default, so clearing beats selecting it.
            val applied = if (device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                audioManager.clearCommunicationDevice()
                true
            } else {
                audioManager.setCommunicationDevice(device)
            }
            if (applied) {
                isSpeakerOn = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                isBluetoothOn = device.type in BLUETOOTH_TYPES
            }
            return applied
        }
        return when (routeId) {
            LEGACY_SPEAKER_ID -> setSpeakerphone(true)
            LEGACY_BLUETOOTH_ID -> setBluetooth(true)
            else -> setBluetooth(false) && setSpeakerphone(false)
        }
    }

    /** Label for a route, falling back to the type when a device reports no name. */
    private fun routeName(device: AudioDeviceInfo): String {
        val product = device.productName?.toString()?.trim().orEmpty()
        return when {
            device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone"
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            product.isNotEmpty() -> product
            device.type in BLUETOOTH_TYPES -> "Bluetooth"
            else -> "Headset"
        }
    }

    /**
     * Routes to a connected Bluetooth headset unless the user has chosen otherwise.
     *
     * With no headset connected the platform default (earpiece) is left alone.
     */
    private fun applyPreferredRoute() {
        if (!isActive || routeChosenByUser) return
        if (bluetoothOutput() == null) return
        if (routeToBluetooth(true)) {
            isBluetoothOn = true
            isSpeakerOn = false
            Log.d(TAG, "applyPreferredRoute: routed call audio to Bluetooth")
        } else {
            // Most likely a missing BLUETOOTH_CONNECT grant on API 31+: the headset is
            // connected but we are not allowed to route to it. Logged at error because
            // the symptom is a silent headset with nothing else to go on.
            Log.e(TAG, "applyPreferredRoute: a Bluetooth headset is connected but routing to it failed")
        }
    }

    private fun routeToBluetooth(on: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!on) {
                audioManager.clearCommunicationDevice()
                return true
            }
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type in BLUETOOTH_TYPES }
            if (device == null) {
                Log.w(TAG, "routeToBluetooth: no Bluetooth communication device available")
                return false
            }
            return audioManager.setCommunicationDevice(device)
        }
        @Suppress("DEPRECATION")
        if (on) audioManager.startBluetoothSco() else audioManager.stopBluetoothSco()
        return true
    }

    /** A connected Bluetooth output, or null. Covers LE Audio as well as classic SCO. */
    private fun bluetoothOutput(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in BLUETOOTH_TYPES }

    /**
     * Re-applies the preferred route as devices come and go, so a headset connected after
     * the call started is used, and disconnecting one falls back to what is left.
     */
    private fun registerDeviceCallback() {
        if (deviceCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                applyPreferredRoute()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                val lostBluetooth = removedDevices?.any { it.type in BLUETOOTH_TYPES } == true
                if (lostBluetooth && isBluetoothOn) {
                    // The chosen route physically went away, so that choice no longer
                    // applies and audio should follow whatever is still connected.
                    isBluetoothOn = false
                    routeChosenByUser = false
                    routeToBluetooth(false)
                    applyPreferredRoute()
                }
            }
        }
        deviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, null)
    }

    private fun unregisterDeviceCallback() {
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
    }

    /**
     * A cellular call taking the audio reaches us as a focus loss and nothing else — the
     * telephony stack does not announce itself, and reading the call state directly would
     * need a `READ_PHONE_STATE` grant the host app never asks for.
     *
     * A duckable loss is a notification tone rather than another conversation, so it is
     * ignored; ducking a call for a chime would be worse than the chime.
     */
    private val onFocusChange = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> handleFocusLost(permanent = true)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> handleFocusLost(permanent = false)
            AudioManager.AUDIOFOCUS_GAIN -> resumeAfterFocusLoss()
            else -> Unit
        }
    }

    /**
     * Resumes once the handset call is over, for the devices that never send the matching
     * `AUDIOFOCUS_GAIN`.
     *
     * A permanent loss drops us from the focus stack altogether, so for those there is no
     * gain to wait for. Bounded by the call itself: [onCallEnded] cancels it.
     */
    private val nativeCallWatchdog = object : Runnable {
        override fun run() {
            if (!isActive || !focusLost) return
            if (!isOnCellularCall()) {
                Log.i(TAG, "nativeCallWatchdog: the native call ended, resuming the app call")
                resumeAfterFocusLoss()
                return
            }
            handler.postDelayed(this, NATIVE_CALL_POLL_MS)
        }
    }

    private fun handleFocusLost(permanent: Boolean) {
        if (!isActive || focusLost) return
        focusLost = true
        focusLostPermanently = permanent
        Log.i(TAG, "handleFocusLost: lost audio focus (permanent=$permanent), suspending the app call")
        focusListener?.onAudioFocusLost()
        handler.removeCallbacks(nativeCallWatchdog)
        handler.postDelayed(nativeCallWatchdog, NATIVE_CALL_POLL_MS)
    }

    private fun resumeAfterFocusLoss() {
        if (!focusLost) return
        // Some devices hand focus back while the handset call is still connected. Taking
        // the mode back now would break that call, so wait for the watchdog instead.
        if (isOnCellularCall()) {
            Log.i(TAG, "resumeAfterFocusLoss: focus returned but a native call is still up, waiting")
            handler.removeCallbacks(nativeCallWatchdog)
            handler.postDelayed(nativeCallWatchdog, NATIVE_CALL_POLL_MS)
            return
        }
        focusLost = false
        handler.removeCallbacks(nativeCallWatchdog)
        if (!isActive) return

        // The native call set MODE_IN_CALL and picked its own output; both have to be
        // claimed back before the media is worth un-holding.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (focusLostPermanently) {
            abandonAudioFocus()
            requestAudioFocus()
        }
        focusLostPermanently = false
        reapplyRoute()
        Log.i(TAG, "resumeAfterFocusLoss: audio focus is ours again, resuming the app call")
        focusListener?.onAudioFocusRegained()
    }

    /** Puts the call back on the output it was using before the interruption. */
    private fun reapplyRoute() {
        when {
            isBluetoothOn -> routeToBluetooth(true)
            isSpeakerOn -> setRoute(true, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            routeChosenByUser -> setRoute(false, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            else -> applyPreferredRoute()
        }
    }

    private fun requestAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(onFocusChange, handler)
            .build()
        audioManager.requestAudioFocus(focusRequest!!)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    @Suppress("DEPRECATION")
    private fun setRoute(enable: Boolean, deviceType: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!enable) {
                audioManager.clearCommunicationDevice()
                return true
            }
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == deviceType }
            return if (device != null) {
                audioManager.setCommunicationDevice(device)
            } else {
                Log.w(TAG, "setRoute: no communication device found for type $deviceType")
                false
            }
        }

        when (deviceType) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> audioManager.isSpeakerphoneOn = enable
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> if (enable) audioManager.startBluetoothSco() else audioManager.stopBluetoothSco()
        }
        return true
    }

    companion object {
        private const val TAG = "TVAudioManager"

        /** How often to re-check whether the handset call is over. */
        private const val NATIVE_CALL_POLL_MS = 2_000L

        /** Classic SCO plus LE Audio, which reports a distinct type from API 31. */
        private val BLUETOOTH_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

        /** Outputs worth offering the user; anything else is not a call destination. */
        private val SELECTABLE_TYPES: Set<Int> = BLUETOOTH_TYPES + setOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )

        // Below API 31 there are no per-device ids, so the three possible routes get
        // synthetic ones. Negative to avoid colliding with real AudioDeviceInfo ids.
        private const val LEGACY_EARPIECE_ID = -1
        private const val LEGACY_SPEAKER_ID = -2
        private const val LEGACY_BLUETOOTH_ID = -3

        @Volatile
        private var instance: TVAudioManager? = null

        fun getInstance(context: Context): TVAudioManager {
            return instance ?: synchronized(this) {
                instance ?: TVAudioManager(context).also { instance = it }
            }
        }
    }
}
