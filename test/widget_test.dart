import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mass_mate/click_wheel.dart';
import 'package:mass_mate/main.dart';

const MethodChannel _hapticsChannel = MethodChannel('mass_mate/haptics');

void main() {
  testWidgets('renders click wheel player shell on desktop', (tester) async {
    await _pumpAppAtSize(tester, const Size(800, 600));

    expect(tester.takeException(), isNull);
    expect(find.text('Mass Mate'), findsOneWidget);
    expect(find.text('Chapter 12: Night Drive'), findsOneWidget);
    expect(find.byType(ClickWheel), findsOneWidget);
  });

  testWidgets('renders click wheel player shell on Android phone size',
      (tester) async {
    await _pumpAppAtSize(tester, const Size(390, 844));

    expect(tester.takeException(), isNull);
    expect(find.text('Mass Mate'), findsOneWidget);
    expect(find.text('Chapter 12: Night Drive'), findsOneWidget);
    expect(find.byType(ClickWheel), findsOneWidget);
    expect(find.text('MENU'), findsOneWidget);

    final wheelTop = tester.getTopLeft(find.byType(ClickWheel)).dy;
    final wheelCenter = tester.getCenter(find.byType(ClickWheel)).dy;
    final cardTop = tester.getTopLeft(find.text('Chapter 12: Night Drive')).dy;
    expect(wheelTop, greaterThan(cardTop));
    expect(wheelCenter, greaterThan(844 * 0.65));

    await tester.tap(find.text('MENU'));
    await tester.pump();

    expect(find.text('Volume mode'), findsAtLeastNWidgets(1));
  });

  testWidgets('keeps wheel below player on tall Android screenshot size',
      (tester) async {
    await _pumpAppAtSize(tester, const Size(904, 2012));

    expect(tester.takeException(), isNull);
    expect(find.byType(ClickWheel), findsOneWidget);
    expect(find.text('Seek'), findsNothing);
    expect(find.text('Volume'), findsNothing);
    expect(find.text('Queue'), findsNothing);

    final wheelTop = tester.getTopLeft(find.byType(ClickWheel)).dy;
    final cardTop = tester.getTopLeft(find.text('Chapter 12: Night Drive')).dy;
    expect(wheelTop, greaterThan(cardTop));
  });

  testWidgets('scrolling the wheel emits stepped haptic feedback',
      (tester) async {
    final platformCalls = <MethodCall>[];
    _capturePlatformCalls(platformCalls);

    await _pumpAppAtSize(tester, const Size(390, 844));

    await _dragWheelClockwise(tester);
    await tester.pump();

    expect(
      _hapticCalls(platformCalls, 'mediumImpact'),
      hasLength(9),
    );
  });

  testWidgets('throws when pan update arrives before drag start',
      (tester) async {
    await _pumpAppAtSize(tester, const Size(390, 844));

    final detector = _wheelPanDetector(tester);

    expect(
      () => detector.onPanUpdate!(
        DragUpdateDetails(
          globalPosition: Offset.zero,
          localPosition: Offset.zero,
        ),
      ),
      throwsA(
        isA<StateError>().having(
          (error) => error.message,
          'message',
          contains('drag angle must be initialized'),
        ),
      ),
    );
  });

  testWidgets('crossing the left angle seam does not create a jump',
      (tester) async {
    final platformCalls = <MethodCall>[];
    final boundaryBuzzes = <MethodCall>[];
    _capturePlatformCalls(platformCalls);
    _captureBoundaryBuzzes(boundaryBuzzes);

    await _pumpAppAtSize(tester, const Size(390, 844));

    final center = tester.getCenter(find.byType(ClickWheel));
    final gesture = await tester.startGesture(center + const Offset(-130, -1));
    await gesture.moveTo(center + const Offset(-130, 1));
    await gesture.up();
    await tester.pump();

    expect(find.textContaining(RegExp(r'18:4[12]')), findsOneWidget);
    expect(_hapticCalls(platformCalls, 'mediumImpact').length,
        lessThanOrEqualTo(1));
    expect(_boundaryBuzzCalls(boundaryBuzzes), isEmpty);
  });

  testWidgets('crossing the left angle seam in reverse does not create a jump',
      (tester) async {
    final platformCalls = <MethodCall>[];
    final boundaryBuzzes = <MethodCall>[];
    _capturePlatformCalls(platformCalls);
    _captureBoundaryBuzzes(boundaryBuzzes);

    await _pumpAppAtSize(tester, const Size(390, 844));

    final center = tester.getCenter(find.byType(ClickWheel));
    final gesture = await tester.startGesture(center + const Offset(-130, 1));
    await gesture.moveTo(center + const Offset(-130, -1));
    await gesture.up();
    await tester.pump();

    expect(find.textContaining(RegExp(r'18:4[12]')), findsOneWidget);
    expect(_hapticCalls(platformCalls, 'mediumImpact').length,
        lessThanOrEqualTo(1));
    expect(_boundaryBuzzCalls(boundaryBuzzes), isEmpty);
  });

  testWidgets('mode cycling cancels seek preview without committing',
      (tester) async {
    await _pumpAppAtSize(tester, const Size(390, 844));

    await tester.tap(find.byIcon(Icons.skip_next));
    await tester.pump();

    expect(find.text('Seek preview'), findsOneWidget);
    expect(find.textContaining('committed 18:42'), findsOneWidget);

    await tester.tap(find.text('MENU'));
    await tester.pump();
    await tester.tap(find.text('MENU'));
    await tester.pump();
    await tester.tap(find.text('MENU'));
    await tester.pump();

    expect(find.text('Seek mode'), findsAtLeastNWidgets(1));
    expect(find.text('Seek preview'), findsNothing);
    expect(find.text('18:42'), findsOneWidget);
  });

  testWidgets('seek wheel movement previews locally until release commits',
      (tester) async {
    final platformCalls = <MethodCall>[];
    _capturePlatformCalls(platformCalls);

    await _pumpAppAtSize(tester, const Size(390, 844));

    final center = tester.getCenter(find.byType(ClickWheel));
    final gesture = await tester.startGesture(center + const Offset(92, -92));
    await gesture.moveTo(center + const Offset(130, 0));
    await tester.pump();

    expect(find.text('Seek preview'), findsOneWidget);
    expect(find.text('23:12'), findsOneWidget);
    expect(find.textContaining('committed 18:42'), findsOneWidget);
    expect(_hapticCalls(platformCalls, 'selectionClick'), isEmpty);

    await gesture.up();
    await tester.pump();

    expect(find.text('Seek preview'), findsNothing);
    expect(find.text('23:12'), findsOneWidget);
    expect(find.text('18:42'), findsNothing);
    expect(_hapticCalls(platformCalls, 'selectionClick'), hasLength(1));
  });

  testWidgets('center button commits active seek preview without cycling modes',
      (tester) async {
    final platformCalls = <MethodCall>[];
    _capturePlatformCalls(platformCalls);

    await _pumpAppAtSize(tester, const Size(390, 844));

    await tester.tap(find.byIcon(Icons.skip_next));
    await tester.pump();

    expect(find.text('Seek preview'), findsOneWidget);
    expect(find.text('24:27'), findsOneWidget);
    expect(find.textContaining('committed 18:42'), findsOneWidget);
    expect(_hapticCalls(platformCalls, 'selectionClick'), isEmpty);

    await tester.tap(find.byType(FilledButton));
    await tester.pump();

    expect(find.text('Seek preview'), findsNothing);
    expect(find.text('Seek mode'), findsAtLeastNWidgets(1));
    expect(find.text('24:27'), findsOneWidget);
    expect(find.text('18:42'), findsNothing);
    expect(_hapticCalls(platformCalls, 'selectionClick'), hasLength(1));
  });

  testWidgets('canceling a seek drag cancels preview without committing',
      (tester) async {
    await _pumpAppAtSize(tester, const Size(390, 844));

    await tester.tap(find.byIcon(Icons.skip_next));
    await tester.pump();

    expect(find.text('Seek preview'), findsOneWidget);
    expect(find.textContaining('committed 18:42'), findsOneWidget);

    _wheelPanDetector(tester).onPanCancel!();
    await tester.pump();

    expect(find.text('Seek preview'), findsNothing);
    expect(find.text('18:42'), findsOneWidget);
  });

  testWidgets('play pause does not commit active seek preview', (tester) async {
    final platformCalls = <MethodCall>[];
    _capturePlatformCalls(platformCalls);

    await _pumpAppAtSize(tester, const Size(390, 844));

    await tester.tap(find.byIcon(Icons.skip_next));
    await tester.pump();

    expect(find.text('Seek preview'), findsOneWidget);
    expect(find.text('24:27'), findsOneWidget);
    expect(find.textContaining('committed 18:42'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.pause));
    await tester.pump();

    expect(find.text('Seek preview'), findsOneWidget);
    expect(find.text('24:27'), findsOneWidget);
    expect(find.textContaining('committed 18:42'), findsOneWidget);
    expect(_hapticCalls(platformCalls, 'selectionClick'), isEmpty);
  });

  testWidgets('volume max endpoint emits a double hard buzz', (tester) async {
    await _expectEndpointBuzz(
      tester,
      selectMode: _selectVolumeMode,
      drag: _dragWheelClockwise,
      dragCount: 9,
      expectedText: 'Volume 100%',
    );
  });

  testWidgets('volume min endpoint emits a double hard buzz', (tester) async {
    await _expectEndpointBuzz(
      tester,
      selectMode: _selectVolumeMode,
      drag: _dragWheelCounterClockwise,
      dragCount: 16,
      expectedText: 'Volume 0%',
    );
  });

  testWidgets('seek endpoint emits a double hard buzz', (tester) async {
    await _expectEndpointBuzz(
      tester,
      drag: _dragWheelCounterClockwise,
      dragCount: 60,
      expectedText: '00:00',
    );
    expect(find.text('00:00'), findsOneWidget);
    expect(find.text('-54:18'), findsOneWidget);
  });

  testWidgets('seek max endpoint emits a double hard buzz', (tester) async {
    await _expectEndpointBuzz(
      tester,
      drag: _dragWheelClockwise,
      dragCount: 115,
      expectedText: '-00:00',
    );
  });

  testWidgets('queue max endpoint emits a double hard buzz', (tester) async {
    await _expectEndpointBuzz(
      tester,
      selectMode: _selectQueueMode,
      drag: _dragWheelClockwise,
      dragCount: 7,
      expectedText: 'Queue item 24 of 24',
    );
  });

  testWidgets('queue min endpoint emits a double hard buzz', (tester) async {
    await _expectEndpointBuzz(
      tester,
      selectMode: _selectQueueMode,
      drag: _dragWheelCounterClockwise,
      dragCount: 2,
      expectedText: 'Queue item 1 of 24',
    );
  });

  testWidgets('endpoint buzz resets after moving back inside range',
      (tester) async {
    final boundaryBuzzes = <MethodCall>[];
    _captureBoundaryBuzzes(boundaryBuzzes);

    await _pumpAppAtSize(tester, const Size(390, 844));
    await _selectQueueMode(tester);

    for (var index = 0; index < 2; index += 1) {
      await _dragWheelCounterClockwise(tester);
    }
    await tester.pump(const Duration(milliseconds: 220));

    expect(find.textContaining('Queue item 1 of 24'), findsOneWidget);
    expect(_boundaryBuzzCalls(boundaryBuzzes), hasLength(1));

    await _dragWheelClockwise(tester);
    await tester.pump();
    expect(find.textContaining('Queue item 4 of 24'), findsOneWidget);

    for (var index = 0; index < 2; index += 1) {
      await _dragWheelCounterClockwise(tester);
    }
    await tester.pump(const Duration(milliseconds: 220));

    expect(find.textContaining('Queue item 1 of 24'), findsOneWidget);
    expect(_boundaryBuzzCalls(boundaryBuzzes), hasLength(2));
  });

  testWidgets('scrolling in queue mode changes the queue item', (tester) async {
    await _pumpAppAtSize(tester, const Size(390, 844));
    await _selectQueueMode(tester);

    expect(find.text('Queue mode'), findsAtLeastNWidgets(1));
    expect(find.textContaining('Queue item 3 of 24'), findsOneWidget);

    await _dragWheelClockwise(tester);
    await tester.pump();

    expect(find.textContaining('Queue item 6 of 24'), findsOneWidget);
  });
}

