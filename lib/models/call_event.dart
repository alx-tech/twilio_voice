enum CallEvent {
  incoming,
  ringing,
  connected,
  reconnected,
  reconnecting,
  callEnded,
  unhold,
  hold,
  unmute,
  mute,
  speakerOn,
  speakerOff,
  bluetoothOn,
  bluetoothOff,

  /// The outputs the call can use changed (Android): a device connected or
  /// disconnected, or a different one was picked. Re-read `getAudioRoutes`.
  audioRoutesChanged,

  /// Android: the system is silencing the call's microphone, so the caller hears nothing.
  /// Happens while the app is in the background without microphone access; opening the
  /// call screen gives it back.
  microphoneSilenced,

  /// Android: the call's microphone is live again after [microphoneSilenced].
  microphoneRestored,
  log,
  permission,
  declined,
  answer,
  missedCall,

  /// An incoming invite the app rejected on the rep's behalf, because the handset
  /// was already on a call or already ringing. Distinct from [declined], which is
  /// the rep choosing to reject.
  autoDeclined,
  returningCall,
}
