import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('click-wheel contract marks current prototype button behavior', () {
    final clickWheelContract =
        File('docs/click-wheel-contract.md').readAsStringSync();

    expect(
      _modeTableRow(clickWheelContract, 'Seek'),
      allOf(
        contains('current prototype'),
        contains('adaptive preview'),
      ),
    );
    expect(
      _modeTableRow(clickWheelContract, 'Volume'),
      allOf(
        contains('current prototype'),
        contains('volume movement'),
        isNot(contains('Previous/next track')),
      ),
    );
    expect(
      _modeTableRow(clickWheelContract, 'Queue'),
      allOf(
        contains('current prototype'),
        contains('queue movement'),
        isNot(contains('Page queue')),
      ),
    );
  });

  test('Music Assistant matrix separates prototype behavior from target rows',
      () {
    final musicAssistantMatrix =
        File('docs/music-assistant-wheel-matrix.md').readAsStringSync();
    final prototypeSection = _section(
      musicAssistantMatrix,
      '## Prototype versus Music Assistant target',
    );

    expect(
      prototypeSection,
      allOf(
        contains('current'),
        contains('target'),
        contains('Seek mode'),
        contains('Volume'),
        contains('Queue'),
        contains('Left/right'),
        contains('Center'),
      ),
    );

    expect(_tableRow(musicAssistantMatrix, 'Center'), contains('target'));
    expect(_tableRow(musicAssistantMatrix, 'Left/right'), contains('Target'));
  });
}

String _modeTableRow(String markdown, String mode) => _tableRow(markdown, mode);

String _tableRow(String markdown, String firstCell) {
  return markdown.split('\n').singleWhere(
        (line) => line.startsWith('| $firstCell |'),
        orElse: () =>
            throw StateError('Missing markdown table row: $firstCell'),
      );
}

String _section(String markdown, String heading) {
  final start = markdown.indexOf(heading);
  if (start == -1) throw StateError('Missing markdown section: $heading');

  final nextHeading = markdown.indexOf('\n## ', start + heading.length);
  return markdown.substring(
    start,
    nextHeading == -1 ? markdown.length : nextHeading,
  );
}
