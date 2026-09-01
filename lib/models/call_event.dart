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
