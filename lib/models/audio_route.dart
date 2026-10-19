/// An output the user can send call audio to (Android).
///
/// [id] is the platform's audio device id, to pass back to `selectAudioRoute`.
/// [type] is the Android `AudioDeviceInfo` type, so callers can pick an icon.
class AudioRoute {
  const AudioRoute({
    required this.id,
    required this.name,
    required this.type,
    required this.isActive,
  });

  final int id;
  final String name;
  final int type;
  final bool isActive;

  // AudioDeviceInfo.TYPE_BUILTIN_EARPIECE / TYPE_BUILTIN_SPEAKER / TYPE_BLUETOOTH_SCO / TYPE_BLE_HEADSET
  static const int typeEarpiece = 1;
  static const int typeSpeaker = 2;
  static const int typeBluetoothSco = 7;
  static const int typeBleHeadset = 26;

  bool get isEarpiece => type == typeEarpiece;
  bool get isSpeaker => type == typeSpeaker;
  bool get isBluetooth => type == typeBluetoothSco || type == typeBleHeadset;

  factory AudioRoute.fromMap(Map<dynamic, dynamic> map) => AudioRoute(
        id: map['id'] as int,
        name: map['name'] as String,
        type: map['type'] as int,
        isActive: map['isActive'] as bool,
      );
}
