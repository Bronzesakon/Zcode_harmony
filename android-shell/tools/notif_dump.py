#!/usr/bin/env python3
"""Print the app's own notifications (title/text/when) from `dumpsys notification --noredact`.

Reads a dump from stdin (or a file argument) and prints one block per
`com.zcode.remote` NotificationRecord. Used during real-device verification of
the fluid cloud (流体云) text: the promoted record's `when` freezes as soon as
the data plane stops, so `when` + text are the two fields to read.
"""
import re
import subprocess
import sys

ADB = r"C:/Program Files/UotanToolbox/Bin/platform-tools/adb.exe"
SERIAL = "3B6F5RE8GCL3LYY7"

FIELDS = ("android.title=", "android.text=", "android.shortCriticalText=", "when=")


def read_dump() -> str:
    if len(sys.argv) > 1:
        with open(sys.argv[1], "r", encoding="utf-8", errors="replace") as fh:
            return fh.read()
    import os

    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    out = subprocess.run(
        [ADB, "-s", SERIAL, "shell", "dumpsys notification --noredact"],
        capture_output=True,
        env=env,
    )
    return out.stdout.decode("utf-8", "replace")


def main() -> None:
    text = read_dump()
    lines = text.splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"\s*NotificationRecord\(.*pkg=com\.zcode\.remote\b", line)
        if not m:
            continue
        idm = re.search(r"id=(\d+)", line)
        flags = re.search(r"flags=([A-Z_|]+)", line)
        print(f"--- id={idm.group(1) if idm else '?'} flags={flags.group(1) if flags else ''}")
        j = i + 1
        while j < len(lines) and not re.match(r"\s*NotificationRecord\(", lines[j]):
            for field in FIELDS:
                if field in lines[j]:
                    print("   " + lines[j].strip())
            j += 1


if __name__ == "__main__":
    main()