Iterable<MethodCall> _hapticCalls(List<MethodCall> platformCalls, String type) {
  return platformCalls.where(
    (call) =>
        call.method == 'HapticFeedback.vibrate' &&
        call.arguments == 'HapticFeedbackType.$type',
  );
}

Iterable<MethodCall> _boundaryBuzzCalls(List<MethodCall> hapticCalls) {
  return hapticCalls.where((call) => call.method == 'boundaryBuzz');
}

GestureDetector _wheelPanDetector(WidgetTester tester) {
  final detectors = tester.widgetList<GestureDetector>(
    find.descendant(
      of: find.byType(ClickWheel),
      matching: find.byType(GestureDetector),
    ),
  );
  return detectors.singleWhere(
    (detector) => detector.onPanUpdate != null,
  );
}

Future<void> _expectEndpointBuzz(
  WidgetTester tester, {
  required Future<void> Function(WidgetTester) drag,
  required int dragCount,
  required String expectedText,
  Future<void> Function(WidgetTester)? selectMode,
}) async {
  final boundaryBuzzes = <MethodCall>[];
  _captureBoundaryBuzzes(boundaryBuzzes);

  await _pumpAppAtSize(tester, const Size(390, 844));
  await selectMode?.call(tester);

  for (var index = 0; index < dragCount; index += 1) {
    await drag(tester);
  }
  await tester.pump(const Duration(milliseconds: 220));

  expect(find.textContaining(expectedText), findsOneWidget);
  expect(_boundaryBuzzCalls(boundaryBuzzes), hasLength(1));
}

