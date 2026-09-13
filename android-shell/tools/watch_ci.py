#!/usr/bin/env python3
"""Read this repo's CI status without gh and without an API token.

Why this exists
---------------
The Android shell cannot be built locally (migration doc D13), so CI is the only
compiler. Actions *logs* require authentication — they 404 anonymously, even on
a public repository — but the run and job pages are server-rendered, and a job's
annotations are baked into its page HTML. That makes the pages the only failure
channel an unauthenticated observer can read, which is why the workflow re-emits
Gradle errors as `::error::` annotations (see .github/workflows/android-shell.yml).

What it reads, and why those signals
------------------------------------
  * the newest run row of the workflow page: run number, commit, branch, push
    time. Identity is not decoration. A run takes a few seconds to appear after a
    push, and the run before yours has the same job names and the same green
    result, so "the newest run is green" is not the same claim as "my push is
    green". The commit is therefore printed, and warned about when it disagrees
    with the local HEAD.
  * per job, from its `<streaming-graph-job>` element: `data-job-id`,
    `data-concluded` and the status octicon (`check-circle-fill` success,
    `x-circle-fill` failure, `skip` skipped). Read the ELEMENT, not the slice
    after `data-job-id="`: GitHub writes `data-concluded` before that attribute,
    so slicing from it attributes every job's flag to its predecessor and leaves
    the last job looking unfinished — which made `--watch` poll to its deadline
    on every single run, green or not.
  * per failed job, the `annotation-message` blocks of the Annotations section,
    whose `annotationContainer` div holds the compiler error lines verbatim.

Exit code
---------
  0  every job of the newest run succeeded or was skipped
  1  no run at all; a job failed or was cancelled; the run has no jobs and is
     not merely seconds old (workflow validation failure — see the `paths` /
     `paths-ignore` note in the workflow — or GitHub changed the job markup);
     or `--watch` reached its deadline with jobs still running

Usage
-----
    python tools/watch_ci.py            # one snapshot
    python tools/watch_ci.py --watch    # poll until every job concludes
"""
import argparse
import datetime
import html
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

REPO = 'Bronzesakon/Zcode_harmony'
WORKFLOW = 'android-shell.yml'
BASE = f'https://github.com/{REPO}'

# Octicon -> label, for humans only: the exit code reads the icon key, never the
# translated label.
STATUS_ICON = {
    'check-circle-fill': '成功',
    'x-circle-fill': '失败',
    'skip': '跳过',
    'dash': '跳过',
    'clock': '排队中',
    'dot-fill': '进行中',
    'stop': '已取消',
}
# A cancelled run counts as a failure: `concurrency: cancel-in-progress` means
# a cancel is the expected outcome of pushing again, but it still means no build
# of this commit finished, which the caller must not read as success.
BAD_ICON = ('x-circle-fill', 'stop')

RUN_ROW = re.compile(r'<a href="[^"]*/actions/runs/(\d+)"[^>]*class="d-flex')
JOB_ELEMENT = re.compile(r'<streaming-graph-job\b')
ANNOTATION = re.compile(r'<annotation-message\b')
ANNOTATION_BODY = re.compile(r'annotationContainer[^>]*>(.*?)<button', re.S)
# A run with no jobs that is younger than this is simply a run GitHub has not
# rendered yet; older than it, the emptiness is the finding.
FRESH_RUN_SECONDS = 120


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


def parse_stamp(value: str | None) -> datetime.datetime | None:
    if not value:
        return None
    try:
        return datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        return None


def latest_run() -> dict | None:
    """The newest run of this workflow, with the identity shown on its row.

    The list is newest-first, so the first anchor is the newest run — but not
    necessarily the one you just pushed; see the module docstring.
    """
    page = fetch(f'{BASE}/actions/workflows/{WORKFLOW}')
    first = RUN_ROW.search(page)
    if not first:
        return None
    tail = page[first.start():]
    following = RUN_ROW.search(tail, 1)
    row = tail[:following.start()] if following else tail[:8000]

    # The row's accessible label leads with the run's state and carries the run
    # number and commit subject: "completed successfully:  Run 90 of <wf>. <subject>"
    label = re.search(r'aria-label="([^"]*)"', row)
    label = html.unescape(label.group(1)) if label else ''
    numbered = re.match(r'([^:]*):\s*Run (\d+) of ([^.]+)\.\s*(.*)', label)
    commit = re.search(r'/commit/([0-9a-f]{40})', row)
    branch = re.search(r'branch-name[^>]*title="([^"]+)"', row)
    pushed = re.search(r'<relative-time\s+datetime="([^"]+)"', row)
    pushed_at = parse_stamp(pushed.group(1)) if pushed else None
    return {
        'id': first.group(1),
        'number': numbered.group(2) if numbered else '?',
        'subject': numbered.group(4).strip() if numbered else '',
        'state': (numbered.group(1) if numbered else '').strip().lower(),
        'commit': commit.group(1) if commit else '',
        'branch': branch.group(1) if branch else '',
        'pushed_at': pushed_at,
    }


