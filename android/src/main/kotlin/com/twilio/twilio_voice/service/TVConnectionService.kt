package com.twilio.twilio_voice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telecom.DisconnectCause
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.twilio.twilio_voice.R
import com.twilio.twilio_voice.activities.TVIncomingCallActivity
import com.twilio.twilio_voice.audio.TVAudioCodecs
import com.twilio.twilio_voice.audio.TVAudioManager
import com.twilio.twilio_voice.audio.TVRinger
import com.twilio.twilio_voice.call.TVCallInviteParametersImpl
import com.twilio.twilio_voice.call.TVCallParametersImpl
import com.twilio.twilio_voice.call.TVParameters
import com.twilio.twilio_voice.fcm.VoiceFirebaseMessagingService
import com.twilio.twilio_voice.receivers.TVBroadcastReceiver
import com.twilio.twilio_voice.storage.Storage
import com.twilio.twilio_voice.storage.StorageImpl
import com.twilio.twilio_voice.types.CallDirection
import com.twilio.twilio_voice.types.CompletionHandler
import com.twilio.twilio_voice.types.ContextExtension.appName
import com.twilio.twilio_voice.types.IntentExtension.getParcelableExtraSafe
import com.twilio.twilio_voice.types.ValueBundleChanged
import com.twilio.voice.*
import com.twilio.voice.Call

class TVConnectionService : Service() {

