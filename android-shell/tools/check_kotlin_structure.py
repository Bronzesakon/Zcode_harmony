#!/usr/bin/env python3
"""Mechanical Kotlin sanity checks: brace balance, package/dir match, duplicates.

A full Kotlin compile needs the Android SDK, which this project deliberately does
not install locally. These checks cannot prove the code compiles, but they do
catch the failure mode that hand-editing tends to produce - a stray brace from a
botched replacement - before it costs a CI round trip.
"""
import glob
import os
import re
import sys

BACKSLASH = chr(92)
QUOTE = chr(34)
APOS = chr(39)
BACKTICK = chr(96)


def strip_noncode(src: str) -> str:
    """Remove comments and literals so brace counting is honest."""
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            while i < n and src[i] != '\n':
                i += 1
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            i += 2
            while i + 1 < n and not (src[i] == '*' and src[i + 1] == '/'):
                i += 1
            i += 2
        elif src[i:i + 3] == QUOTE * 3:
            i += 3
            while i + 2 < n and src[i:i + 3] != QUOTE * 3:
                i += 1
            i += 3
        elif c == QUOTE:
            i += 1
            while i < n and src[i] != QUOTE:
                if src[i] == BACKSLASH:
                    i += 1
                i += 1
            i += 1
        elif c == BACKTICK:
            # A back-quoted identifier (Kotlin lets a function name be almost any
            # text, e.g. a test name). Skip it before the apostrophe rule can see
            # it: `the system's theme` would otherwise open a phantom char literal
            # and swallow the rest of the line, including a brace.
            i += 1
            while i < n and src[i] != BACKTICK:
                i += 1
            i += 1
        elif c == APOS:
            i += 1
            while i < n and src[i] != APOS:
                if src[i] == BACKSLASH:
                    i += 1
                i += 1
            i += 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def main() -> int:
    files = sorted(glob.glob('app/src/**/*.kt', recursive=True))
    problems = []
    for f in files:
        src = open(f, encoding='utf-8').read()
        code = strip_noncode(src)
        for open_c, close_c, label in [('{', '}', 'brace'), ('(', ')', 'paren'), ('[', ']', 'bracket')]:
            depth = code.count(open_c) - code.count(close_c)
            if depth != 0:
                problems.append(f'{f}: unbalanced {label} ({depth:+d})')
        m = re.search(r'^package\s+([\w.]+)', src, re.M)
        if not m:
            problems.append(f'{f}: no package declaration')
        else:
            expected = os.path.dirname(f).split('java' + os.sep, 1)[1].replace(os.sep, '.')
            if m.group(1) != expected:
                problems.append(f'{f}: package {m.group(1)} != directory {expected}')
        names = re.findall(
            r'^\s*(?:private |internal |public )?(?:fun|class|object|data class|enum class|interface)\s+(\w+)',
            src, re.M)
        dupes = sorted({n for n in names if names.count(n) > 1})
        if dupes:
            problems.append(f'{f}: duplicate declaration(s) {dupes}')
        # Leftovers from editing that would be a compile error or a silent hole.
        for marker in ['<<<<<<<', '>>>>>>>', 'TODO(', 'FIXME(', 'XXX']:
            if marker in src:
                problems.append(f'{f}: contains {marker}')

    print(f'kotlin files checked: {len(files)}')
    if problems:
        print('PROBLEMS:')
        for p in problems:
            print('  -', p)
        return 1
    print('structure OK: balanced delimiters, package matches directory, no duplicates, no merge markers')
    return 0


if __name__ == '__main__':
    sys.exit(main())
