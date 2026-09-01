package com.twilio.twilio_voice.activities

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
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

        /** Outputs built into the handset; anything else counts as an external route. */
        private val BUILT_IN_TYPES: Set<Int> = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )

        /** Each DTMF key with the letters printed under it; blank where a phone prints none. */
        private val KEYPAD_KEYS: List<Pair<String, String>> = listOf(
            "1" to "", "2" to "ABC", "3" to "DEF",
            "4" to "GHI", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
            "*" to "", "0" to "", "#" to "",
        )
    }

    private var callHandle: String? = null
    private var dismissReceiver: BroadcastReceiver? = null
    private var inCall: Boolean = false
    private var muted: Boolean = false
    private var speakerOn: Boolean = false
    private val dialedDigits = StringBuilder()

    /** Back closes the pad first, so it never drops the rep out of a live call by surprise. */
    private val keypadBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setKeypadOpen(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupLockScreenFlags()
        setContentView(R.layout.activity_incoming_call)

        buildKeypad()
        onBackPressedDispatcher.addCallback(this, keypadBackCallback)
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
            startConnectionServiceAction(TVConnectionService.ACTION_TOGGLE_MUTE) {
                putExtra(TVConnectionService.EXTRA_MUTE_STATE, !muted)
            }
        }

        findViewById<View>(R.id.incall_speaker).setOnClickListener {
            startConnectionServiceAction(TVConnectionService.ACTION_TOGGLE_SPEAKER) {
                putExtra(TVConnectionService.EXTRA_SPEAKER_STATE, !speakerOn)
            }
        }

        findViewById<View>(R.id.incall_route).setOnClickListener {
            showRoutePicker()
        }

        findViewById<View>(R.id.incall_keypad).setOnClickListener {
            setKeypadOpen(true)
        }

        findViewById<View>(R.id.incall_keypad_hide).setOnClickListener {
            setKeypadOpen(false)
        }

        findViewById<View>(R.id.incall_hangup).setOnClickListener {
            startConnectionServiceAction(TVConnectionService.ACTION_HANGUP)
            // Close locally rather than waiting for the disconnect broadcast. The
            // service emits one whenever it can, but this screen shows over the lock
            // screen, so leaving it up on a missed broadcast would sit on top of
            // everything. Hang up must always mean hang up.
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
        refreshRouteControl()

        if (!inCall) {
            inCall = true
            startCallTimer()
            resetKeypad()
        }
    }

    /** Builds the DTMF pad once, as rows of three inflated keys. */
    private fun buildKeypad() {
        val grid = findViewById<LinearLayout>(R.id.incall_keypad_grid)
        val inflater = LayoutInflater.from(this)
        val spacing = resources.getDimensionPixelSize(R.dimen.keypad_key_spacing)

        KEYPAD_KEYS.chunked(3).forEach { rowKeys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = spacing }
            }
            rowKeys.forEach { (digit, letters) ->
                row.addView(createKeypadKey(inflater, row, digit, letters, spacing))
            }
            grid.addView(row)
        }
    }

    private fun createKeypadKey(
        inflater: LayoutInflater,
        parent: ViewGroup,
        digit: String,
        letters: String,
        spacing: Int,
    ): View {
        val key = inflater.inflate(R.layout.view_keypad_key, parent, false)
        key.contentDescription = digit
        key.setOnClickListener { onDigitPressed(digit) }
        (key.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = spacing
            marginEnd = spacing
        }

        key.findViewById<TextView>(R.id.keypad_key_digit).text = digit
        key.findViewById<TextView>(R.id.keypad_key_letters).apply {
            text = letters
            // INVISIBLE, not GONE: a lettered and an unlettered key must stay the same height.
            visibility = if (letters.isEmpty()) View.INVISIBLE else View.VISIBLE
        }
        return key
    }

    /**
     * Sends one tone and echoes it on screen.
     *
     * The echo is the only feedback. A local tone would move the audio stream mid-call,
     * which the cellular-call interruption handling reads as an interruption.
     */
    private fun onDigitPressed(digit: String) {
        startConnectionServiceAction(TVConnectionService.ACTION_SEND_DIGITS) {
            putExtra(TVConnectionService.EXTRA_DIGITS, digit)
        }
        dialedDigits.append(digit)
        findViewById<TextView>(R.id.incall_dialed_digits).apply {
            text = dialedDigits.toString()
            visibility = View.VISIBLE
        }
    }

    private fun setKeypadOpen(open: Boolean) {
        findViewById<View>(R.id.incall_toggles).visibility = if (open) View.GONE else View.VISIBLE
        findViewById<View>(R.id.incall_keypad_panel).visibility = if (open) View.VISIBLE else View.GONE
        keypadBackCallback.isEnabled = open
    }

    private fun resetKeypad() {
        dialedDigits.setLength(0)
        findViewById<TextView>(R.id.incall_dialed_digits).apply {
            text = ""
            visibility = View.GONE
        }
        setKeypadOpen(false)
    }

    /**
     * Offers the route control only when there is a real choice to make.
     *
     * With no headset connected the only outputs are earpiece and speaker, which the
     * speaker toggle already covers, so a third button would just add noise.
     */
    private fun refreshRouteControl() {
        val group = findViewById<View>(R.id.incall_route_group)
        val routes = TVAudioManager.getInstance(applicationContext).availableRoutes()
        group.visibility = if (routes.size > 2) View.VISIBLE else View.GONE
        findViewById<View>(R.id.incall_route).setBackgroundResource(
            if (routes.any { it.isActive && it.type !in BUILT_IN_TYPES }) {
                R.drawable.bg_circle_toggle_on
            } else {
                R.drawable.bg_circle_toggle_off
            }
        )
    }

    /**
     * Lets the user send call audio to any connected output.
     *
     * Each Bluetooth device is listed by its own name, so a rep with a headset and a car
     * kit paired can tell them apart — the point of the picker over a plain toggle.
     */
    private fun showRoutePicker() {
        val audio = TVAudioManager.getInstance(applicationContext)
        val routes = audio.availableRoutes()
        if (routes.isEmpty()) return
        val labels = routes.map { it.name }.toTypedArray()
        val checked = routes.indexOfFirst { it.isActive }
        AlertDialog.Builder(this)
            .setTitle(R.string.call_audio_output_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                audio.selectRoute(routes[which].id)
                // The picker can change the speaker route, so the toggle has to follow.
                speakerOn = audio.isSpeakerOn
                updateAudioButtons()
                refreshRouteControl()
                dialog.dismiss()
            }
            .show()
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

    private fun startConnectionServiceAction(actionName: String, extras: Intent.() -> Unit = {}) {
        Intent(this, TVConnectionService::class.java).apply {
            action = actionName
            putExtra(TVConnectionService.EXTRA_CALL_HANDLE, callHandle)
            extras()
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