    companion object {
        val TAG = "TwilioVoiceConnectionService"

        val activeConnections = ConcurrentHashMap<String, TVCallConnection>()

        val TWI_SCHEME: String = "twi"

        /** Notification id shared by the ringing and ongoing-call notifications. */
        val NOTIFICATION_ID_CALL: Int = 100

        //region ACTIONS_* Constants
        /**
         * Action used with [VoiceFirebaseMessagingService] to notify of incoming calls
         */
        const val ACTION_CALL_INVITE: String = "ACTION_CALL_INVITE"

        //region ACTIONS_* Constants
        /**
         * Action used with [EXTRA_CALL_HANDLE] to cancel a call connection.
         */
        const val ACTION_CANCEL_CALL_INVITE: String = "ACTION_CANCEL_CALL_INVITE"

        /**
         * Action used with [EXTRA_DIGITS] to send digits to the [TVConnection] active call.
         */
        const val ACTION_SEND_DIGITS: String = "ACTION_SEND_DIGITS"

        /**
         * Action used to hangup an active call connection.
         */
        const val ACTION_HANGUP: String = "ACTION_HANGUP"

        /**
         * Action used to toggle the speakerphone state of an active call connection.
         */
        const val ACTION_TOGGLE_SPEAKER: String = "ACTION_TOGGLE_SPEAKER"

        /**
         * Action used to toggle bluetooth state of an active call connection.
         */
        const val ACTION_TOGGLE_BLUETOOTH: String = "ACTION_TOGGLE_BLUETOOTH"

        /**
         * Action used to toggle hold state of an active call connection.
         */
        const val ACTION_TOGGLE_HOLD: String = "ACTION_TOGGLE_HOLD"

        /**
         * Action used to toggle mute state of an active call connection.
         */
        const val ACTION_TOGGLE_MUTE: String = "ACTION_TOGGLE_MUTE"

        /**
         * Action used to answer an incoming call connection.
         */
        const val ACTION_ANSWER: String = "ACTION_ANSWER"

        /**
         * Action used to decline an incoming call connection.
         */
        const val ACTION_REJECT: String = "ACTION_REJECT"

        /**
         * Action used to answer an incoming call connection.
         */
        const val ACTION_INCOMING_CALL: String = "ACTION_INCOMING_CALL"

        /**
         * Action used to place an outgoing call connection.
         * Additional parameters are required: [EXTRA_TOKEN], [EXTRA_TO] and [EXTRA_FROM]. Optionally, [EXTRA_OUTGOING_PARAMS] for bundled extra custom parameters.
         */
        const val ACTION_PLACE_OUTGOING_CALL: String = "ACTION_PLACE_OUTGOING_CALL"

        /**
         * Action used to poll the ConnectionService for the active call handle.
         */
        const val ACTION_ACTIVE_HANDLE: String = "ACTION_ACTIVE_HANDLE"
        //endregion

        //region EXTRA_* Constants
        /**
         * Extra used with [ACTION_SEND_DIGITS] to send digits to the [TVConnection] active call.
         */
        const val EXTRA_DIGITS: String = "EXTRA_DIGITS"

        /**
         * Extra used with [ACTION_CANCEL_CALL_INVITE] to cancel a call connection.
         */
        const val EXTRA_INCOMING_CALL_INVITE: String = "EXTRA_INCOMING_CALL_INVITE"

        /**
         * Extra used to identify a call connection.
         */
        const val EXTRA_CALL_HANDLE: String = "EXTRA_CALL_HANDLE"

        /**
         * Extra used with [ACTION_CANCEL_CALL_INVITE] to cancel a call connection
         */
        const val EXTRA_CANCEL_CALL_INVITE: String = "EXTRA_CANCEL_CALL_INVITE"

        /**
         * Extra used with [ACTION_PLACE_OUTGOING_CALL] to place an outgoing call connection. Denotes the Twilio Voice access token.
         */
        const val EXTRA_TOKEN: String = "EXTRA_TOKEN"

        /**
         * Extra used with [ACTION_PLACE_OUTGOING_CALL] to place an outgoing call connection, denotes the call parameters treated as a Bundle.
         */
        const val EXTRA_CONNECT_RAW: String = "EXTRA_CONNECT_RAW"

        /**
         * Extra used with [ACTION_PLACE_OUTGOING_CALL] to place an outgoing call connection. Denotes the recipient's identity.
         */
        const val EXTRA_TO: String = "EXTRA_TO"

        /**
         * Extra used with [ACTION_PLACE_OUTGOING_CALL] to place an outgoing call connection. Denotes the caller's identity.
         */
        const val EXTRA_FROM: String = "EXTRA_FROM"

        /**
         * Extra used with [ACTION_PLACE_OUTGOING_CALL] to send additional parameters to the [TVConnectionService] active call.
         */
        const val EXTRA_OUTGOING_PARAMS: String = "EXTRA_OUTGOING_PARAMS"

        /**
         * Extra used with [ACTION_TOGGLE_SPEAKER] to send additional parameters to the [TVCallConnection] active call.
         */
        const val EXTRA_SPEAKER_STATE: String = "EXTRA_SPEAKER_STATE"

        /**
         * Extra used with [ACTION_TOGGLE_BLUETOOTH] to send additional parameters to the [TVCallConnection] active call.
         */
        const val EXTRA_BLUETOOTH_STATE: String = "EXTRA_BLUETOOTH_STATE"

        /**
         * Extra used with [ACTION_TOGGLE_HOLD] to send additional parameters to the [TVCallConnection] active call.
         */
        const val EXTRA_HOLD_STATE: String = "EXTRA_HOLD_STATE"

        /**
         * Extra used with [ACTION_TOGGLE_MUTE] to send additional parameters to the [TVCallConnection] active call.
         */
        const val EXTRA_MUTE_STATE: String = "EXTRA_MUTE_STATE"
        //endregion

        fun hasActiveCalls(): Boolean {
            return activeConnections.isNotEmpty()
        }

        /**
         * Active call definition is extended to include calls in which one can actively communicate, or call is on hold, or call is ringing or dialing. This applies only to this and calling functions.
         * Gets the first ongoing call handle, if any. Else, gets the first call on hold. Lastly, gets the first call in either a ringing or dialing state, if any. Returns null if there are no active calls. If there are more than one active calls, the first call handle is returned.
         * Note: this might not necessarily correspond to the current active call.
         */
        fun getActiveCallHandle(): String? {
            if (!hasActiveCalls()) return null
            return activeConnections.entries.firstOrNull { it.value.state == TVCallConnection.STATE_ACTIVE }?.key
                ?: activeConnections.entries.firstOrNull { it.value.state == TVCallConnection.STATE_HOLDING }?.key
                ?: activeConnections.entries.firstOrNull { arrayListOf(TVCallConnection.STATE_RINGING, TVCallConnection.STATE_DIALING).contains(it.value.state) }?.key
        }

        fun getIncomingCallHandle(): String? {
            if (!hasActiveCalls()) return null
            return activeConnections.entries.firstOrNull { it.value.state == TVCallConnection.STATE_RINGING }?.key
        }

        fun getConnection(callSid: String): TVCallConnection? {
            return activeConnections[callSid]
        }
    }


    private val ringer: TVRinger by lazy { TVRinger(applicationContext) }

