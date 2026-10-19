import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:twilio_voice/twilio_voice.dart';

void main() {
  const channel = MethodChannel('twilio_voice/messages');

  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'getAudioRoutes':
          return [
            {'id': 3, 'name': 'Phone', 'type': AudioRoute.typeEarpiece, 'isActive': true},
            {'id': 4, 'name': 'Speaker', 'type': AudioRoute.typeSpeaker, 'isActive': false},
            {'id': 9, 'name': 'Jabra Evolve', 'type': AudioRoute.typeBluetoothSco, 'isActive': false},
          ];
        case 'selectAudioRoute':
          return true;
      }
      return null;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    debugDefaultTargetPlatformOverride = null;
  });

  test('getAudioRoutes maps every route on Android', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;

    final routes = await Call().getAudioRoutes();

    expect(routes.map((r) => r.name), ['Phone', 'Speaker', 'Jabra Evolve']);
    expect(routes.first.isActive, isTrue);
    expect(routes.last.isBluetooth, isTrue);
    expect(routes.first.isEarpiece, isTrue);
  });

  test('selectAudioRoute sends the route id on Android', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;

    final applied = await Call().selectAudioRoute(9);

    expect(applied, isTrue);
    expect(calls.single.method, 'selectAudioRoute');
    expect(calls.single.arguments, {'routeId': 9});
  });

  test('iOS never asks the platform', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;

    expect(await Call().getAudioRoutes(), isEmpty);
    expect(await Call().selectAudioRoute(9), isFalse);
    expect(calls, isEmpty);
  });
}
