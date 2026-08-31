// TODO
// - add twilio parameter interpretation
// - create contact with twi:// from twilio parameters

package com.twilio.twilio_voice.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telecom.DisconnectCause
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.twilio.twilio_voice.audio.TVAudioCodecs
import com.twilio.twilio_voice.audio.TVAudioManager
import com.twilio.twilio_voice.call.TVParameters
import com.twilio.twilio_voice.receivers.TVBroadcastReceiver
import com.twilio.twilio_voice.types.CallDirection
import com.twilio.twilio_voice.types.CallExceptionExtension.toBundle
import com.twilio.twilio_voice.types.CompletionHandler
import com.twilio.twilio_voice.types.TVNativeCallActions
import com.twilio.twilio_voice.types.TVNativeCallEvents
import com.twilio.twilio_voice.types.ValueBundleChanged
import com.twilio.voice.Call
import com.twilio.voice.CallException
import com.twilio.voice.CallInvite


class TVCallInviteConnection(
    ctx: Context,
    ci: CallInvite,
    callParams: TVParameters,
    onEvent: ValueBundleChanged<String>? = null,
    onAction: ValueBundleChanged<String>? = null,
    onDisconnected: CompletionHandler<DisconnectCause>? = null
) : TVCallConnection(ctx, onEvent, onAction, onDisconnected) {

    override val TAG = "VoipCallInviteConnection"
    private val callInvite: CallInvite
    override val callDirection = CallDirection.INCOMING

    init {
        callInvite = ci
        setCallParameters(callParams)
    }

    fun onAnswer() {
        Log.d(TAG, "onAnswer: onAnswer")
        twilioCall = callInvite.accept(context, TVAudioCodecs.acceptOptions(), this)
        onAction?.onChange(TVNativeCallActions.ACTION_ANSWERED, Bundle().apply {
            putParcelable(TVBroadcastReceiver.EXTRA_CALL_INVITE, callInvite)
            putInt(TVBroadcastReceiver.EXTRA_CALL_DIRECTION, callDirection.id)
        })
    }

    fun acceptInvite() {
        Log.d(TAG, "acceptInvite: acceptInvite")
        onAnswer()
    }

    fun rejectInvite() {
        Log.d(TAG, "rejectInvite: rejectInvite")
        onReject()
    }

    fun onReject() {
        Log.d(TAG, "onReject: onReject")
        callInvite.reject(context)
        // if the call was answered, then immediately rejected/ended, we need to disconnect the call also
        twilioCall?.let {
            Log.d(TAG, "onReject: disconnecting call")
            it.disconnect()
        }
        onEvent?.onChange(TVNativeCallEvents.EVENT_DISCONNECTED_LOCAL, null)
        onDisconnected?.withValue(DisconnectCause(DisconnectCause.REJECTED))
        onAction?.onChange(TVNativeCallActions.ACTION_REJECTED, null)
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
    }
}