    private val audioStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshOngoingNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        LocalBroadcastManager.getInstance(applicationContext)
            .registerReceiver(audioStateReceiver, IntentFilter(TVBroadcastReceiver.ACTION_AUDIO_STATE))
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(applicationContext).unregisterReceiver(audioStateReceiver)
        ringer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun refreshOngoingNotification() {
        if (!hasActiveCalls()) return
        val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_CALL, createNotification())
    }

    private fun stopSelfSafe(): Boolean {
        if (!hasActiveCalls()) {
            stopSelf()
            return true
        } else {
            return false
        }
    }

    /**
     * When a code path started via [android.content.Context.startForegroundService] has nothing
     * to present (e.g. the referenced call is already gone), it must still call [startForeground]
     * before it can stop, or the system throws ForegroundServiceDidNotStartInTimeException.
     * Posts the minimal ongoing-call notification, then immediately tears it down if idle.
     */
    private fun startForegroundThenStopIfIdle() {
        // Reached from background FCM paths (e.g. a cancelled invite) with no call to carry, so
        // never claim the microphone here.
        startForegroundService(wantMicrophone = false)
        if (!hasActiveCalls()) {
            stopForegroundService()
            stopSelf()
        }
    }

    //region Service onStartCommand
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Thread.currentThread().contextClassLoader = CallInvite::class.java.classLoader
        super.onStartCommand(intent, flags, startId)
        intent?.let {
            when (it.action) {
                ACTION_SEND_DIGITS -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getActiveCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_SEND_DIGITS is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }
                    val digits = it.getStringExtra(EXTRA_DIGITS) ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_SEND_DIGITS is missing String EXTRA_DIGITS")
                        return@let
                    }

                    getConnection(callHandle)?.sendDigits(digits) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_SEND_DIGITS] could not find connection for callHandle: $callHandle")
                    }
                }

                ACTION_CANCEL_CALL_INVITE -> {
                    ringer.stop()

                    // Load CancelledCallInvite class loader
                    // See: https://github.com/twilio/voice-quickstart-android/issues/561#issuecomment-1678613170
                    it.setExtrasClassLoader(CallInvite::class.java.classLoader)
                    val cancelledCallInvite = it.getParcelableExtraSafe<CancelledCallInvite>(EXTRA_CANCEL_CALL_INVITE) ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_CANCEL_CALL_INVITE is missing parcelable EXTRA_CANCEL_CALL_INVITE")
                        startForegroundThenStopIfIdle()
                        return@let
                    }

                    val callHandle = cancelledCallInvite.callSid
                    val conn = getConnection(callHandle)
                    val displayName = conn?.callerDisplayName
                        ?: conn?.getCallParameters()?.from
                        ?: cancelledCallInvite.from
                        ?: getString(R.string.unknown_caller)
                    val leadId = conn?.getCallParameters()?.customParameters?.get("lead_id")
                    postMissedCallNotification(callHandle, displayName, leadId)

                    conn?.onAbort() ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_CANCEL_CALL_INVITE] could not find connection for callHandle: $callHandle")
                        startForegroundThenStopIfIdle()
                    }
                }

                ACTION_INCOMING_CALL -> {
                    // Load CallInvite class loader & get callInvite
                    val callInvite = it.getParcelableExtraSafe<CallInvite>(EXTRA_INCOMING_CALL_INVITE) ?: run {
                        Log.e(TAG, "onStartCommand: 'ACTION_INCOMING_CALL' is missing parcelable 'EXTRA_INCOMING_CALL_INVITE'")
                        return@let
                    }

                    // A rep already talking to someone on the handset cannot take this
                    // call, and ringing over a live conversation is worse than useless.
                    // Rejecting rather than ringing silently also tells the caller's flow
                    // straight away, so it moves to the next person instead of waiting
                    // out a ring timeout nobody is going to answer.
                    if (TVAudioManager.getInstance(applicationContext).isOnCellularCall()) {
                        Log.i(TAG, "onStartCommand: [ACTION_INCOMING_CALL] rejecting ${callInvite.callSid} — already on a native call")
                        callInvite.reject(applicationContext)
                        // Started via startForegroundService, so we must still reach
                        // startForeground before stopping or the system kills the app.
                        startForegroundThenStopIfIdle()
                        return@let
                    }

                    // Create storage instance for call parameters
                    val storage: Storage = StorageImpl(applicationContext)

                    // Resolve call parameters
                    val callParams: TVParameters = TVCallInviteParametersImpl(storage, callInvite)

                    // Create connection
                    val connection = TVCallInviteConnection(applicationContext, callInvite, callParams)

                    // Setup connection event listeners and UI parameters
                    attachCallEventListeners(connection, callInvite.callSid)
                    applyParameters(connection, callParams)
                    connection.setRinging()

                    // Ring first so the notification can carry the alert itself when
                    // the ringer produced nothing — otherwise the call arrives with no
                    // sound and no vibration, and nothing says why.
                    val alerted = ringer.start()

                    // Present a full-screen-intent notification + native ringing Activity instead of the system's Telecom incoming call UI
                    startIncomingCallForeground(callInvite.callSid, connection.callerDisplayName ?: callParams.from, alerted)
                }

                ACTION_ANSWER -> {
                    ringer.stop()

                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getIncomingCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_ANSWER is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }

                    val connection = getConnection(callHandle) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_ANSWER] could not find connection for callHandle: $callHandle")
                        broadcastCallGone(callHandle)
                        return@let
                    }

                    if(connection is TVCallInviteConnection) {
                        connection.acceptInvite()
                        // Ensure the native in-call Activity is up — if the answer came from
                        // the heads-up notification action rather than the full-screen ringing
                        // Activity morphing itself, it hasn't been launched yet.
                        launchInCallActivity(callHandle, connection.callerDisplayName ?: connection.getCallParameters()?.from)
                    } else {
                        Log.e(TAG, "onStartCommand: [ACTION_ANSWER] connection for callHandle is not an invite: $callHandle")
                        broadcastCallGone(callHandle)
                    }
                }

                ACTION_REJECT -> {
                    ringer.stop()

                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getIncomingCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_REJECT is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }

                    val connection = getConnection(callHandle) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_REJECT] could not find connection for callHandle: $callHandle")
                        broadcastCallGone(callHandle)
                        return@let
                    }

                    if(connection is TVCallInviteConnection) {
                        connection.rejectInvite()
                    } else {
                        Log.e(TAG, "onStartCommand: [ACTION_REJECT] connection for callHandle is not an invite: $callHandle")
                        broadcastCallGone(callHandle)
                    }
                }

                ACTION_HANGUP -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getActiveCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_HANGUP is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }

                    getConnection(callHandle)?.disconnect() ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_HANGUP] could not find connection for callHandle: $callHandle")
                        broadcastCallGone(callHandle)
                    }
                }

                ACTION_PLACE_OUTGOING_CALL -> {

                    val rawConnect = it.getBooleanExtra(EXTRA_CONNECT_RAW, false)

                    fun getRequiredString(key: String, allowNullIfRaw: Boolean = false): String? {
                        val value = it.getStringExtra(key)
                        if (value == null) {
                            Log.e(TAG, "onStartCommand: ACTION_PLACE_OUTGOING_CALL is missing String $key")
                            if (!rawConnect || !allowNullIfRaw) return null
                        }
                        return value
                    }

                    val token = getRequiredString(EXTRA_TOKEN) ?: return@let
                    val to = getRequiredString(EXTRA_TO, allowNullIfRaw = true)
                    val from = getRequiredString(EXTRA_FROM, allowNullIfRaw = true)

                    val params = HashMap<String, String>().apply {
                        it.getParcelableExtraSafe<Bundle>(EXTRA_OUTGOING_PARAMS)?.let { bundle ->
                            for (key in bundle.keySet()) {
                                bundle.getString(key)?.let { value -> put(key, value) }
                            }
                        }
                        if (!rawConnect) {
                            to?.let { v -> put("To", v) }
                            from?.let { v -> put("From", v) }
                        }
                    }

                    val connectOptions = TVAudioCodecs
                        .applyTo(ConnectOptions.Builder(token).params(params))
                        .build()

                    // Create outgoing connection directly via Voice.connect, bypassing Telecom entirely
                    val connection = TVCallConnection(applicationContext)
                    connection.twilioCall = Voice.connect(applicationContext, connectOptions, connection)

                    // Create storage instance for call parameters
                    val mStorage: Storage = StorageImpl(applicationContext)

                    // Set call state listener, applies non-temporary Call SID when call is ringing or connected (i.e. when assigned by Twilio)
                    val onCallStateListener: CompletionHandler<Call.State> = CompletionHandler { state ->
                        if (state == Call.State.RINGING || state == Call.State.CONNECTED) {
                            val call = connection.twilioCall!!
                            val callSid = call.sid!!

                            // Resolve call parameters
                            val callParams = TVCallParametersImpl(mStorage, call, to ?: "", from ?: "", params)
                            connection.setCallParameters(callParams)

                            // If call is not attached, attach it
                            if (!activeConnections.containsKey(callSid)) {
                                applyParameters(connection, callParams)
                                attachCallEventListeners(connection, callSid)
                                callParams.callSid = callSid
                            }
                        }
                    }

                    // Set call disconnected listener, removes connection from active connections when call is disconnected
                    val onCallInitializingDisconnectedListener: CompletionHandler<DisconnectCause> = CompletionHandler {
                        ringer.stop()
                        connection.twilioCall?.let { call ->
                            if (activeConnections.containsKey(call.sid)) {
                                activeConnections.remove(call.sid)
                            }
                            sendBroadcastEvent(applicationContext, TVBroadcastReceiver.ACTION_CALL_ENDED, call.sid ?: "", connection.extras)
                            stopForegroundService()
                            stopSelfSafe()
                        }
                    }

                    connection.setOnCallStateListener(onCallStateListener)
                    connection.setOnCallDisconnected(onCallInitializingDisconnectedListener)

                    // Setup connection UI parameters
                    connection.setInitializing()

                    // Outgoing call placed from the app, so the mic is in use and the app is
                    // foreground — the microphone claim is both needed and permitted.
                    startForegroundService(wantMicrophone = true)
                }

                ACTION_TOGGLE_BLUETOOTH -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getActiveCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_TOGGLE_BLUETOOTH is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }
                    val bluetoothState = it.getBooleanExtra(EXTRA_BLUETOOTH_STATE, false)

                    getConnection(callHandle)?.toggleBluetooth(bluetoothState) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_TOGGLE_BLUETOOTH] could not find connection for callHandle: $callHandle")
                    }
                }

                ACTION_TOGGLE_HOLD -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getActiveCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_TOGGLE_HOLD is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }
                    val holdState = it.getBooleanExtra(EXTRA_HOLD_STATE, false)

                    getConnection(callHandle)?.toggleHold(holdState) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_TOGGLE_HOLD] could not find connection for callHandle: $callHandle")
                    }
                }

                ACTION_TOGGLE_MUTE -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getActiveCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_TOGGLE_MUTE is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }
                    val muteState = it.getBooleanExtra(EXTRA_MUTE_STATE, false)

                    getConnection(callHandle)?.toggleMute(muteState) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_TOGGLE_MUTE] could not find connection for callHandle: $callHandle")
                    }
                }

                ACTION_TOGGLE_SPEAKER -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getActiveCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_TOGGLE_SPEAKER is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }
                    val speakerState = it.getBooleanExtra(EXTRA_SPEAKER_STATE, false)
                    getConnection(callHandle)?.toggleSpeaker(speakerState) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_TOGGLE_SPEAKER] could not find connection for callHandle: $callHandle")
                    }
                }

                ACTION_ACTIVE_HANDLE -> {
                    val activeCallHandle = getActiveCallHandle()
                    sendBroadcastCallHandle(applicationContext, activeCallHandle)
                }

                else -> {
                    Log.e(TAG, "onStartCommand: unknown action: ${it.action}")
                    startForegroundThenStopIfIdle()
                }
            }
        } ?: run {
            Log.e(TAG, "onStartCommand: intent is null")
        }
        return START_STICKY
    }
    //endregion

    /**
     * Attach call event listeners to the given connection. This includes responding to call events, call actions and when call has ended.
     * @param connection The connection to attach the listeners to.
     * @param callSid The call SID of the connection.
     */
    private fun <T: TVCallConnection> attachCallEventListeners(connection: T, callSid: String) {

        val onAction: ValueBundleChanged<String> = ValueBundleChanged { event: String?, extra: Bundle? ->
            sendBroadcastEvent(applicationContext, event ?: "", callSid, extra)
        }

        val onEvent: ValueBundleChanged<String> = ValueBundleChanged { event: String?, extra: Bundle? ->
            sendBroadcastEvent(applicationContext, event ?: "", callSid, extra)
            // This is a temporary solution since `isOnCall` returns true when there is an active ConnectionService, regardless of the source app. This also applies to SIM/Telecom calls.
            sendBroadcastCallHandle(applicationContext, extra?.getString(TVBroadcastReceiver.EXTRA_CALL_HANDLE))
        }
        val onDisconnect: CompletionHandler<DisconnectCause> = CompletionHandler {
            ringer.stop()
            if (activeConnections.containsKey(callSid)) {
                activeConnections.remove(callSid)
            }
            stopForegroundService()
            stopSelfSafe()
        }
        val onCallState: CompletionHandler<Call.State> = CompletionHandler { state ->
            when (state) {
                Call.State.CONNECTED -> {
                    ringer.stop()
                    // Swap the ringing full-screen notification for the ordinary ongoing call
                    // notification, now claiming the microphone the connected call is using.
                    startForegroundService(wantMicrophone = true)
                }
                Call.State.DISCONNECTED -> {
                    ringer.stop()
                    if (activeConnections.containsKey(callSid)) {
                        activeConnections.remove(callSid)
                    }
                    stopForegroundService()
                    stopSelfSafe()
                }
                else -> {}
            }
        }

        // Add to local connection cache
        activeConnections[callSid] = connection

        // attach listeners
        connection.setOnCallActionListener(onAction)
        connection.setOnCallEventListener(onEvent)
        connection.setOnCallDisconnected(onDisconnect)
        connection.setOnCallStateListener(onCallState);
    }

    /**
     * Apply the given parameters to the given connection. This resolves the caller display name, if any.
     * @param connection The connection to apply the parameters to.
     * @param params The parameters to apply to the connection.
     */
    private fun <T: TVCallConnection> applyParameters(connection: T, params: TVParameters) {
        val name = if(connection.callDirection == CallDirection.OUTGOING) params.to else params.from
        connection.callerDisplayName = name
    }

    /**
     * Tells any UI showing [callHandle] that there is no longer a call behind it.
     *
     * A missing connection means the invite was cancelled or the call already ended —
     * common, because a caller who gives up mid-ring cancels the invite while the
     * ringing UI is still on screen. [TVIncomingCallActivity] only ever closes on a
     * broadcast, so without this the user is left looking at a call screen that
     * cannot be dismissed: answer does nothing, and hang up disconnects a connection
     * that is already gone.
     */
    private fun broadcastCallGone(callHandle: String) {
        sendBroadcastEvent(applicationContext, TVBroadcastReceiver.ACTION_CALL_ENDED, callHandle)
    }

    private fun sendBroadcastEvent(ctx: Context, event: String, callSid: String?, extras: Bundle? = null) {
        Intent(ctx, TVBroadcastReceiver::class.java).apply {
            action = event
            putExtra(EXTRA_CALL_HANDLE, callSid)
            extras?.let { putExtras(it) }
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(this)
        }
    }

    private fun sendBroadcastCallHandle(ctx: Context, callSid: String?) {
        Log.d(TAG, "sendBroadcastCallHandle: ${if (callSid != null) "On call" else "Not on call"}}")
        Intent(ctx, TVBroadcastReceiver::class.java).apply {
            action = TVBroadcastReceiver.ACTION_ACTIVE_CALL_CHANGED
            putExtra(EXTRA_CALL_HANDLE, callSid)
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(this)
        }
    }

    //region Ongoing call notification (foreground service)
    private fun getOrCreateChannel(): NotificationChannel {
        val id = "${applicationContext.packageName}_calls"
        val name = applicationContext.appName
        val descriptionText = "Active Voice Calls"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(id, name, importance).apply {
            description = descriptionText
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        return channel
    }

    private fun createNotification(): Notification {
        val channel = getOrCreateChannel()

        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT

        val callHandle = getActiveCallHandle()
        val connection = callHandle?.let { getConnection(it) }
        val isSpeakerOn = TVAudioManager.getInstance(applicationContext).isSpeakerOn
        val isMuted = connection?.isMuted ?: false
        val callerDisplayName = connection?.callerDisplayName ?: "Voice call"

        fun servicePendingIntent(requestCode: Int, action: String, extra: Pair<String, Boolean>? = null): PendingIntent {
            val serviceIntent = Intent(applicationContext, TVConnectionService::class.java).apply {
                this.action = action
                callHandle?.let { putExtra(EXTRA_CALL_HANDLE, it) }
                extra?.let { (key, value) -> putExtra(key, value) }
            }
            return PendingIntent.getService(applicationContext, requestCode, serviceIntent, flag)
        }

        val hangupIntent = servicePendingIntent(300, ACTION_HANGUP)
        val speakerIntent = servicePendingIntent(301, ACTION_TOGGLE_SPEAKER, EXTRA_SPEAKER_STATE to !isSpeakerOn)
        val muteIntent = servicePendingIntent(302, ACTION_TOGGLE_MUTE, EXTRA_MUTE_STATE to !isMuted)

        val speakerAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_microphone),
            if (isSpeakerOn) "Speaker off" else "Speaker on",
            speakerIntent
        ).build()
        val muteAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_microphone),
            if (isMuted) "Unmute" else "Mute",
            muteIntent
        ).build()

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = Person.Builder().setName(callerDisplayName).build()
            Notification.Builder(this, channel.id)
                .setStyle(Notification.CallStyle.forOngoingCall(person, hangupIntent))
                .addAction(speakerAction)
                .addAction(muteAction)
                .setSmallIcon(R.drawable.ic_microphone)
        } else {
            val hangupAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_microphone),
                "Hang up",
                hangupIntent
            ).build()
            Notification.Builder(this, channel.id)
                .setContentTitle(callerDisplayName)
                .setContentText("Ongoing call")
                .setSmallIcon(R.drawable.ic_microphone)
                .addAction(speakerAction)
                .addAction(muteAction)
                .addAction(hangupAction)
        }

        return builder.apply {
            setOngoing(true)
            setCategory(Notification.CATEGORY_CALL)
            callScreenPendingIntent(callHandle, connection, flag)?.let { setContentIntent(it) }
        }.build()
    }

    /**
     * Where tapping the ongoing-call notification takes the rep.
     *
     * For an incoming call this returns to [TVIncomingCallActivity] — the way back for a
     * rep who left a live call to check something, alongside the task's own entry in
     * recents. Outgoing calls are presented by the Flutter in-call screen inside the
     * app's own task, so those return to the launcher rather than stacking a second
     * call UI over it.
     *
     * Null with no active call, leaving the notification without a tap target instead
     * of one that resolves to nothing.
     */
    private fun callScreenPendingIntent(callHandle: String?, connection: TVCallConnection?, flag: Int): PendingIntent? {
        if (callHandle == null) {
            return null
        }
        val intent = if (connection != null && connection.callDirection == CallDirection.INCOMING) {
            Intent(applicationContext, TVIncomingCallActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(EXTRA_CALL_HANDLE, callHandle)
                putExtra(
                    TVIncomingCallActivity.EXTRA_CALLER_DISPLAY_NAME,
                    connection.callerDisplayName ?: connection.getCallParameters()?.from
                )
                putExtra(TVIncomingCallActivity.EXTRA_IN_CALL, true)
            }
        } else {
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        } ?: return null
        return PendingIntent.getActivity(applicationContext, 303, intent, flag)
    }

    private fun cancelNotification() {
        val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_CALL)
    }

    private fun launchInCallActivity(callHandle: String, callerDisplayName: String?) {
        val launchIntent = Intent(applicationContext, TVIncomingCallActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(EXTRA_CALL_HANDLE, callHandle)
            putExtra(TVIncomingCallActivity.EXTRA_CALLER_DISPLAY_NAME, callerDisplayName)
            putExtra(TVIncomingCallActivity.EXTRA_IN_CALL, true)
        }
        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.w(TAG, "launchInCallActivity: could not launch in-call activity: $e")
        }
    }

    /**
     * Goes foreground with [notification], always claiming `phoneCall` and additionally
     * `microphone` when [wantMicrophone].
     *
     * `microphone` is a while-in-use foreground-service type: from Android 14 it cannot be started
     * while the app is in the background. Incoming calls arrive as an FCM push with the app
     * backgrounded, so claiming it there throws — and because the notification is posted *by*
     * startForeground, the phone never rings at all. `phoneCall` carries no such restriction (it
     * needs only MANAGE_OWN_CALLS or ROLE_DIALER, and this plugin declares the former), so it is
     * always claimed and `microphone` is added only once the call is connected and the mic is
     * genuinely in use.
     *
     * The microphone claim can still be refused when a call connects while the app is in the
     * background, so that case falls back to `phoneCall` alone: being foreground without the mic
     * type beats failing to go foreground at all, which the system punishes with
     * ForegroundServiceDidNotStartInTimeException.
     */
    private fun startForegroundWithCallTypes(notification: Notification, wantMicrophone: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID_CALL, notification)
            return
        }
        if (!wantMicrophone) {
            startForeground(NOTIFICATION_ID_CALL, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            return
        }
        try {
            startForeground(
                NOTIFICATION_ID_CALL,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            Log.e(TAG, "[VoiceConnectionService] microphone service type refused, continuing as phoneCall only: $e")
            startForeground(NOTIFICATION_ID_CALL, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        }
    }

    /// Source: https://github.com/react-native-webrtc/react-native-callkeep/blob/master/android/src/main/java/io/wazo/callkeep/VoiceConnectionService.java#L295
    private fun startForegroundService(wantMicrophone: Boolean) {
        val notification = createNotification()
        Log.d(TAG, "[VoiceConnectionService] Starting foreground service")
        try {
            startForegroundWithCallTypes(notification, wantMicrophone)
        } catch (e: Exception) {
            // Error, not warn: this is the failure that silently stops the phone ringing, and it
            // went unnoticed in production precisely because it was only logged at warn.
            Log.e(TAG, "[VoiceConnectionService] Can't start foreground service : $e")
        }
    }

    /// Source: https://github.com/react-native-webrtc/react-native-callkeep/blob/master/android/src/main/java/io/wazo/callkeep/VoiceConnectionService.java#L352C5-L377C6
    private fun stopForegroundService() {
        Log.d(TAG, "[VoiceConnectionService] stopForegroundService")
        try {
            stopForeground(NOTIFICATION_ID_CALL)
            cancelNotification()
        } catch (e: java.lang.Exception) {
            Log.w(TAG, "[VoiceConnectionService] can't stop foreground service :$e")
        }
    }
    //endregion

    //region Incoming call full-screen-intent notification
    /**
     * @param alerted whether [TVRinger] actually alerted the device. When it did
     * not, the notification has to make the sound itself, or the call arrives
     * completely silent. A channel's sound is fixed once created, so the audible
     * fallback is a second channel rather than a mutation of the first.
     */
    private fun getOrCreateIncomingCallChannel(alerted: Boolean): NotificationChannel {
        val suffix = if (alerted) "" else "_fallback"
        val id = "${applicationContext.packageName}_incoming_calls$suffix"
        val name = if (alerted) "Incoming calls" else "Incoming calls (backup ringer)"
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming call notifications"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            if (alerted) {
                // TVRinger owns the ringtone + vibration (looping, ringer-mode aware),
                // so the channel itself stays silent to avoid a one-shot double-buzz.
                enableVibration(false)
                setSound(null, null)
            } else {
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        }
        val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        return channel
    }

    private fun createIncomingCallNotification(callSid: String, callerDisplayName: String, alerted: Boolean): Notification {
        val channel = getOrCreateIncomingCallChannel(alerted)
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT

        val fullScreenIntent = Intent(applicationContext, TVIncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CALL_HANDLE, callSid)
            putExtra(TVIncomingCallActivity.EXTRA_CALLER_DISPLAY_NAME, callerDisplayName)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(applicationContext, callSid.hashCode(), fullScreenIntent, flag)

        val answerIntent = Intent(applicationContext, TVConnectionService::class.java).apply {
            action = ACTION_ANSWER
            putExtra(EXTRA_CALL_HANDLE, callSid)
        }
        val answerPendingIntent = PendingIntent.getService(applicationContext, callSid.hashCode() + 1, answerIntent, flag)

        val declineIntent = Intent(applicationContext, TVConnectionService::class.java).apply {
            action = ACTION_REJECT
            putExtra(EXTRA_CALL_HANDLE, callSid)
        }
        val declinePendingIntent = PendingIntent.getService(applicationContext, callSid.hashCode() + 2, declineIntent, flag)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = Person.Builder().setName(callerDisplayName).build()
            Notification.Builder(this, channel.id)
                .setStyle(Notification.CallStyle.forIncomingCall(person, declinePendingIntent, answerPendingIntent))
                .setSmallIcon(R.drawable.ic_microphone)
        } else {
            Notification.Builder(this, channel.id)
                .setContentTitle(callerDisplayName)
                .setContentText("Incoming call")
                .setSmallIcon(R.drawable.ic_microphone)
                .setPriority(Notification.PRIORITY_MAX)
                .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this@TVConnectionService, R.drawable.ic_microphone), "Decline", declinePendingIntent).build())
                .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this@TVConnectionService, R.drawable.ic_microphone), "Accept", answerPendingIntent).build())
        }

        return builder.apply {
            setCategory(Notification.CATEGORY_CALL)
            setFullScreenIntent(fullScreenPendingIntent, true)
            setContentIntent(fullScreenPendingIntent)
            setOngoing(true)
            setAutoCancel(false)
        }.build()
    }

    /**
     * Warns when the OS will not honour our full-screen intent.
     *
     * From Android 14 `USE_FULL_SCREEN_INTENT` is only granted by default to apps the
     * system classifies as calling or alarm apps; otherwise the user must enable it.
     * When it is denied, `setFullScreenIntent` silently degrades to a heads-up
     * notification: the ringing Activity never launches and the phone does not present
     * a call, with nothing in the logs to say why. Logging it turns a silent,
     * unexplainable "my phone never rang" into something diagnosable.
     */
    private fun warnIfFullScreenIntentUnavailable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.canUseFullScreenIntent()) {
            Log.e(
                TAG,
                "[VoiceConnectionService] USE_FULL_SCREEN_INTENT is not granted — the incoming call " +
                    "will only appear as a heads-up notification and the ringing screen will not launch"
            )
        }
    }

    private fun startIncomingCallForeground(callSid: String, callerDisplayName: String, alerted: Boolean = true) {
        val notification = createIncomingCallNotification(callSid, callerDisplayName, alerted)
        if (!alerted) {
            Log.w(TAG, "[VoiceConnectionService] Ringer produced no sound or vibration — falling back to the notification channel's own ringtone")
        }
        warnIfFullScreenIntentUnavailable()
        Log.d(TAG, "[VoiceConnectionService] Starting incoming call foreground service")
        try {
            // Ringing needs no microphone, and this runs from an FCM push with the app
            // backgrounded — claiming `microphone` here is what stopped Android 14+ devices
            // ringing at all, since this notification is posted by startForeground.
            startForegroundWithCallTypes(notification, wantMicrophone = false)
        } catch (e: Exception) {
            Log.e(TAG, "[VoiceConnectionService] Can't start incoming call foreground service : $e")
        }
    }
    //endregion

    //region Missed call notification
    private fun getOrCreateMissedCallChannel(): NotificationChannel {
        val id = "${applicationContext.packageName}_missed_calls"
        val channel = NotificationChannel(id, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT)
        val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        return channel
    }

    private fun postMissedCallNotification(callHandle: String, displayName: String, leadId: String?) {
        val channel = getOrCreateMissedCallChannel()
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT

        // infinit://deeplink/leads/<id> is kept in sync with the dealer app's deep-link scheme
        val contentIntent = if (leadId != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse("infinit://deeplink/leads/$leadId")).setPackage(applicationContext.packageName)
        } else {
            packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        }
        val pendingIntent = contentIntent?.let { PendingIntent.getActivity(applicationContext, callHandle.hashCode(), it, flag) }

        val notification = Notification.Builder(this, channel.id)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(getString(R.string.call_missed_title))
            .setContentText(displayName)
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .build()

        val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(callHandle.hashCode(), notification)
    }
    //endregion
}
