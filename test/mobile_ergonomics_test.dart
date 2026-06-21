import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mass_mate/click_wheel.dart';
import 'package:mass_mate/main.dart';

void main() {
  testWidgets('compact phone keeps wheel reachable and uncrowded',
      (tester) async {
    await _pumpAppAtSize(tester, const Size(360, 640));

    final wheelSize = tester.getSize(find.byType(ClickWheel));
    final wheelCenter = tester.getCenter(find.byType(ClickWheel));
    final wheelTop = tester.getTopLeft(find.byType(ClickWheel)).dy;
    final cardBottom = tester.getBottomLeft(find.byType(Card)).dy;

    expect(wheelSize.width, inInclusiveRange(260, 320));
    expect(wheelSize.height, wheelSize.width);
    expect(wheelCenter.dy, greaterThan(640 * 0.68));
    expect(wheelTop - cardBottom, greaterThanOrEqualTo(8));
  });

  testWidgets('normal phone grows wheel toward available width',
      (tester) async {
    await _pumpAppAtSize(tester, const Size(390, 844));

    final wheelSize = tester.getSize(find.byType(ClickWheel));
    final wheelCenter = tester.getCenter(find.byType(ClickWheel));

    expect(wheelSize.width, greaterThan(320));
    expect(wheelSize.width, lessThanOrEqualTo(360));
    expect(wheelCenter.dy, greaterThan(844 * 0.65));
  });

  testWidgets('tall phone caps wheel while keeping thumb placement',
      (tester) async {
    await _pumpAppAtSize(tester, const Size(904, 2012));

    final wheelSize = tester.getSize(find.byType(ClickWheel));
    final wheelCenter = tester.getCenter(find.byType(ClickWheel));

    expect(wheelSize.width, 360);
    expect(wheelCenter.dy, greaterThan(2012 * 0.75));
  });

  testWidgets('landscape layout keeps wheel in the side control column',
      (tester) async {
    await _pumpAppAtSize(tester, const Size(800, 600));

    final wheelSize = tester.getSize(find.byType(ClickWheel));
    final wheelCenter = tester.getCenter(find.byType(ClickWheel));
    final cardRight = tester.getTopRight(find.byType(Card)).dx;
    final wheelLeft = tester.getTopLeft(find.byType(ClickWheel)).dx;

    expect(wheelSize.width, 340);
    expect(wheelCenter.dx, greaterThan(800 * 0.6));
    expect(wheelLeft, greaterThan(cardRight));
  });

  testWidgets('gesture-navigation bottom inset still leaves wheel reachable',
      (tester) async {
    tester.view.padding = const FakeViewPadding(bottom: 34);
    addTearDown(tester.view.resetPadding);

    await _pumpAppAtSize(tester, const Size(390, 844));

    final wheelBottom = tester.getBottomLeft(find.byType(ClickWheel)).dy;
    final wheelCenter = tester.getCenter(find.byType(ClickWheel));

    expect(wheelBottom, lessThanOrEqualTo(844 - 34));
    expect(wheelCenter.dy, greaterThan(844 * 0.62));
  });

  testWidgets('wheel button regions have forgiving hit areas', (tester) async {
    await _pumpAppAtSize(tester, const Size(390, 844));

    expect(_iconButtonSize(tester, Icons.skip_previous).width,
        greaterThanOrEqualTo(64));
    expect(_iconButtonSize(tester, Icons.skip_next).width,
        greaterThanOrEqualTo(64));
    expect(
        _iconButtonSize(tester, Icons.pause).width, greaterThanOrEqualTo(64));
    expect(_textButtonSize(tester, 'MODE').height, greaterThanOrEqualTo(64));
    expect(tester.getSize(find.byType(FilledButton)).width,
        greaterThanOrEqualTo(104));
  });

  testWidgets('active seek preview reduces card density and explains commit',
      (tester) async {
    await _pumpAppAtSize(tester, const Size(390, 844));

    await tester.tap(find.byIcon(Icons.skip_next));
    await tester.pump();

    expect(find.text('Seek preview'), findsOneWidget);
    expect(find.text('Release or center to commit • MODE cancels'),
        findsOneWidget);
    expect(find.text('The Long Way Home • Audiobook'), findsNothing);
    expect(find.textContaining('Volume 62%'), findsNothing);
  });

  testWidgets('wheel exposes mode-aware accessibility labels', (tester) async {
    final semantics = tester.ensureSemantics();
    try {
      await _pumpAppAtSize(tester, const Size(390, 844));

      _expectSemanticsNode(
        tester,
        label: 'Seek click wheel',
        hint: 'Scrub through the current track',
      );
      _expectSemanticsNode(
        tester,
        label: 'Change wheel mode',
        hint: 'Cancels active seek preview and moves to the next mode.',
      );
      _expectSemanticsNode(
        tester,
        label: 'Next wheel mode',
        hint: 'Cycles to the next click-wheel mode.',
      );
      _expectSemanticsNode(
        tester,
        label: 'Seek backward preview',
        hint: 'Moves the local seek preview backward.',
      );
      _expectSemanticsNode(
        tester,
        label: 'Seek forward preview',
        hint: 'Moves the local seek preview forward.',
      );
      _expectSemanticsNode(
        tester,
        label: 'Pause',
        hint: 'Toggles committed playback without committing seek preview.',
      );

      await tester.tap(find.byIcon(Icons.skip_next));
      await tester.pump();

      _expectSemanticsNode(
        tester,
        label: 'Commit seek preview',
        hint: 'Applies the previewed seek position.',
      );
      expect(
        find.bySemanticsLabel(RegExp(r'Preview target .+ committed .+')),
        findsOneWidget,
      );

      await tester.tap(find.text('MODE'));
      await tester.pump();

      _expectSemanticsNode(
        tester,
        label: 'Volume click wheel',
        hint: 'Fine tune output level',
      );
      _expectSemanticsNode(
        tester,
        label: 'Volume down',
        hint: 'Lowers the local demo volume.',
      );
      _expectSemanticsNode(
        tester,
        label: 'Volume up',
        hint: 'Raises the local demo volume.',
      );

      await tester.tap(find.text('MODE'));
      await tester.pump();

      _expectSemanticsNode(
        tester,
        label: 'Queue click wheel',
        hint: 'Step through upcoming items',
      );
      _expectSemanticsNode(
        tester,
        label: 'Queue backward',
        hint: 'Moves the queue cursor backward.',
      );
      _expectSemanticsNode(
        tester,
        label: 'Queue forward',
        hint: 'Moves the queue cursor forward.',
      );
    } finally {
      semantics.dispose();
    }
  });

  testWidgets('too-small viewport surfaces explicit unsupported state',
      (tester) async {
    await _pumpAppAtSize(tester, const Size(280, 420));

    expect(find.byType(ClickWheel), findsNothing);
    expect(find.textContaining('Viewport too small'), findsOneWidget);
  });
}

void _expectSemanticsNode(
  WidgetTester tester, {
  required String label,
  required String hint,
}) {
  final finder = find.bySemanticsLabel(label);
  expect(finder, findsOneWidget);
  expect(tester.getSemantics(finder).hint, hint);
}

Size _iconButtonSize(WidgetTester tester, IconData icon) {
  return tester.getSize(
    find.ancestor(
      of: find.byIcon(icon),
      matching: find.byType(IconButton),
    ),
  );
}

Size _textButtonSize(WidgetTester tester, String text) {
  return tester.getSize(
    find.ancestor(
      of: find.text(text),
      matching: find.byType(TextButton),
    ),
  );
}

Future<void> _pumpAppAtSize(WidgetTester tester, Size size) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  await tester.pumpWidget(MassMateApp());
}
