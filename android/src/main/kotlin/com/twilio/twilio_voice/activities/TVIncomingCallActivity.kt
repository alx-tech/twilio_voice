package com.twilio.twilio_voice.activities

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.twilio.twilio_voice.R
import com.twilio.twilio_voice.receivers.TVBroadcastReceiver
import com.twilio.twilio_voice.service.TVConnectionService
import com.twilio.twilio_voice.types.TVNativeCallActions

class TVIncomingCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CALLER_DISPLAY_NAME: String = "EXTRA_CALLER_DISPLAY_NAME"
    }

    private var callHandle: String? = null
    private var dismissReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupLockScreenFlags()
        setContentView(R.layout.activity_incoming_call)

        callHandle = intent.getStringExtra(TVConnectionService.EXTRA_CALL_HANDLE)
        val callerDisplayName = intent.getStringExtra(EXTRA_CALLER_DISPLAY_NAME)

        findViewById<TextView>(R.id.incoming_call_caller_name).text =
            callerDisplayName?.takeIf { it.isNotBlank() } ?: getString(R.string.unknown_caller)

        findViewById<Button>(R.id.incoming_call_accept).setOnClickListener {
            startConnectionServiceAction(TVConnectionService.ACTION_ANSWER)
            finish()
        }

        findViewById<Button>(R.id.incoming_call_decline).setOnClickListener {
            startConnectionServiceAction(TVConnectionService.ACTION_REJECT)
            finish()
        }

        registerDismissReceiver()
    }

    override fun onDestroy() {
        dismissReceiver?.let { LocalBroadcastManager.getInstance(this).unregisterReceiver(it) }
        super.onDestroy()
    }

    private fun startConnectionServiceAction(actionName: String) {
        Intent(this, TVConnectionService::class.java).apply {
            action = actionName
            putExtra(TVConnectionService.EXTRA_CALL_HANDLE, callHandle)
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
            addAction(TVNativeCallActions.ACTION_ANSWERED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter)
    }
}