def jobs(run_id: str) -> list[dict]:
    page = fetch(f'{BASE}/actions/runs/{run_id}')
    out = []
    for block in JOB_ELEMENT.split(page)[1:]:
        head = block[:8000]
        key = re.search(r'data-job-id="([\w-]+)"', head)
        if not key:
            continue
        icon = re.search(r'octicon-([\w-]+)', head)
        icon = icon.group(1) if icon else ''
        job_id = re.search(rf'/actions/runs/{run_id}/job/(\d+)', head)
        concluded = re.search(r'data-concluded="(\w+)"', head)
        # Markup-agnostic: the job name is the text right after the anchor's
        # closing '>' and before the next tag (the duration lives in a child
        # element, so it cannot be part of the pattern).
        anchor = re.search(r'>\s*([A-Za-z][A-Za-z0-9 +()/._-]{2,50}?)\s*<', head)
        out.append({
            'key': key.group(1),
            'id': job_id.group(1) if job_id else None,
            'icon': icon,
            'status': STATUS_ICON.get(icon, icon or '?'),
            'concluded': concluded is not None and concluded.group(1) == 'true',
            'text': anchor.group(1).strip() if anchor else key.group(1),
        })
    return out


def annotations(job_id: str, run_id: str) -> list[str]:
    """The annotation bodies of a job page, one per re-emitted `::error::` line.

    Each annotation is its own `<annotation-message>` block and its text sits in
    the `annotationContainer` div, so read that rather than trimming everything
    after the word "Annotations": the old slice picked up the surrounding chrome
    — the step name repeated once per row, then stylesheet text — and printed it
    interleaved with the errors.
    """
    page = fetch(f'{BASE}/actions/runs/{run_id}/job/{job_id}')
    out = []
    for block in ANNOTATION.split(page)[1:]:
        body = ANNOTATION_BODY.search(block)
        if not body:
            continue
        line = text_of(body.group(1))
        if line:
            out.append(line)
    return out


def local_head() -> str:
    """This working tree's HEAD, or '' when git cannot answer."""
    try:
        done = subprocess.run(
            ['git', '-C', str(Path(__file__).resolve().parents[1]), 'rev-parse', 'HEAD'],
            capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.SubprocessError):
        return ''
    if done.returncode != 0:
        return ''
    return done.stdout.strip()


def age_of(when: datetime.datetime | None) -> tuple[str, float]:
    """(human age, seconds) for a timestamp read off the page."""
    if not when:
        return '', float('inf')
    seconds = (datetime.datetime.now(datetime.timezone.utc) - when).total_seconds()
    if seconds < 60:
        return '刚刚', seconds
    if seconds < 3600:
        return f'{int(seconds // 60)} 分钟前', seconds
    return f'{int(seconds // 3600)} 小时 {int(seconds % 3600 // 60)} 分前', seconds


def describe(run: dict, head: str) -> str:
    parts = [f"run #{run['number']}"]
    if run['commit']:
        parts.append(run['commit'][:7])
    if run['branch']:
        parts.append(f"分支 {run['branch']}")
    age, _ = age_of(run['pushed_at'])
    if age and run['pushed_at']:
        parts.append(f"推送 {run['pushed_at'].astimezone().strftime('%m-%d %H:%M')}（{age}）")
    lines = ['  '.join(parts)]
    if run['subject']:
        lines.append(f"  {run['subject']}")
    if head and run['commit'] and head != run['commit']:
        lines.append(f"  ⚠ 该 run 的提交是 {run['commit'][:7]}，本机 HEAD 是 {head[:7]}"
                     ' —— 你推的那次可能还没出现在列表里，或本机有未推送的提交')
    return '\n'.join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--watch', action='store_true',
                        help='轮询到全部 job 结束（长驻命令可能被宿主取消，优先单次读取）')
    parser.add_argument('--timeout', type=int, default=900, help='--watch 的总时限，秒')
    args = parser.parse_args()

    head = local_head()
    deadline = time.time() + args.timeout
    while True:
        run = latest_run()
        if not run:
            print('该 workflow 还没有任何运行')
            return 1
        current = jobs(run['id'])
        print(describe(run, head))
        for job in current:
            print(f"  {job['key']:<11} {job['status']:<4} {job['text']}")

        if not current:
            age, seconds = age_of(run['pushed_at'])
            print(f"  该 run 没有任何 job（运行状态：{run['state'] or '未知'}，{age}）")
            if seconds < FRESH_RUN_SECONDS or 'progress' in run['state'] or 'queued' in run['state']:
                print('  刚创建、job 还没渲染出来，稍后再读一次')
                return 0
            print('  workflow 校验失败（例如 paths 与 paths-ignore 同用），或 GitHub 改了 job 的标记')
            return 1

        pending = any(not job['concluded'] for job in current)
        if args.watch and pending and time.time() <= deadline:
            time.sleep(30)
            continue

        for job in current:
            if job['icon'] not in BAD_ICON or not job['id']:
                continue
            try:
                notes = annotations(job['id'], run['id'])
            except Exception as exc:  # a status report is worth more than this one section
                print(f"\n--- {job['key']} 的 Annotations 读取失败：{exc}")
                continue
            if notes:
                print(f"\n--- {job['key']} 的 Annotations（编译/测试失败原因）---")
                for line in notes:
                    print(f'  {line}')

        bad = [job for job in current if job['icon'] in BAD_ICON]
        if pending:
            print(f"\n--watch 到时限（{args.timeout}s）仍有 job 未结束")
            return 1
        if bad:
            print(f"\n{'、'.join(job['key'] for job in bad)} 未通过或已取消")
            return 1
        return 0


if __name__ == '__main__':
    sys.exit(main())
