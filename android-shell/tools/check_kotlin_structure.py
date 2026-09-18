#!/usr/bin/env python3
"""Mechanical Kotlin sanity checks: brace balance, package/dir match, duplicates,
plus one wiring invariant that no test can see.

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

MAIN_ACTIVITY = os.path.join('app', 'src', 'main', 'java', 'com', 'zcode', 'remote', 'MainActivity.kt')
INJECTION_RESET_CALL = 'ShellRuntime.onPageStarted()'


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


def check_injection_reset_wiring(problems: list) -> None:
    """The page-load callback must reset the injection-readiness flag.

    Why this is a check and not a comment: `injectedReady` answers "may I dispatch
    JS into the page yet?" and gates three things - the settings readout, the
    notification-tap locator, and the single diagnostic queue shared by both adb
    doors (`ShellRuntime.dispatchJsDiag`; the foreground door delegates to it).
    Its ONLY reset lives in `ShellRuntime.onPageStarted()`. When that reset had no
    caller the flag latched true forever after the first load: the settings page
    reported "ready" for a document that had no injection, and commands sent during a
    reload window could be swallowed without even a log line. 2026-09-18 fixed it by
    calling it from this callback.

    The regression test (`InjectionReadinessTest`) pins the *holder's* semantics, not
    this call site - so deleting the line below leaves every test green. That is
    precisely the bug being guarded here, and the reason the check exists.

    Note this pins wiring textually: if the reset is ever moved into a helper, point
    this check at the helper - do not delete the check.
    """
    if not os.path.exists(MAIN_ACTIVITY):
        problems.append(f'{MAIN_ACTIVITY}: missing (the page-load callback lives here)')
        return
    src = open(MAIN_ACTIVITY, encoding='utf-8').read()
    m = re.search(r'override\s+fun\s+onPageStarted\s*\(', src)
    if not m:
        problems.append(
            f'{MAIN_ACTIVITY}: no `override fun onPageStarted(` - the page-load callback '
            f'moved or was renamed; update this check instead of dropping it')
        return
    # Body proxy: from this override up to the next override (these are flat members
    # of the WebViewClient, so nothing nests). Avoids brace matching, which comments
    # and string literals can throw off.
    nxt = re.search(r'override\s+fun\s+\w+\s*\(', src[m.end():])
    body = src[m.end():m.end() + nxt.start()] if nxt else src[m.end():]
    if INJECTION_RESET_CALL not in body:
        problems.append(
            f'{MAIN_ACTIVITY}: `onPageStarted` does not call {INJECTION_RESET_CALL} - the '
            f'injection-readiness flag would never reset on a page load (the settings page '
            f'would report "ready" for a fresh document, and commands sent during a reload '
            f'window could be swallowed). If the reset moved into a helper, update this '
            f'check; do not delete it')


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

    check_injection_reset_wiring(problems)

    print(f'kotlin files checked: {len(files)}')
    if problems:
        print('PROBLEMS:')
        for p in problems:
            print('  -', p)
        return 1
    print('structure OK: balanced delimiters, package matches directory, no duplicates, '
          'no merge markers, page-load callback resets injection readiness')
    return 0


if __name__ == '__main__':
    sys.exit(main())
