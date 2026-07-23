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

    fun start() {
        if (isRinging) return
        isRinging = true

        when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> { }
            AudioManager.RINGER_MODE_VIBRATE -> vibrate()
            else -> {
                playRingtone()
                vibrate()
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

    private fun playRingtone() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE) ?: run {
            Log.w(TAG, "playRingtone: no default ringtone uri found")
            return
        }

        try {
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
        } catch (e: Exception) {
            Log.w(TAG, "playRingtone: failed to play ringtone: $e")
        }
    }

    private fun vibrate() {
        try {
            val pattern = longArrayOf(0, 1000, 1000)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } catch (e: Exception) {
            Log.w(TAG, "vibrate: failed to vibrate: $e")
        }
    }

    companion object {
        private const val TAG = "TVRinger"
    }
}
