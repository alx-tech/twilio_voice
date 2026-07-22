package com.twilio.twilio_voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

class TVAudioManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneOn: Boolean = false
    private var isActive: Boolean = false

    var isSpeakerOn: Boolean = false
        private set
    var isBluetoothOn: Boolean = false
        private set

    fun onCallActive() {
        if (isActive) return
        isActive = true
        previousMode = audioManager.mode
        previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        requestAudioFocus()
    }

    fun onCallEnded() {
        if (!isActive) return
        isActive = false
        setSpeakerphone(false)
        setBluetooth(false)
        abandonAudioFocus()
        audioManager.mode = previousMode
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = previousSpeakerphoneOn
    }

    fun setSpeakerphone(on: Boolean): Boolean {
        val result = setRoute(on, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        isSpeakerOn = on
        if (on) isBluetoothOn = false
        return result
    }

    fun setBluetooth(on: Boolean): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRoute(on, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        } else {
            @Suppress("DEPRECATION")
            if (on) audioManager.startBluetoothSco() else audioManager.stopBluetoothSco()
            true
        }
        isBluetoothOn = on
        if (on) isSpeakerOn = false
        return result
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .build()
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
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

        @Volatile
        private var instance: TVAudioManager? = null

        fun getInstance(context: Context): TVAudioManager {
            return instance ?: synchronized(this) {
                instance ?: TVAudioManager(context).also { instance = it }
            }
        }
    }
}
