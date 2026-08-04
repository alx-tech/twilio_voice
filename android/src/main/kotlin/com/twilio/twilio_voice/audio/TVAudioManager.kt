package com.twilio.twilio_voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Owns call audio routing.
 *
 * The FSI rewrite replaced the Telecom ConnectionService with a plain service, so the OS
 * no longer picks a route for us. Without the work below a call always lands on the
 * earpiece even with a headset already connected, and nothing reacts to one being
 * connected mid-call.
 */
class TVAudioManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneOn: Boolean = false
    private var isActive: Boolean = false

    /** Set once the user picks a route by hand, so automatic routing stops fighting them. */
    private var routeChosenByUser: Boolean = false
    private var deviceCallback: AudioDeviceCallback? = null

    var isSpeakerOn: Boolean = false
        private set
    var isBluetoothOn: Boolean = false
        private set

    fun onCallActive() {
        if (isActive) return
        isActive = true
        routeChosenByUser = false
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
        unregisterDeviceCallback()
        setSpeakerphone(false)
        setBluetooth(false)
        abandonAudioFocus()
        audioManager.mode = previousMode
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = previousSpeakerphoneOn
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

    private fun requestAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
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

        /** Classic SCO plus LE Audio, which reports a distinct type from API 31. */
        private val BLUETOOTH_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

        @Volatile
        private var instance: TVAudioManager? = null

        fun getInstance(context: Context): TVAudioManager {
            return instance ?: synchronized(this) {
                instance ?: TVAudioManager(context).also { instance = it }
            }
        }
    }
}