void _captureBoundaryBuzzes(List<MethodCall> hapticCalls) {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(_hapticsChannel, (call) async {
    hapticCalls.add(call);
    return null;
  });
  addTearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_hapticsChannel, null),
  );
}

void _capturePlatformCalls(List<MethodCall> platformCalls) {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(SystemChannels.platform, (call) async {
    platformCalls.add(call);
    return null;
  });
  addTearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null),
  );
}

Future<void> _selectVolumeMode(WidgetTester tester) async {
  await tester.tap(find.text('MENU'));
  await tester.pump();
}

Future<void> _selectQueueMode(WidgetTester tester) async {
  await _selectVolumeMode(tester);
  await tester.tap(find.text('MENU'));
  await tester.pump();
}

Future<void> _dragWheelClockwise(WidgetTester tester) async {
  final center = tester.getCenter(find.byType(ClickWheel));
  final gesture = await tester.startGesture(center + const Offset(92, -92));
  await gesture.moveTo(center + const Offset(130, 0));
  await gesture.moveTo(center + const Offset(92, 92));
  await gesture.up();
}

Future<void> _dragWheelCounterClockwise(WidgetTester tester) async {
  final center = tester.getCenter(find.byType(ClickWheel));
  final gesture = await tester.startGesture(center + const Offset(92, 92));
  await gesture.moveTo(center + const Offset(130, 0));
  await gesture.moveTo(center + const Offset(92, -92));
  await gesture.up();
}

Future<void> _pumpAppAtSize(WidgetTester tester, Size size) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  await tester.pumpWidget(const MassMateApp());
}
