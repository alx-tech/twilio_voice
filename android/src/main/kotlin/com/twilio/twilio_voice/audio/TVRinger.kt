package com.twilio.twilio_voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class TVRinger(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var ringtone: Ringtone? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isRinging = false

    /**
     * Starts ringing, and reports whether the device was actually alerted.
     *
     * A `false` here means the handset stayed silent and still, which the caller
     * cannot otherwise detect: every failure below is caught and logged, and the
     * incoming-call notification channel is deliberately silent because this
     * class owns the ringtone. Without this signal a failed ringtone produces a
     * call that arrives with no sound, no vibration and no way to tell.
     *
     * Returns true for a deliberately silenced device: the user chose that, so
     * it is not a failure and must not be overridden.
     */
    fun start(): Boolean {
        if (isRinging) return true
        isRinging = true

        return when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> true
            AudioManager.RINGER_MODE_VIBRATE -> vibrate()
            else -> {
                val rang = playRingtone()
                val vibrated = vibrate()
                rang || vibrated
            }
        }
    }

    fun stop() {
        if (!isRinging) return
        isRinging = false

        try {
            ringtone?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop: failed to stop ringtone: $e")
        }
        ringtone = null

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "stop: failed to stop media player: $e")
        }
        mediaPlayer = null

        try {
            vibrator.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "stop: failed to cancel vibration: $e")
        }
    }

    private fun ringtoneAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private fun playRingtone(): Boolean {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE) ?: run {
            Log.w(TAG, "playRingtone: no default ringtone uri found")
            return false
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tone = RingtoneManager.getRingtone(appContext, uri)
                tone.audioAttributes = ringtoneAudioAttributes()
                tone.isLooping = true
                ringtone = tone
                tone.play()
            } else {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(ringtoneAudioAttributes())
                    setDataSource(appContext, uri)
                    isLooping = true
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "playRingtone: failed to play ringtone: $e")
            false
        }
    }

    private fun vibrate(): Boolean {
        return try {
            val pattern = longArrayOf(0, 1000, 1000)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
            true
        } catch (e: Exception) {
            Log.w(TAG, "vibrate: failed to vibrate: $e")
            false
        }
    }

    companion object {
        private const val TAG = "TVRinger"
    }
}
