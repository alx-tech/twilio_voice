package com.twilio.twilio_voice.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.twilio.twilio_voice.receivers.TVBroadcastReceiver

/**
 * Owns call audio routing.
 *
 * The FSI rewrite replaced the Telecom ConnectionService with a plain service, so the OS
 * no longer picks a route for us. A call starts on the handset (or a wired headset) and
 * goes to Bluetooth only when the user picks it: a paired device the rep is not wearing
 * would otherwise take the call silently, and a dead microphone is all anyone would see.
 *
 * Telecom also used to arbitrate between an app call and a call on the handset. It no
 * longer does, so [CallAudioFocusListener] carries that duty: once a call on the handset
 * takes the audio, the app call is suspended instead of leaving two conversations fighting
 * over the microphone. Any other app taking the focus leaves the call live.
 *
 * It also watches whether the system is silencing the call's microphone, which happens when
 * the app is in the background without the microphone foreground-service type, and tells
 * [MicrophoneListener] so the rep can be sent back to the call screen.
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

    /** Told when the system starts and stops silencing the call's microphone. */
    interface MicrophoneListener {
        fun onMicrophoneSilenced()
        fun onMicrophoneRestored()
    }

    /**
     * How a *ringing* invite is told to stop making noise while something else owns the audio.
     *
     * Separate from [CallAudioFocusListener] because a ringing call has no media to hold — the
     * only thing worth suspending is the ringtone, and the ringer belongs to the service rather
     * than the connection. Silencing instead of rejecting keeps the invite available: a focus
     * loss is not proof of a phone call, so discarding the call on one would lose real calls.
     */
    interface RingAudioFocusListener {
        fun onRingFocusLost()
        fun onRingFocusRegained()
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val broadcastManager = LocalBroadcastManager.getInstance(appContext)
    private val handler = Handler(Looper.getMainLooper())

    private var focusRequest: AudioFocusRequest? = null

    // Ring-phase focus is held separately from the active-call fields below: answering runs
    // onCallActive() while the ring focus is still held, and sharing one slot would leak the
    // ring request and hand the call's listener a stale phase.
    private var ringFocusRequest: AudioFocusRequest? = null
    private var ringFocusListener: RingAudioFocusListener? = null
    private var ringFocusLost: Boolean = false

    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneOn: Boolean = false
    private var isActive: Boolean = false

    private var focusListener: CallAudioFocusListener? = null
    private var focusLost: Boolean = false
    private var focusLostPermanently: Boolean = false
    private var focusLostAt: Long = 0L

    /**
     * Whether `MODE_IN_CALL` was seen since focus was lost, i.e. the interruption really
     * is a call on the handset rather than another app taking the audio.
     */
    private var sawNativeCall: Boolean = false

    /** Whether the call was actually held, which only a call on the handset does. */
    private var suspended: Boolean = false

    var microphoneListener: MicrophoneListener? = null
    private var microphoneSilenced: Boolean = false
    private var silencedChecks: Int = 0

    private var deviceCallback: AudioDeviceCallback? = null

    /**
     * The Bluetooth device the call is on (API 31+), so a second paired device going out of
     * range does not drop the call, and a resume puts it back on the same one.
     */
    private var bluetoothDeviceId: Int? = null

    var isSpeakerOn: Boolean = false
        private set
    var isBluetoothOn: Boolean = false
        private set

    fun onCallActive(listener: CallAudioFocusListener? = null) {
        if (isActive) return
        // Answering an invite we were ringing for: drop the ringtone's focus before asking for
        // the call's, or the ring request stays on the focus stack for the life of the process.
        onRingingEnded()
        isActive = true
        focusListener = listener
        previousMode = audioManager.mode
        previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!requestAudioFocus()) {
            // Worth connecting anyway — the call is more useful than silence — but this is
            // the one line that explains audio nobody can hear from the first second.
            Log.w(TAG, "onCallActive: audio focus was refused, call audio may be inaudible")
        }
        registerDeviceCallback()
        isSpeakerOn = false
        isBluetoothOn = false
        bluetoothDeviceId = null
        routeToHandset()
        microphoneSilenced = false
        silencedChecks = 0
        handler.postDelayed(microphoneWatchdog, MICROPHONE_CHECK_MS)
    }

    fun onCallEnded() {
        if (!isActive) return
        isActive = false
        focusLost = false
        focusLostPermanently = false
        sawNativeCall = false
        suspended = false
        focusListener = null
        handler.removeCallbacks(nativeCallWatchdog)
        handler.removeCallbacks(microphoneWatchdog)
        microphoneSilenced = false
        unregisterDeviceCallback()

        // A call on the handset may still be running — this call may even have been
        // suspended for it. Putting the mode and route back the way we found them would
        // take the handset's audio with us and break the conversation the rep is still in.
        val nativeCallInProgress = isOnCellularCall()
        if (nativeCallInProgress) {
            Log.i(TAG, "onCallEnded: a native call is still in progress, leaving audio mode and route alone")
        }
        releaseRoute(nativeCallInProgress)
        abandonAudioFocus()
        if (!nativeCallInProgress) {
            audioManager.mode = previousMode
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = previousSpeakerphoneOn
        }
        isSpeakerOn = false
        isBluetoothOn = false
        bluetoothDeviceId = null
    }

    fun setSpeakerphone(on: Boolean): Boolean {
        if (!on && !isSpeakerOn) return true
        val result = if (on) setRoute(true, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) else routeToHandset()
        isSpeakerOn = on
        if (on) {
            isBluetoothOn = false
            bluetoothDeviceId = null
        }
        return result
    }

    fun setBluetooth(on: Boolean): Boolean {
        if (!on && !isBluetoothOn) return true
        val result = if (on) routeToBluetooth() else routeToHandset()
        isBluetoothOn = on && result
        if (!isBluetoothOn) bluetoothDeviceId = null
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
     * Whether the handset is already ringing for someone else.
     *
     * [isOnCellularCall] only sees a call that has been *answered* — the telephony stack sets
     * `MODE_IN_CALL` at that point and not before — so a cellular call that is merely ringing
     * used to pass straight through the guard, and we rang over the top of it. Both ringtones
     * are the device default (see `TVRinger`), so the rep heard one tone doubled and had two
     * full-screen call screens fighting for the foreground.
     *
     * `MODE_RINGTONE` is not proof that the other caller is on the *cellular* network: another
     * VoIP app ringing sets it too. That is deliberate — a rep already being rung by someone is
     * unavailable either way, and telling the two apart would need the `READ_PHONE_STATE`
     * runtime grant this plugin avoids (see the note on [isOnCellularCall]).
     *
     * Verified on API 30 and API 34: ringing reports `MODE_RINGTONE`, answering `MODE_IN_CALL`.
     */
    fun isDeviceRinging(): Boolean = audioManager.mode == AudioManager.MODE_RINGTONE

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
     * The choice holds for this call only; the next call starts on the handset again.
     */
    fun selectRoute(routeId: Int): Boolean {
        if (!isActive) return false
        val applied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.id == routeId }
            if (device == null) {
                Log.w(TAG, "selectRoute: route $routeId is no longer available")
                return false
            }
            audioManager.setCommunicationDevice(device).also {
                if (it) {
                    isSpeakerOn = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    isBluetoothOn = device.type in BLUETOOTH_TYPES
                    bluetoothDeviceId = if (isBluetoothOn) device.id else null
                }
            }
        } else {
            when (routeId) {
                LEGACY_SPEAKER_ID -> setSpeakerphone(true)
                LEGACY_BLUETOOTH_ID -> setBluetooth(true)
                else -> setBluetooth(false) && setSpeakerphone(false)
            }
        }
        if (applied) broadcastRoutesChanged()
        return applied
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
     * Puts the call on the handset: a wired or USB headset if one is plugged in, otherwise
     * the earpiece.
     *
     * Selected explicitly rather than by clearing the communication device, because with a
     * Bluetooth headset connected the platform default can be that headset — which is the
     * very route this exists to avoid.
     */
    private fun routeToHandset(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val device = HANDSET_TYPES.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
            if (device == null) {
                // No earpiece at all (a tablet): the platform default is the only option.
                audioManager.clearCommunicationDevice()
                return true
            }
            return audioManager.setCommunicationDevice(device).also {
                if (!it) Log.w(TAG, "routeToHandset: could not select ${device.type}, the call may be on the platform default")
            }
        }
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        return true
    }

    /** Below API 31 there is one combined Bluetooth route, so any Bluetooth loss is ours. */
    private fun lostDeviceInUse(removedDevices: Array<out AudioDeviceInfo>?): Boolean {
        val removed = removedDevices.orEmpty()
        val inUse = bluetoothDeviceId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && inUse != null) {
            return removed.any { it.id == inUse }
        }
        return removed.any { it.type in BLUETOOTH_TYPES }
    }

    private fun isOnHandset(): Boolean = isActive && !isSpeakerOn && !isBluetoothOn

    /**
     * Hands the output back to the platform once our call is over.
     *
     * From API 31 this only withdraws our own request, so it is safe during a native call
     * and must happen then too, or our earpiece choice outlives the call. The legacy SCO
     * switch is global, so it is left alone while the handset is in use.
     */
    private fun releaseRoute(nativeCallInProgress: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
            return
        }
        if (!nativeCallInProgress) {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        }
    }

    /** Sends the call to the Bluetooth device in use if it is still there, else the first one. */
    private fun routeToBluetooth(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices.filter { it.type in BLUETOOTH_TYPES }
            val device = devices.firstOrNull { it.id == bluetoothDeviceId } ?: devices.firstOrNull()
            if (device == null) {
                Log.w(TAG, "routeToBluetooth: no Bluetooth communication device available")
                return false
            }
            return audioManager.setCommunicationDevice(device).also { if (it) bluetoothDeviceId = device.id }
        }
        // The speakerphone outranks SCO, so leaving it on would keep the audio on the speaker.
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        return true
    }

    /** A connected Bluetooth output, or null. Covers LE Audio as well as classic SCO. */
    private fun bluetoothOutput(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in BLUETOOTH_TYPES }

    /**
     * Tells the call screens when outputs come and go, so they can offer a headset that
     * connected mid-call. A new device never takes the call by itself; losing the one in
     * use falls back to the handset.
     */
    private fun registerDeviceCallback() {
        if (deviceCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                // Plugging a headset in is a deliberate act, unlike a paired device coming
                // into range, so a wired one still takes a call that is on the handset.
                if (isOnHandset() && addedDevices?.any { it.type in WIRED_TYPES } == true) {
                    routeToHandset()
                }
                broadcastRoutesChanged()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                val lostWired = removedDevices?.any { it.type in WIRED_TYPES } == true
                if (isBluetoothOn && lostDeviceInUse(removedDevices)) {
                    isBluetoothOn = false
                    bluetoothDeviceId = null
                    routeToHandset()
                } else if (lostWired && isOnHandset()) {
                    routeToHandset()
                }
                broadcastRoutesChanged()
            }
        }
        deviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, null)
    }

    private fun unregisterDeviceCallback() {
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
    }

    private fun broadcastRoutesChanged() {
        if (!isActive) return
        broadcastManager.sendBroadcast(Intent(TVBroadcastReceiver.ACTION_AUDIO_ROUTES_CHANGED))
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
            AudioManager.AUDIOFOCUS_GAIN -> attemptResume()
            else -> Unit
        }
    }

    /**
     * Resumes once the handset call is over, for the devices that never send the matching
     * `AUDIOFOCUS_GAIN`.
     *
     * Only a call we actually saw take the handset counts: another VoIP app or a voice
     * assistant takes the focus without setting `MODE_IN_CALL`, and those leave the call
     * live. The watchdog keeps polling while focus is lost, so a handset call answered
     * after a long ring is still caught.
     *
     * Bounded by the call itself: [onCallEnded] cancels it.
     */
    private val nativeCallWatchdog = object : Runnable {
        override fun run() {
            if (!isActive || !focusLost) return
            if (isOnCellularCall()) {
                sawNativeCall = true
                suspendForNativeCall()
                handler.postDelayed(this, NATIVE_CALL_POLL_MS)
                return
            }
            if (sawNativeCall) {
                Log.i(TAG, "nativeCallWatchdog: the native call ended, resuming the app call")
                attemptResume()
                return
            }
            if (focusLostPermanently && SystemClock.elapsedRealtime() - focusLostAt >= NATIVE_CALL_DETECT_WINDOW_MS) {
                // Nothing will hand the focus back, so re-acquiring it is the only way out.
                Log.i(TAG, "nativeCallWatchdog: permanent loss with no native call, trying to take focus back")
                attemptResume()
                return
            }
            // Another app has the focus, or a handset call is only ringing. The call stays
            // live; keep watching in case the rep answers a handset call later.
            handler.postDelayed(this, NATIVE_CALL_POLL_MS)
        }
    }

    private fun handleFocusLost(permanent: Boolean) {
        if (!isActive || focusLost) return
        focusLost = true
        focusLostPermanently = permanent
        focusLostAt = SystemClock.elapsedRealtime()
        sawNativeCall = isOnCellularCall()
        Log.i(TAG, "handleFocusLost: lost audio focus (permanent=$permanent, nativeCall=$sawNativeCall)")
        // Holding stops the audio both ways, so it is kept for a conversation on the handset.
        // For a voice assistant, a voice note or a ringing handset the customer would hear
        // nothing and hang up.
        if (sawNativeCall) suspendForNativeCall()
        scheduleWatchdog()
    }

    private fun suspendForNativeCall() {
        if (suspended) return
        suspended = true
        Log.i(TAG, "suspendForNativeCall: a call on the handset took the audio, suspending the app call")
        focusListener?.onAudioFocusLost()
    }

    /**
     * Brings the call back, but only once it can actually be heard.
     *
     * Bails out — leaving the call suspended and the watchdog running — while a handset
     * call is still up, or when the focus request needed after a permanent loss is
     * refused. Unholding without focus would resume a call nobody can hear and leave no
     * way back.
     */
    private fun attemptResume() {
        if (!focusLost) return
        if (isOnCellularCall()) {
            // Seen here as well as in the watchdog, so a gain that arrives before the
            // handset call is over still counts as having identified the interruption.
            sawNativeCall = true
            Log.i(TAG, "attemptResume: a native call is still up, staying suspended")
            scheduleWatchdog()
            return
        }
        if (!isActive) {
            focusLost = false
            handler.removeCallbacks(nativeCallWatchdog)
            return
        }
        // A permanent loss dropped us off the focus stack, so the request has to be made
        // again — and honoured — before the media is worth un-holding.
        if (focusLostPermanently) {
            abandonAudioFocus()
            if (!requestAudioFocus()) {
                Log.w(TAG, "attemptResume: audio focus refused, staying suspended")
                scheduleWatchdog()
                return
            }
        }
        focusLost = false
        focusLostPermanently = false
        sawNativeCall = false
        handler.removeCallbacks(nativeCallWatchdog)

        // The interruption set its own mode and output; both have to be claimed back.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        reapplyRoute()
        if (suspended) {
            suspended = false
            Log.i(TAG, "attemptResume: audio focus is ours again, resuming the app call")
            focusListener?.onAudioFocusRegained()
        }
    }

    /**
     * Polls whether the system silences the call's recording.
     *
     * Polled, so a change of the silenced state alone is never missed. A short silence is
     * normal while the microphone type is claimed at connect, so only a silence over several
     * checks is reported.
     */
    private val microphoneWatchdog = object : Runnable {
        override fun run() {
            if (!isActive) return
            // While another app or a handset call has the audio, a silenced microphone is
            // expected and opening the call screen would not help.
            val silenced = !focusLost && isCallRecordingSilenced()
            if (silenced) {
                silencedChecks++
                if (!microphoneSilenced && silencedChecks >= MICROPHONE_SILENCED_CHECKS) {
                    microphoneSilenced = true
                    microphoneListener?.onMicrophoneSilenced()
                }
            } else {
                silencedChecks = 0
                if (microphoneSilenced) {
                    microphoneSilenced = false
                    microphoneListener?.onMicrophoneRestored()
                }
            }
            handler.postDelayed(this, MICROPHONE_CHECK_MS)
        }
    }

    /** The call records with VOICE_COMMUNICATION; other sources are not the call. */
    private fun isCallRecordingSilenced(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return audioManager.activeRecordingConfigurations.any { config: AudioRecordingConfiguration ->
            config.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION && config.isClientSilenced
        }
    }

    private fun scheduleWatchdog() {
        handler.removeCallbacks(nativeCallWatchdog)
        handler.postDelayed(nativeCallWatchdog, NATIVE_CALL_POLL_MS)
    }

    /** Puts the call back on the output it was using before the interruption. */
    private fun reapplyRoute() {
        when {
            isBluetoothOn -> routeToBluetooth()
            isSpeakerOn -> setRoute(true, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            else -> routeToHandset()
        }
    }

    /**
     * Asks for focus, reporting whether it was granted.
     *
     * Delayed gain is not requested, so `AUDIOFOCUS_REQUEST_DELAYED` cannot come back:
     * anything other than granted means another owner is holding on to it.
     */
    private fun requestAudioFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(onFocusChange, handler)
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /**
     * Takes focus for the ringtone, so a cellular call arriving *while we ring* is noticed.
     *
     * A *granted* request is deliberately not read as a verdict on whether the handset is
     * ringing: `AUDIOFOCUS_GAIN_TRANSIENT` is granted unless the current owner took it
     * exclusively, and the telephony ringer holds a plain one. [isDeviceRinging] answers that
     * question before we ever get here. What holding focus buys is the loss and gain
     * callbacks: without them we are never told that something else took the audio, which is
     * the whole of the reverse direction.
     *
     * A *refused* request does carry information, so it is reported rather than swallowed —
     * refusal means an owner holds the audio exclusively, and the telephony ringer is exactly
     * the thing that does that (`AUDIOFOCUS_FLAG_LOCK`). Only a granted request is recorded:
     * keeping one we do not hold would leave [onRingingEnded] abandoning nothing, block a
     * later attempt, and promise a silencing callback that can never arrive.
     *
     * `USAGE_NOTIFICATION_RINGTONE` matches what `TVRinger` plays with, so the request
     * describes the sound it is actually protecting.
     *
     * @return whether focus is held, i.e. whether the ringtone can still be silenced later.
     * Also `false` when there is deliberately nothing to protect (see the ringer-mode check).
     */
    fun onRingingStarted(listener: RingAudioFocusListener): Boolean {
        if (ringFocusRequest != null) return true
        // Nothing to protect on a silenced or vibrate-only handset: `TVRinger` plays no tone,
        // there is no doubled sound to prevent, and taking focus would pause whatever the rep
        // is listening to for the length of a ring they cannot hear anyway.
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(onRingFocusChange, handler)
            .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "onRingingStarted: audio focus refused, this ringtone cannot be silenced")
            return false
        }
        ringFocusRequest = request
        ringFocusListener = listener
        ringFocusLost = false
        return true
    }

    /** Releases the ringtone's focus. Safe to call when no invite is ringing. */
    fun onRingingEnded() {
        ringFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        ringFocusRequest = null
        ringFocusListener = null
        ringFocusLost = false
    }

    /**
     * A duckable loss is a chime, not a conversation, so the ringtone plays on — going quiet
     * for a notification tone would be worse than the tone. Anything else means something took
     * the audio, and a rep cannot pick out our ringtone from underneath it.
     */
    private val onRingFocusChange = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (!ringFocusLost) {
                    ringFocusLost = true
                    Log.i(TAG, "onRingFocusChange: lost audio focus while ringing, silencing the ringtone")
                    ringFocusListener?.onRingFocusLost()
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                if (ringFocusLost) {
                    ringFocusLost = false
                    Log.i(TAG, "onRingFocusChange: focus back while still ringing, resuming the ringtone")
                    ringFocusListener?.onRingFocusRegained()
                }
            }

            else -> Unit
        }
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

        /** How often to check that the system is not silencing the call's microphone. */
        private const val MICROPHONE_CHECK_MS = 500L

        /** Consecutive silenced checks before the rep is told: 1.5 s rules out the claim at connect. */
        private const val MICROPHONE_SILENCED_CHECKS = 3

        /**
         * How long after a permanent focus loss with no `MODE_IN_CALL` before trying to take
         * the focus back. The telephony stack sets the mode within a second or so of taking
         * the audio.
         */
        private const val NATIVE_CALL_DETECT_WINDOW_MS = 10_000L

        /** Classic SCO plus LE Audio, which reports a distinct type from API 31. */
        private val BLUETOOTH_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

        private val WIRED_TYPES: List<Int> = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        )

        /** Where a call starts, in order of preference: a plugged-in headset beats the earpiece. */
        private val HANDSET_TYPES: List<Int> = WIRED_TYPES + AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

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