open class TVCallConnection(
    ctx: Context,
    onEvent: ValueBundleChanged<String>? = null,
    onAction: ValueBundleChanged<String>? = null,
    onDisconnected: CompletionHandler<DisconnectCause>? = null,
) : Call.Listener, TVAudioManager.CallAudioFocusListener {

    companion object {
        const val STATE_NEW = 0
        const val STATE_RINGING = 1
        const val STATE_DIALING = 2
        const val STATE_ACTIVE = 3
        const val STATE_HOLDING = 4
        const val STATE_DISCONNECTED = 5
    }

    open val TAG = "VoipConnection"
    val context: Context
    var twilioCall: Call? = null
    var onDisconnected: CompletionHandler<DisconnectCause>? = null
    var onEvent: ValueBundleChanged<String>? = null
    var onAction: ValueBundleChanged<String>? = null
    private var onCallStateListener: CompletionHandler<Call.State>? = null
    open val callDirection = CallDirection.OUTGOING
    private var callParams: TVParameters? = null

    var state: Int = STATE_NEW
        private set

    var extras: Bundle = Bundle()
    var callerDisplayName: String? = null
    var isMuted: Boolean = false
        private set

    /** Set while the call is held for a cellular call, so only that hold is undone. */
    private var heldForAudioFocus: Boolean = false
    private var mutedBeforeAudioFocusLoss: Boolean = false

    init {
        context = ctx
        this.onDisconnected = onDisconnected
        this.onEvent = onEvent
        this.onAction = onAction
    }

    fun setOnCallDisconnected(handler: CompletionHandler<DisconnectCause>) {
        onDisconnected = handler
    }

    fun setOnCallEventListener(listener: ValueBundleChanged<String>) {
        onEvent = listener
    }

    fun setOnCallActionListener(listener: ValueBundleChanged<String>) {
        onAction = listener
    }

    fun setOnCallStateListener(listener: CompletionHandler<Call.State>) {
        onCallStateListener = listener
    }

    fun setCallParameters(params: TVParameters) {
        callParams = params
    }

    fun getCallParameters(): TVParameters? {
        return callParams
    }

    //region Connection state transitions (formerly android.telecom.Connection)
    fun setInitializing() {
        state = STATE_NEW
    }

    fun setInitialized() {
        state = STATE_DIALING
    }

    fun setRinging() {
        state = STATE_RINGING
    }

    fun setActive() {
        state = STATE_ACTIVE
        TVAudioManager.getInstance(context).onCallActive(this)
        broadcastAudioState()
    }

    fun setOnHold() {
        state = STATE_HOLDING
    }

    fun setDisconnected(cause: DisconnectCause) {
        state = STATE_DISCONNECTED
        TVAudioManager.getInstance(context).onCallEnded()
    }
    //endregion

    //region Call.Listener
    /**
     * The call failed to connect.
     *
     *
     * Calls that fail to connect will result in [Call.Listener.onConnectFailure]
     * and always return a [CallException] providing more information about what failure occurred.
     *
     *
     * @param call          An object model representing a call that failed to connect.
     * @param callException CallException that describes why the connect failed.
     */
    override fun onConnectFailure(call: Call, callException: CallException) {
        Log.d(TAG, "onConnectFailure: onConnectFailure")
        twilioCall = null
        val rejectedErrorCodeList = listOf(
            31600, // Call invite rejected
        )
        val disconnectCauseCode = if (rejectedErrorCodeList.contains(callException.errorCode)) {
            DisconnectCause.REJECTED
        } else {
            DisconnectCause.ERROR
        }
        val disconnectCause = DisconnectCause(disconnectCauseCode, callException.message);
        this@TVCallConnection.setDisconnected(disconnectCause)
        onDisconnected?.withValue(disconnectCause)
        onEvent?.onChange(TVNativeCallEvents.EVENT_CONNECT_FAILURE, callException.toBundle())
        onCallStateListener?.withValue(call.state)
    }

    /**
     * Emitted once before the [Call.Listener.onConnected] callback. If
     * `answerOnBridge` is true, this represents the callee being alerted of a call.
     *
     * The [Call.getSid] is now available.
     *
     * @param call  An object model representing a call.
     */
    override fun onRinging(call: Call) {
        twilioCall = call

        when (callDirection) {
            CallDirection.INCOMING -> {
                setRinging()
            }
            CallDirection.OUTGOING -> {
                setInitialized()
            }
        }
        onCallStateListener?.withValue(call.state)
        onEvent?.onChange(TVNativeCallEvents.EVENT_RINGING, Bundle().apply {
            putString(TVBroadcastReceiver.EXTRA_CALL_HANDLE, callParams?.callSid)
            putString(TVBroadcastReceiver.EXTRA_CALL_FROM, callParams?.fromRaw)
            putString(TVBroadcastReceiver.EXTRA_CALL_TO, callParams?.toRaw)
            putInt(TVBroadcastReceiver.EXTRA_CALL_DIRECTION, callDirection.id)
        })
    }

    override fun onConnected(call: Call) {
        Log.d(TAG, "onConnected: onConnected")
        twilioCall = call
        setActive()
        onCallStateListener?.withValue(call.state)
        onEvent?.onChange(TVNativeCallEvents.EVENT_CONNECTED, Bundle().apply {
            putString(TVBroadcastReceiver.EXTRA_CALL_HANDLE, callParams?.callSid)
            putString(TVBroadcastReceiver.EXTRA_CALL_FROM, callParams?.fromRaw)
            putString(TVBroadcastReceiver.EXTRA_CALL_TO, callParams?.toRaw)
            putInt(TVBroadcastReceiver.EXTRA_CALL_DIRECTION, callDirection.id)
        })
    }

    /**
     * The call starts reconnecting.
     *
     * Reconnect is triggered when a network change is detected and Call is already in [Call.State.CONNECTED] state.
     * If the call is in [Call.State.CONNECTING] or in [Call.State.RINGING] when network
     * change happened the SDK will continue attempting to connect, but a reconnect event will not be raised.
     *
     * @param call           An object model representing a call.
     * @param callException  CallException that describes the reconnect reason. This would have one of the two
     * possible values with error codes 53001 "Signaling connection disconnected" and 53405 "Media connection failed".
     */
    override fun onReconnecting(call: Call, callException: CallException) {
        twilioCall = call
        onCallStateListener?.withValue(call.state)
        onEvent?.onChange(TVNativeCallEvents.EVENT_RECONNECTING, Bundle().apply {
            putString(TVBroadcastReceiver.EXTRA_CALL_HANDLE, callParams?.callSid)
            putString(TVBroadcastReceiver.EXTRA_CALL_FROM, callParams?.fromRaw)
            putString(TVBroadcastReceiver.EXTRA_CALL_TO, callParams?.toRaw)
            putInt(TVBroadcastReceiver.EXTRA_CALL_DIRECTION, callDirection.id)
            putAll(callException.toBundle())
        })
    }

    /**
     * The call is reconnected.
     *
     * @param call An object model representing a call.
     */
    override fun onReconnected(call: Call) {
        twilioCall = call
        setActive()
        onCallStateListener?.withValue(call.state)
        onEvent?.onChange(TVNativeCallEvents.EVENT_RECONNECTED, Bundle().apply {
            putString(TVBroadcastReceiver.EXTRA_CALL_HANDLE, callParams?.callSid)
            putString(TVBroadcastReceiver.EXTRA_CALL_FROM, callParams?.fromRaw)
            putString(TVBroadcastReceiver.EXTRA_CALL_TO, callParams?.toRaw)
            putInt(TVBroadcastReceiver.EXTRA_CALL_DIRECTION, callDirection.id)
        });
    }

    override fun onDisconnected(call: Call, reason: CallException?) {
        // TODO run below only if we did NOT ended call i.e. remove disconnect from other client
        Log.d(TAG, "onDisconnected: onDisconnected, reason: ${reason?.message}.\nException: ${reason.toString()}")
        twilioCall = null
        onCallStateListener?.withValue(call.state)
        onEvent?.onChange(TVNativeCallEvents.EVENT_DISCONNECTED_REMOTE, Bundle().apply {
            reason?.toBundle()?.let { putAll(it) }
        })
        setDisconnected(DisconnectCause(DisconnectCause.REMOTE))
        onDisconnected?.withValue(DisconnectCause(DisconnectCause.REMOTE))
    }
    //endregion

    fun onAbort() {
        Log.i(TAG, "onAbort: onAbort")
        twilioCall?.disconnect()
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        onAction?.onChange(TVNativeCallActions.ACTION_ABORT, null)
        onDisconnected?.withValue(DisconnectCause(DisconnectCause.CANCELED))
    }

    fun onHold() {
        Log.i(TAG, "onHold: onHold")
        twilioCall?.hold(true)
        setOnHold()
        onAction?.onChange(TVNativeCallActions.ACTION_HOLD, null)

        Intent(TVBroadcastReceiver.ACTION_CALL_STATE).apply {
            putExtra(TVBroadcastReceiver.EXTRA_HOLD_STATE, true)
        }.also {
            sendBroadcast(context, it)
        }
    }

    fun onUnhold() {
        Log.i(TAG, "onUnhold: onUnhold")
        twilioCall?.hold(false)
        setActive()
        onAction?.onChange(TVNativeCallActions.ACTION_UNHOLD, null)

        Intent(TVBroadcastReceiver.ACTION_CALL_STATE).apply {
            putExtra(TVBroadcastReceiver.EXTRA_HOLD_STATE, false)
        }.also {
            sendBroadcast(context, it)
        }
    }

    fun toggleHold(newState: Boolean) {
        if (newState) {
            onHold()
        } else {
            onUnhold()
        }
    }

    //region TVAudioManager.CallAudioFocusListener
    /**
     * Steps aside for a call on the handset.
     *
     * Muting as well as holding is deliberate: hold stops the media the caller hears, but
     * the rep is now having a different conversation next to a live microphone and that
     * must not reach the caller under any ordering.
     *
     * A call the user put on hold themselves is left alone — resuming it later would undo
     * a choice we never made.
     */
    override fun onAudioFocusLost() {
        if (heldForAudioFocus || state != STATE_ACTIVE) return
        Log.i(TAG, "onAudioFocusLost: holding the call for a native call")
        heldForAudioFocus = true
        mutedBeforeAudioFocusLoss = isMuted
        toggleMute(true)
        onHold()
    }

    override fun onAudioFocusRegained() {
        if (!heldForAudioFocus) return
        Log.i(TAG, "onAudioFocusRegained: resuming the call")
        heldForAudioFocus = false
        onUnhold()
        toggleMute(mutedBeforeAudioFocusLoss)
    }
    //endregion

    /**
     * Toggle mute state of the call.
     * @param newState: true to mute, false to unmute
     */
    fun toggleMute(newState: Boolean) {
        twilioCall?.let {
            it.mute(newState)
            isMuted = newState
            broadcastAudioState()
        } ?: run {
            Log.e(TAG, "toggleMute: Unable to toggle mute, active call is null")
        }
    }

    /**
     * Toggle audio route of the call.
     * @param newState: true if speaker is enabled, false if speaker is disabled
     */
    fun toggleSpeaker(newState: Boolean) {
        TVAudioManager.getInstance(context).setSpeakerphone(newState)
        broadcastAudioState()
    }

    /**
     * Toggle audio route of the call.
     * @param newState: true if bluetooth is enabled, false if bluetooth is disabled
     */
    fun toggleBluetooth(newState: Boolean) {
        TVAudioManager.getInstance(context).setBluetooth(newState)
        broadcastAudioState()
    }

    private fun broadcastAudioState() {
        val audio = TVAudioManager.getInstance(context)
        Intent(TVBroadcastReceiver.ACTION_AUDIO_STATE).apply {
            putExtra(TVBroadcastReceiver.EXTRA_MUTE_STATE, isMuted)
            putExtra(TVBroadcastReceiver.EXTRA_SPEAKER_STATE, audio.isSpeakerOn)
            putExtra(TVBroadcastReceiver.EXTRA_BLUETOOTH_STATE, audio.isBluetoothOn)
        }.also {
            sendBroadcast(context, it)
        }
    }

    /**
     * Send a broadcast to the [TVBroadcastReceiver] with the given [intent].
     * @param ctx: the context
     * @param intent: the intent to send
     */
    private fun sendBroadcast(ctx: Context, intent: Intent) {
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }

    /**
     * Disconnect the call.
     * If the call is ringing and is an incoming call, reject the call using the [CallInvite.reject].
     * Otherwise, disconnect the call using [Call.disconnect] with [DisconnectCause.LOCAL]
     */
    fun disconnect() {
        Log.d(TAG, "disconnect: disconnect")
        if (this is TVCallInviteConnection && state == STATE_RINGING) {
            rejectInvite()
        } else {
            Log.d(TAG, "onDisconnected: onDisconnected")
            twilioCall?.disconnect()
            onEvent?.onChange(TVNativeCallEvents.EVENT_DISCONNECTED_LOCAL, null)
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            onDisconnected?.withValue(DisconnectCause(DisconnectCause.LOCAL))
            onCallStateListener?.withValue(Call.State.DISCONNECTED)
        }
    }

    /**
     * Send digits to the active call.
     * @param digits: the digits to send
     */
    fun sendDigits(digits: String) {
        twilioCall?.sendDigits(digits) ?: run {
            Log.e(TAG, "sendDigits: Unable to send digits, active call is null")
        }
    }
}
