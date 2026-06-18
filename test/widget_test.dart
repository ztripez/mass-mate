import 'package:flutter_test/flutter_test.dart';
import 'package:mass_mate/main.dart';

void main() {
  testWidgets('renders click wheel player shell', (tester) async {
    await tester.pumpWidget(const MassMateApp());

    expect(find.text('Mass Mate'), findsOneWidget);
    expect(find.text('Chapter 12: Night Drive'), findsOneWidget);
    expect(find.byType(ClickWheel), findsOneWidget);
  });
}
