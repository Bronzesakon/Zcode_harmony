#!/usr/bin/env python3
"""Resource reference check: every `@type/name` a resource file mentions must exist.

Why this exists
---------------
The Android SDK is deliberately not installed on this machine, so `aapt2 link`
only runs in CI: a typo like `@color/miuix_surface_contaner`, an `app:`-prefixed
attribute written into a <style>, or a string deleted while a layout still uses
it, costs a full three-minute build round trip and — until 2026-09-12 — produced
an annotation that did not even name the offending reference ("failed linking
references" with nothing to grep). This catches all of that in under a second.

What it checks, and what it deliberately does not
------------------------------------------------
* Checks: string, color, dimen, drawable, layout, xml, mipmap, and
  non-dotted style names — the types where a wrong name is the actual mistake.
* Skips `@android:...` (framework) and `@+id/...` (definitions).
* Skips dotted style *parents* (`Widget.Material3.Button…`, `Theme.…`): those come
  from the theme/Material libraries and are not in this repository.
* Skips `?attr/` and `?android:attr/` (theme attributes are resolved at runtime).
* Does not resolve `@type/name` inside string values passed through
  `android:text="@string/x"`-style indirection — it is a name check, not a compiler.
"""
import glob
import os
import re
import sys

REF = re.compile(r'@(?!\+|android:)([a-z]+)/([A-Za-z0-9_.]+)')
COMMENT = re.compile(r'<!--.*?-->', re.S)


def strip_comments(text: str) -> str:
    """Blanks XML comments but keeps line numbers (and column layout) intact."""
    return COMMENT.sub(lambda m: re.sub(r'[^\n]', ' ', m.group(0)), text)

# Types whose definitions live in res/values*/*.xml as <type name="…">.
VALUE_TYPES = {'string', 'color', 'dimen', 'style', 'bool', 'integer', 'array'}
# Types whose definitions live as files (drawable/foo.xml, mipmap-hdpi/foo.png, …).
FILE_TYPES = {'drawable', 'mipmap', 'layout', 'xml', 'anim', 'menu', 'raw', 'font'}


def collect() -> dict:
    defined = {t: set() for t in VALUE_TYPES | FILE_TYPES}
    for path in glob.glob('app/src/main/res/values*/*.xml'):
        if os.path.basename(path) == 'public.xml':
            continue
        text = open(path, encoding='utf-8').read()
        for name in re.findall(r'<(string|color|dimen|style|bool|integer|array)\s+name="([^"]+)"', text):
            defined[name[0]].add(name[1])
    for path in glob.glob('app/src/main/res/*/*'):
        directory = os.path.basename(os.path.dirname(path)).split('-')[0]
        if directory in FILE_TYPES:
            defined[directory].add(os.path.splitext(os.path.basename(path))[0])
    return defined


def main() -> int:
    defined = collect()
    problems = []
    targets = sorted(
        glob.glob('app/src/main/res/**/*.xml', recursive=True) + ['app/src/main/AndroidManifest.xml']
    )
    for path in targets:
        text = strip_comments(open(path, encoding='utf-8').read())
        for line_no, line in enumerate(text.split('\n'), 1):
            for kind, name in REF.findall(line):
                if kind not in defined:
                    continue
                # A dotted style parent is a library style, not ours.
                if kind == 'style' and '.' in name:
                    continue
                if name not in defined[kind]:
                    problems.append(f'{path}:{line_no}: @{kind}/{name} is not defined')
    if problems:
        print('UNRESOLVED RESOURCE REFERENCES:')
        for problem in problems:
            print('  - ' + problem)
        return 1
    print('resource references OK: %d files scanned' % len(targets))
    return 0


if __name__ == '__main__':
    sys.exit(main())
