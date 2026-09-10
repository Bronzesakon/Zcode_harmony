#!/usr/bin/env python3
"""Read this repo's CI status without gh and without an API token.

Why this exists
---------------
The Android shell cannot be built locally (migration doc D13), so CI is the only
compiler. Actions *logs* require authentication — they 404 anonymously, even on a
public repository — but the run and job pages are server-rendered, and a job's
annotations are baked into its page HTML. That makes the pages the only failure
channel an unauthenticated observer can read, which is why the workflow re-emits
Gradle errors as `::error::` annotations (see .github/workflows/android-shell.yml).

What it reads, and why those signals
------------------------------------
  * per job: `data-job-id="<key>"` and the status octicon in the same block
    (`check-circle-fill` success, `x-circle-fill` failure, `skip` skipped)
  * per failed job: the `Annotations` section text, which contains the compiler
    error lines

Usage
-----
    python tools/watch_ci.py            # one snapshot
    python tools/watch_ci.py --watch    # poll until every job concludes
"""
import argparse
import html
import re
import sys
import time
import urllib.request

REPO = 'Bronzesakon/Zcode_harmony'
WORKFLOW = 'android-shell.yml'
BASE = f'https://github.com/{REPO}'

STATUS_ICON = {
    'check-circle-fill': '成功',
    'x-circle-fill': '失败',
    'skip': '跳过',
    'dash': '跳过',
    'clock': '排队中',
    'dot-fill': '进行中',
    'stop': '已取消',
}


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={'User-Agent': 'zcode-ci-watch'})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return resp.read().decode('utf-8', 'replace')
        except Exception:
            if attempt == 2:
                raise
            time.sleep(5)
    raise RuntimeError('unreachable')


def text_of(fragment: str) -> str:
    return re.sub(r'\s+', ' ', html.unescape(re.sub(r'<[^>]+>', ' ', fragment))).strip()


def latest_run_id() -> str | None:
    page = fetch(f'{BASE}/actions/workflows/{WORKFLOW}')
    seen: list[str] = []
    for run_id in re.findall(r'/actions/runs/(\d+)', page):
        if run_id not in seen:
            seen.append(run_id)
    return seen[0] if seen else None


def jobs(run_id: str) -> list[dict]:
    page = fetch(f'{BASE}/actions/runs/{run_id}')
    out = []
    for block in re.split(r'data-job-id="', page)[1:]:
        key = block[:block.find('"')]
        head = block[:8000]
        icon = re.search(r'octicon-([\w-]+)', head)
        job_id = re.search(rf'/actions/runs/{run_id}/job/(\d+)', head)
        # Markup-agnostic: the job name is the text right after the anchor's
        # closing '>' and before the next tag (the duration lives in a child
        # element, so it cannot be part of the pattern).
        anchor = re.search(r'>\s*([A-Za-z][A-Za-z0-9 +()/._-]{2,50}?)\s*<', head)
        name = anchor.group(1).strip() if anchor else key
        out.append({
            'key': key,
            'id': job_id.group(1) if job_id else None,
            'status': STATUS_ICON.get(icon.group(1) if icon else '', icon.group(1) if icon else '?'),
            'concluded': 'data-concluded="true"' in head,
            'text': name,
        })
    return out


def annotations(job_id: str, run_id: str) -> list[str]:
    page = fetch(f'{BASE}/actions/runs/{run_id}/job/{job_id}')
    idx = page.find('Annotations')
    if idx < 0:
        return []
    seg = re.sub(r'<script.*?</script>', ' ', page[idx:idx + 40000], flags=re.S)
    lines, prev = [], None
    for line in (l.strip() for l in re.sub(r'<[^>]+>', '\n', html.unescape(seg)).splitlines()):
        if line and line != prev and line not in ('Show more', 'Show less'):
            lines.append(line)
            prev = line
    # Drop the section header itself and CSS noise that follows the annotations.
    trimmed = []
    for line in lines[1:]:
        if line.startswith('@media') or line.startswith('html {') or line.startswith('scroll-behavior'):
            break
        trimmed.append(line)
    return trimmed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--watch', action='store_true')
    parser.add_argument('--timeout', type=int, default=900)
    args = parser.parse_args()

    deadline = time.time() + args.timeout
    while True:
        run_id = latest_run_id()
        if not run_id:
            print('该 workflow 还没有任何运行')
            return 1
        current = jobs(run_id)
        print(f'run {run_id}')
        for job in current:
            print(f"  {job['key']:<8} {job['status']:<6} {job['text']}")
        pending = any(not job['concluded'] for job in current)
        if not args.watch or not pending or time.time() > deadline:
            for job in current:
                if job['status'] != '失败' or not job['id']:
                    continue
                notes = annotations(job['id'], run_id)
                if notes:
                    print(f"\n--- {job['key']} 的 Annotations（编译/测试失败原因）---")
                    for line in notes:
                        print(f'  {line}')
            return 0
        time.sleep(30)


if __name__ == '__main__':
    sys.exit(main())
