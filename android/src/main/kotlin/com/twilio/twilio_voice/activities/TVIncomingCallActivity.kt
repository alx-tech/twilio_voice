package com.twilio.twilio_voice.activities

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.twilio.twilio_voice.R
import com.twilio.twilio_voice.audio.TVAudioManager
import com.twilio.twilio_voice.receivers.TVBroadcastReceiver
import com.twilio.twilio_voice.service.TVConnectionService
import com.twilio.twilio_voice.types.TVNativeCallActions
import com.twilio.twilio_voice.types.TVNativeCallEvents

class TVIncomingCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CALLER_DISPLAY_NAME: String = "EXTRA_CALLER_DISPLAY_NAME"
        const val EXTRA_IN_CALL: String = "EXTRA_IN_CALL"
    }

    private var callHandle: String? = null
    private var dismissReceiver: BroadcastReceiver? = null
    private var inCall: Boolean = false
    private var muted: Boolean = false
    private var speakerOn: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupLockScreenFlags()
        setContentView(R.layout.activity_incoming_call)

        applyIntent(intent)
        registerDismissReceiver()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    override fun onDestroy() {
        dismissReceiver?.let { LocalBroadcastManager.getInstance(this).unregisterReceiver(it) }
        super.onDestroy()
    }

    private fun applyIntent(intent: Intent) {
        callHandle = intent.getStringExtra(TVConnectionService.EXTRA_CALL_HANDLE)
        val callerDisplayName = intent.getStringExtra(EXTRA_CALLER_DISPLAY_NAME)?.takeIf { it.isNotBlank() }

        findViewById<TextView>(R.id.incoming_call_caller_name).text =
            callerDisplayName ?: getString(R.string.unknown_caller)
        updateAvatar(callerDisplayName)

        findViewById<View>(R.id.incoming_call_accept).setOnClickListener {
            startConnectionServiceAction(TVConnectionService.ACTION_ANSWER)
            // Switching immediately keeps the tap feeling instant, which is only safe
            // because the service now broadcasts ACTION_CALL_ENDED when the invite has
            // already gone — so a call answered a moment too late closes this screen
            // instead of leaving a running timer with nothing behind it.
            switchToInCall()
        }

        findViewById<View>(R.id.incoming_call_decline).setOnClickListener {
            startConnectionServiceAction(TVConnectionService.ACTION_REJECT)
            finish()
        }

        findViewById<View>(R.id.incall_mute).setOnClickListener {
            startConnectionServiceAction(TVConnectionService.ACTION_TOGGLE_MUTE, mapOf(TVConnectionService.EXTRA_MUTE_STATE to !muted))
        }

        findViewById<View>(R.id.incall_speaker).setOnClickListener {
            startConnectionServiceAction(TVConnectionService.ACTION_TOGGLE_SPEAKER, mapOf(TVConnectionService.EXTRA_SPEAKER_STATE to !speakerOn))
        }

        findViewById<View>(R.id.incall_hangup).setOnClickListener {
            startConnectionServiceAction(TVConnectionService.ACTION_HANGUP)
            // Close locally rather than waiting for the disconnect broadcast. The
            // service emits one whenever it can, but this screen shows over the lock
            // screen and is excluded from recents, so if a broadcast is ever missed
            // the user has no other way out. Hang up must always mean hang up.
            finish()
        }

        if (intent.getBooleanExtra(EXTRA_IN_CALL, false)) {
            switchToInCall()
        }
    }

    private fun computeInitials(name: String?): String? {
        val parts = name?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
        val letters = parts.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
        return letters.ifEmpty { null }
    }

    private fun updateAvatar(callerDisplayName: String?) {
        val initials = computeInitials(callerDisplayName)
        val initialsView = findViewById<TextView>(R.id.incoming_call_avatar_initials)
        val iconView = findViewById<ImageView>(R.id.incoming_call_avatar_icon)
        if (initials != null) {
            initialsView.text = initials
            initialsView.visibility = View.VISIBLE
            iconView.visibility = View.GONE
        } else {
            initialsView.visibility = View.GONE
            iconView.visibility = View.VISIBLE
        }
    }

    private fun switchToInCall() {
        findViewById<View>(R.id.incoming_call_status).visibility = View.GONE
        findViewById<View>(R.id.incoming_call_ringing_actions).visibility = View.GONE
        findViewById<View>(R.id.incall_actions).visibility = View.VISIBLE

        val connection = callHandle?.let { TVConnectionService.getConnection(it) }
        muted = connection?.isMuted ?: false
        speakerOn = TVAudioManager.getInstance(applicationContext).isSpeakerOn
        updateAudioButtons()

        if (!inCall) {
            inCall = true
            startCallTimer()
        }
    }

    private fun startCallTimer() {
        findViewById<Chronometer>(R.id.incall_timer).apply {
            visibility = View.VISIBLE
            base = SystemClock.elapsedRealtime()
            setOnChronometerTickListener { chronometer ->
                val elapsedSeconds = ((SystemClock.elapsedRealtime() - chronometer.base) / 1000).toInt()
                chronometer.text = String.format("%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)
            }
            start()
        }
    }

    private fun updateAudioButtons() {
        setToggleState(R.id.incall_mute, R.id.incall_mute_icon, muted, R.drawable.ic_mic_off, R.drawable.ic_mic)
        setToggleState(R.id.incall_speaker, R.id.incall_speaker_icon, speakerOn, R.drawable.ic_volume_up, R.drawable.ic_volume_down)
    }

    private fun setToggleState(containerId: Int, iconId: Int, active: Boolean, activeIcon: Int, inactiveIcon: Int) {
        findViewById<View>(containerId).setBackgroundResource(
            if (active) R.drawable.bg_circle_toggle_on else R.drawable.bg_circle_toggle_off
        )
        findViewById<ImageView>(iconId).apply {
            setImageResource(if (active) activeIcon else inactiveIcon)
            setColorFilter(getColor(if (active) R.color.call_screen_icon_active else R.color.white))
        }
    }

    private fun startConnectionServiceAction(actionName: String, extras: Map<String, Boolean> = emptyMap()) {
        Intent(this, TVConnectionService::class.java).apply {
            action = actionName
            putExtra(TVConnectionService.EXTRA_CALL_HANDLE, callHandle)
            extras.forEach { (key, value) -> putExtra(key, value) }
        }.also { startService(it) }
    }

    private fun setupLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        keyguardManager?.requestDismissKeyguard(this, null)
    }

    private fun registerDismissReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == TVBroadcastReceiver.ACTION_AUDIO_STATE) {
                    muted = intent.getBooleanExtra(TVBroadcastReceiver.EXTRA_MUTE_STATE, muted)
                    speakerOn = intent.getBooleanExtra(TVBroadcastReceiver.EXTRA_SPEAKER_STATE, speakerOn)
                    updateAudioButtons()
                    return
                }

                val handle = intent.getStringExtra(TVBroadcastReceiver.EXTRA_CALL_HANDLE)
                if (handle == null || handle == callHandle) {
                    finish()
                }
            }
        }
        dismissReceiver = receiver

        val filter = IntentFilter().apply {
            addAction(TVBroadcastReceiver.ACTION_CALL_ENDED)
            addAction(TVNativeCallActions.ACTION_ABORT)
            addAction(TVNativeCallActions.ACTION_REJECTED)
            addAction(TVNativeCallEvents.EVENT_DISCONNECTED_LOCAL)
            addAction(TVNativeCallEvents.EVENT_DISCONNECTED_REMOTE)
            addAction(TVBroadcastReceiver.ACTION_AUDIO_STATE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter)
    }
}
