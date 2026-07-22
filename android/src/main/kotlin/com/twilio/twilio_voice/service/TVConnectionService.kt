package com.twilio.twilio_voice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telecom.DisconnectCause
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.twilio.twilio_voice.R
import com.twilio.twilio_voice.activities.TVIncomingCallActivity
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

        val SERVICE_TYPE_MICROPHONE: Int = 100

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


    override fun onBind(intent: Intent?): IBinder? {
        return null
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
        startForegroundService()
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
                    // Load CancelledCallInvite class loader
                    // See: https://github.com/twilio/voice-quickstart-android/issues/561#issuecomment-1678613170
                    it.setExtrasClassLoader(CallInvite::class.java.classLoader)
                    val cancelledCallInvite = it.getParcelableExtraSafe<CancelledCallInvite>(EXTRA_CANCEL_CALL_INVITE) ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_CANCEL_CALL_INVITE is missing parcelable EXTRA_CANCEL_CALL_INVITE")
                        startForegroundThenStopIfIdle()
                        return@let
                    }

                    val callHandle = cancelledCallInvite.callSid
                    getConnection(callHandle)?.onAbort() ?: run {
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

                    // Present a full-screen-intent notification + native ringing Activity instead of the system's Telecom incoming call UI
                    startIncomingCallForeground(callInvite.callSid, connection.callerDisplayName ?: callParams.from)
                }

                ACTION_ANSWER -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getIncomingCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_ANSWER is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }

                    val connection = getConnection(callHandle) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_ANSWER] could not find connection for callHandle: $callHandle")
                        return@let
                    }

                    if(connection is TVCallInviteConnection) {
                        connection.acceptInvite()
                    } else {
                        Log.e(TAG, "onStartCommand: [ACTION_ANSWER] could not find connection for callHandle: $callHandle")
                    }
                }

                ACTION_REJECT -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getIncomingCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_REJECT is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }

                    val connection = getConnection(callHandle) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_REJECT] could not find connection for callHandle: $callHandle")
                        return@let
                    }

                    if(connection is TVCallInviteConnection) {
                        connection.rejectInvite()
                    } else {
                        Log.e(TAG, "onStartCommand: [ACTION_REJECT] could not find connection for callHandle: $callHandle")
                    }
                }

                ACTION_HANGUP -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getActiveCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_HANGUP is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }

                    getConnection(callHandle)?.disconnect() ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_HANGUP] could not find connection for callHandle: $callHandle")
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

                    val connectOptions = ConnectOptions.Builder(token)
                        .params(params)
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

                    startForegroundService()
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
            if (activeConnections.containsKey(callSid)) {
                activeConnections.remove(callSid)
            }
            stopForegroundService()
            stopSelfSafe()
        }
        val onCallState: CompletionHandler<Call.State> = CompletionHandler { state ->
            when (state) {
                Call.State.CONNECTED -> {
                    // Swap the ringing full-screen notification for the ordinary ongoing call notification
                    startForegroundService()
                }
                Call.State.DISCONNECTED -> {
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
        val importance = NotificationManager.IMPORTANCE_NONE
        val channel = NotificationChannel(id, name, importance).apply {
            description = descriptionText
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        return channel
    }

    private fun createNotification(): Notification {
        val channel = getOrCreateChannel()

        val intent = Intent(applicationContext, TVConnectionService::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent: PendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, flag);

        return Notification.Builder(this, channel.id).apply {
            setOngoing(true)
            setContentTitle("Voice Calls")
            setCategory(Notification.CATEGORY_SERVICE)
            setContentIntent(pendingIntent)
            setSmallIcon(R.drawable.ic_microphone)
        }.build()
    }

    private fun cancelNotification() {
        val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(SERVICE_TYPE_MICROPHONE)
    }

    /// Source: https://github.com/react-native-webrtc/react-native-callkeep/blob/master/android/src/main/java/io/wazo/callkeep/VoiceConnectionService.java#L295
    private fun startForegroundService() {
        val notification = createNotification()
        Log.d(TAG, "[VoiceConnectionService] Starting foreground service")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // FOREGROUND_SERVICE_TYPE_PHONE_CALL requires a self-managed ConnectionService, which the FSI rewrite removed; microphone is what the call actually uses.
                startForeground(SERVICE_TYPE_MICROPHONE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(SERVICE_TYPE_MICROPHONE, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[VoiceConnectionService] Can't start foreground service : $e")
        }
    }

    /// Source: https://github.com/react-native-webrtc/react-native-callkeep/blob/master/android/src/main/java/io/wazo/callkeep/VoiceConnectionService.java#L352C5-L377C6
    private fun stopForegroundService() {
        Log.d(TAG, "[VoiceConnectionService] stopForegroundService")
        try {
            stopForeground(SERVICE_TYPE_MICROPHONE)
            cancelNotification()
        } catch (e: java.lang.Exception) {
            Log.w(TAG, "[VoiceConnectionService] can't stop foreground service :$e")
        }
    }
    //endregion

    //region Incoming call full-screen-intent notification
    private fun getOrCreateIncomingCallChannel(): NotificationChannel {
        val id = "${applicationContext.packageName}_incoming_calls"
        val channel = NotificationChannel(id, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming call notifications"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        return channel
    }

    private fun createIncomingCallNotification(callSid: String, callerDisplayName: String): Notification {
        val channel = getOrCreateIncomingCallChannel()
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

    private fun startIncomingCallForeground(callSid: String, callerDisplayName: String) {
        val notification = createIncomingCallNotification(callSid, callerDisplayName)
        Log.d(TAG, "[VoiceConnectionService] Starting incoming call foreground service")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // FOREGROUND_SERVICE_TYPE_PHONE_CALL requires a self-managed ConnectionService, which the FSI rewrite removed; microphone is what the call actually uses.
                startForeground(SERVICE_TYPE_MICROPHONE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(SERVICE_TYPE_MICROPHONE, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[VoiceConnectionService] Can't start incoming call foreground service : $e")
        }
    }
    //endregion
}
