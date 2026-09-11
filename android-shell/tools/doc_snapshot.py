#!/usr/bin/env python3
"""Snapshot official vendor documentation into ``android-shell/docs/`` as
offline Markdown (text + localized images).

Why a script instead of manual copies: the docs/ tree is a byte-for-byte record
of vendor pages, so it must be reproducible and free of transcription drift.
Re-run with ``--force`` to refresh a snapshot; the manifest records what came
from where.

Sources handled
---------------
* ``developer.android.com`` — the article body is extracted from
  ``div.devsite-article-body``. The site redirects cookie-less requests into a
  Google auto-sign-in flow, so every request carries ``auto_signin=False``;
  without it the fetch returns a 302 to an OAuth page.
* ``raw.githubusercontent.com`` — already Markdown, used verbatim.
* anything else — falls back to a whole-page HTML to Markdown conversion.

Images are downloaded to ``docs/_assets/<host>/<8-hex-of-url>-<name>`` and the
Markdown references are rewritten to relative paths, so the archive reads
offline. Existing files are left alone unless ``--force`` is passed.

Pages that need a JavaScript renderer (``m3.material.io``) are NOT handled here
— that site ships a 62 KB shell with no article text, so those snapshots were
taken through a rendering extractor and are recorded in the same manifest.

Usage::

    python tools/doc_snapshot.py --probe          # status only, writes nothing
    python tools/doc_snapshot.py                  # fetch what is missing
    python tools/doc_snapshot.py --force 03       # refresh one subdirectory
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from datetime import date

try:
    import html2text
    from bs4 import BeautifulSoup
except ImportError:  # pragma: no cover - dependency hint for the operator
    sys.exit("missing dependencies: pip install beautifulsoup4 lxml html2text")

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(REPO, "docs")
ASSETS = os.path.join(DOCS, "_assets")
MANIFEST = os.path.join(DOCS, "_manifest.json")
TODAY = date.today().isoformat()

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

DEVSITE = "https://developer.android.com"
MDC = "https://raw.githubusercontent.com/material-components/material-components-android/master"

SECTION = "04-界面规范-Android16-M3Expressive"
LANGS = "01-设计语言与基础"
PLATFORM = "02-平台行为（Android 16）"
VIEWS = "03-Views 控件规范（MDC-Android）"
STATUS = "04-官方状态公告"
VIEWS_BASE = f"{MDC}/docs"


def d(url: str) -> str:
    """Append the query flags developer.android.com needs to serve content."""
    if "developer.android.com" not in url:
        return url
    sep = "&" if "?" in url else "?"
    return f"{url}{sep}auto_signin=False"


# --------------------------------------------------------------------------
# Manifest: one output file may aggregate several source pages, because the
# MDC reference is split into many small per-component files and a reader wants
# one document per control. Output names may contain a subdirectory.
# --------------------------------------------------------------------------
MANIFEST_ENTRIES: list[tuple[str, list[str], str]] = [
    # === 02 平台行为：与控件无关，但决定界面能否正确显示 ===
    (
        f"{PLATFORM}/01-行为变更（UI 与交互相关）.md",
        [f"{DEVSITE}/about/versions/16/behavior-changes-16?hl=zh-cn"],
        "面向 Android 16 (API 36) 的应用在 UI 与交互上的强制行为变更",
    ),
    (
        f"{PLATFORM}/02-功能总览（大屏与自适应）.md",
        [f"{DEVSITE}/about/versions/16/features?hl=zh-cn"],
        "Android 16 平台功能总览，含大屏、自适应与界面相关能力",
    ),
    (
        f"{PLATFORM}/03-边到边显示 Edge-to-edge（Views 实现）.md",
        [f"{DEVSITE}/develop/ui/views/layout/edge-to-edge?hl=zh-cn"],
        "targetSdk 35 起强制边到边，Views 侧的 insets 处理方式",
    ),
    (
        f"{PLATFORM}/04-预测性返回（返回手势与动画）.md",
        [f"{DEVSITE}/guide/navigation/custom-back/predictive-back-gesture?hl=zh-cn"],
        "targetSdk 36 起预测性返回默认开启，需要迁移的 API 与适配方式",
    ),
    (
        f"{PLATFORM}/05-动态取色 Dynamic color（Views）.md",
        [f"{DEVSITE}/develop/ui/views/theming/dynamic-colors?hl=zh-cn"],
        "Material You 动态配色在 Android 12+ 的接入方式与兜底策略",
    ),
    (
        f"{PLATFORM}/06-主题与样式 Themes and styles（Views 官方指南）.md",
        [f"{DEVSITE}/develop/ui/views/theming/themes?hl=zh-cn"],
        "Views 体系下主题、样式与颜色属性的官方说明",
    ),
    # === 01 设计语言与基础 ===
    (
        f"{LANGS}/06-Compose 中的 Material Design 3（含 M3 Expressive）.md",
        [f"{DEVSITE}/develop/ui/compose/designsystems/material3?hl=zh-cn"],
        "官方 M3 主题体系（色彩角色/字阶/形状）与 M3 Expressive 实现说明",
    ),
    # === 03 Views 控件规范 ===
    (
        f"{VIEWS}/01-接入与目录结构.md",
        [
            f"{VIEWS_BASE}/getting-started.md",
            f"{VIEWS_BASE}/directorystructure.md",
            f"{MDC}/README.md",
        ],
        "MDC-Android 的接入方式、文档目录结构，以及仓库 README 中的维护模式公告",
    ),
    (
        f"{VIEWS}/02-颜色与深色模式.md",
        [f"{VIEWS_BASE}/theming/Color.md", f"{VIEWS_BASE}/theming/Dark.md"],
        "Views 侧的颜色角色、动态取色与深色模式规范",
    ),
    (
        f"{VIEWS}/03-字阶、形状与动效.md",
        [
            f"{VIEWS_BASE}/theming/Typography.md",
            f"{VIEWS_BASE}/theming/Shape.md",
            f"{VIEWS_BASE}/theming/Motion.md",
        ],
        "M3 字阶、形状与动效在 Views 体系中的 token 与属性",
    ),
    (
        f"{VIEWS}/04-应用栏 App bar（Views 官方指南）.md",
        [
            f"{DEVSITE}/develop/ui/views/components/appbar?hl=zh-cn",
            f"{DEVSITE}/develop/ui/views/components/appbar/setting-up?hl=zh-cn",
            f"{DEVSITE}/develop/ui/views/components/appbar/actions?hl=zh-cn",
            f"{DEVSITE}/develop/ui/views/components/appbar/up-action?hl=zh-cn",
            f"{DEVSITE}/develop/ui/views/components/appbar/action-views?hl=zh-cn",
        ],
        "Views 侧应用栏的官方用法：总览、接入 Toolbar、添加操作与向上导航",
    ),
    (
        f"{VIEWS}/05-按钮 Buttons.md",
        [
            f"{VIEWS_BASE}/components/Button.md",
            f"{VIEWS_BASE}/components/CommonButton.md",
            f"{VIEWS_BASE}/components/ButtonGroup.md",
            f"{VIEWS_BASE}/components/SplitButton.md",
            f"{VIEWS_BASE}/components/IconButton.md",
            f"{VIEWS_BASE}/components/FloatingActionButton.md",
            f"{VIEWS_BASE}/components/ExtendedFloatingActionButton.md",
            f"{VIEWS_BASE}/components/ToggleButtonGroup.md",
        ],
        "各类按钮的样式枚举、属性与用法（含 M3 Expressive 新增按钮族）",
    ),
    (
        f"{VIEWS}/06-卡片 Cards.md",
        [f"{VIEWS_BASE}/components/Card.md"],
        "卡片类型、属性与使用建议",
    ),
    (
        f"{VIEWS}/07-对话框 Dialog.md",
        [f"{VIEWS_BASE}/components/Dialog.md"],
        "MaterialAlertDialogBuilder 的用法与样式属性",
    ),
    (
        f"{VIEWS}/08-底部弹窗 BottomSheet.md",
        [f"{VIEWS_BASE}/components/BottomSheet.md"],
        "BottomSheetDialog / BottomSheetBehavior 的用法与状态",
    ),
    (
        f"{VIEWS}/09-选择控件 Switch、Checkbox、RadioButton.md",
        [
            f"{VIEWS_BASE}/components/Switch.md",
            f"{VIEWS_BASE}/components/Checkbox.md",
            f"{VIEWS_BASE}/components/RadioButton.md",
        ],
        "开关、复选、单选的样式与属性（含 M3 开关图形）",
    ),
    (
        f"{VIEWS}/10-文本输入 TextField.md",
        [f"{VIEWS_BASE}/components/TextField.md"],
        "TextInputLayout 的三种风格、辅文、错误态与图标",
    ),
    (
        f"{VIEWS}/11-列表 List.md",
        [f"{VIEWS_BASE}/components/List.md"],
        "M3 列表的构成、尺寸与用法",
    ),
    (
        f"{VIEWS}/12-顶栏与工具栏 TopAppBar 与 Toolbar.md",
        [
            f"{VIEWS_BASE}/components/TopAppBar.md",
            f"{VIEWS_BASE}/components/DockedToolbar.md",
            f"{VIEWS_BASE}/components/FloatingToolbar.md",
            f"{VIEWS_BASE}/components/DockedFloatingToolbars.md",
        ],
        "顶栏、停靠工具栏与浮动工具栏（M3 Expressive 新增）规范",
    ),
    (
        f"{VIEWS}/13-进度与加载指示.md",
        [
            f"{VIEWS_BASE}/components/ProgressIndicator.md",
            f"{VIEWS_BASE}/components/LoadingIndicator.md",
        ],
        "线性/圆形进度指示与 M3 Expressive 新增的加载指示器",
    ),
    (
        f"{VIEWS}/14-提示 Snackbar、Tooltip、Badge.md",
        [
            f"{VIEWS_BASE}/components/Snackbar.md",
            f"{VIEWS_BASE}/components/Tooltip.md",
            f"{VIEWS_BASE}/components/BadgeDrawable.md",
        ],
        "轻量提示、长按提示与角标",
    ),
    (
        f"{VIEWS}/15-标签页 Tabs 与 Chip.md",
        [f"{VIEWS_BASE}/components/Tabs.md", f"{VIEWS_BASE}/components/Chip.md"],
        "标签页与标签片（设置页若需分组筛选时使用）",
    ),
]

# Snapshotted through a rendering extractor because m3.material.io is a
# JavaScript app; listed so the manifest documents the full section.
RENDERED_ENTRIES: list[tuple[str, str, str]] = [
    (
        f"{LANGS}/01-M3 Expressive 是什么（官方发布说明）.md",
        "https://m3.material.io/blog/building-with-m3-expressive",
        "Material 3 Expressive 的官方发布说明",
    ),
    (
        f"{LANGS}/02-颜色 Color（M3 Expressive 色彩系统）.md",
        "https://m3.material.io/styles/color/system/overview",
        "M3 Expressive 色彩系统与颜色角色",
    ),
    (
        f"{LANGS}/03-字体 Typography（含强调字阶）.md",
        "https://m3.material.io/styles/typography/overview",
        "M3 Expressive 字阶与强调字阶（emphasized）",
    ),
    (
        f"{LANGS}/04-形状 Shape（形状库与形状变形）.md",
        "https://m3.material.io/styles/shape/overview",
        "M3 Expressive 形状库与形状变形动效",
    ),
    (
        f"{LANGS}/05-动效 Motion（弹簧动效系统）.md",
        "https://m3.material.io/styles/motion/overview",
        "M3 Expressive 弹簧物理动效系统",
    ),
    (
        f"{STATUS}/01-Material Android 转为 Compose 优先（Views 维护模式）.md",
        "https://m3.material.io/blog/material-is-compose-first",
        "官方公告：Views 库进入维护模式，1.14 为最后一个稳定版",
    ),
]


def build_converter() -> html2text.HTML2Text:
    conv = html2text.HTML2Text()
    conv.body_width = 0  # no hard wrapping: keep tables and long attribute rows
    conv.ignore_images = False
    conv.ignore_links = False
    conv.protect_links = True
    conv.unicode_snob = True
    conv.single_line_break = False
    conv.mark_code = True
    return conv


def output_path(out_name: str) -> str:
    """Resolve a manifest output name to a path.

    Only the FIRST ``/`` separates the subdirectory from the file name; any
    later slash would be a directory marker on Windows and is rejected so a
    stray separator fails loudly instead of creating a surprise tree.
    """
    subdir, _, filename = out_name.partition("/")
    if not _:
        return os.path.join(DOCS, SECTION, out_name)
    if "/" in filename:
        raise ValueError(f"output name must contain at most one '/': {out_name}")
    return os.path.join(DOCS, SECTION, subdir, filename)


def fetch(url: str) -> tuple[int, bytes, str]:
    req = urllib.request.Request(
        d(url),
        headers={
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        },
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.status, resp.read(), resp.geturl()


def extract_title(soup: BeautifulSoup) -> str:
    h1 = soup.find("h1")
    if h1 and h1.get_text(strip=True):
        return h1.get_text(" ", strip=True)
    if soup.title:
        return re.sub(
            r"\s*[|–-]\s*(Android Developers|Material Design.*)$",
            "",
            soup.title.get_text(strip=True),
        )
    return "Untitled"


def download_image(absolute: str, md_dir: str, counter: list[int]) -> str | None:
    """Fetch one image into the shared asset pool; return its path relative to
    the Markdown file being written, or None when the upstream link is dead."""
    parsed = urllib.parse.urlparse(absolute)
    name = os.path.basename(parsed.path) or "img"
    name = re.sub(r"[^A-Za-z0-9._-]", "_", name)[:80]
    digest = hashlib.md5(absolute.encode("utf-8")).hexdigest()[:8]
    host_dir = os.path.join(ASSETS, parsed.netloc)
    os.makedirs(host_dir, exist_ok=True)
    target = os.path.join(host_dir, f"{digest}-{name}")
    try:
        if not os.path.exists(target):
            _, blob, _ = fetch(absolute)
            with open(target, "wb") as fh:
                fh.write(blob)
    except Exception as exc:  # a dead vendor link must not fail the snapshot
        print(f"      ! image failed {absolute}: {exc}")
        return None
    counter[0] += 1
    return os.path.relpath(target, md_dir).replace(os.sep, "/")


def localize_images(soup: BeautifulSoup, page_url: str, md_dir: str, counter: list[int]) -> None:
    """Download <img> targets and rewrite them to relative paths."""
    for img in soup.find_all("img"):
        src = img.get("src") or img.get("data-src") or ""
        if src.startswith("data:"):
            img.decompose()
            continue
        if not src:
            continue
        rel = download_image(urllib.parse.urljoin(page_url, src), md_dir, counter)
        if rel is None:
            img.decompose()
            continue
        img["src"] = rel
        for attr in ("srcset", "data-src", "loading"):
            if img.has_attr(attr):
                del img[attr]


# Raw Markdown sources (the MDC-Android repo) mix HTML <img> tags with plain
# `![alt](path)` references, so the soup pass alone leaves the latter pointing at
# a relative path that does not exist next to the local file.
MD_IMAGE = re.compile(r"!\[([^\]]*)\]\(([^)\s]+)(\s+\"[^\"]*\")?\)")


def localize_markdown_images(text: str, source_url: str, md_dir: str, counter: list[int]) -> str:
    def replace(match: re.Match) -> str:
        alt, src, title = match.group(1), match.group(2), match.group(3) or ""
        if src.startswith(("http://", "https://", "data:")):
            return match.group(0)
        # The <img> pass runs first and has already rewritten those references to
        # a path inside the local asset pool; resolving them again against the
        # source URL would produce a URL that cannot exist.
        if "_assets/" in src:
            return match.group(0)
        absolute = urllib.parse.urljoin(source_url, src)
        rel = download_image(absolute, md_dir, counter)
        if rel is None:
            # Keep the link usable online rather than leaving a broken local path.
            return f"![{alt}]({absolute}{title})"
        return f"![{alt}]({rel}{title})"

    return MD_IMAGE.sub(replace, text)


def html_to_markdown(html: bytes, page_url: str, md_dir: str, counter: list[int]) -> tuple[str, str]:
    soup = BeautifulSoup(html, "lxml")
    title = extract_title(soup)
    if "developer.android.com" in page_url:
        node = soup.select_one(".devsite-article-body") or soup.select_one("article") or soup.body
    else:
        node = soup.select_one("article") or soup.select_one("main") or soup.body
    if node is None:
        return title, ""
    node = BeautifulSoup(str(node), "lxml")  # detach from the page chrome
    localize_images(node, page_url, md_dir, counter)
    text = build_converter().handle(str(node))
    text = re.sub(r"\n{4,}", "\n\n\n", text)
    text = re.sub(r"[ \t]+\n", "\n", text)
    return title, text.strip()


def write_snapshot(out_name: str, titles: list[str], parts: list[str], urls: list[str], note: str, images: int) -> dict:
    out_path = output_path(out_name)
    md_dir = os.path.dirname(out_path)
    os.makedirs(md_dir, exist_ok=True)

    heading = os.path.basename(out_name)[:-3]
    is_mdc = any("github" in u for u in urls)
    header = [
        f"# {heading}",
        "",
        "> 来源：Google 官方文档（"
        + ("Material Components for Android 官方仓库" if is_mdc else "Android Developers")
        + "）",
        "> 原文链接：" + " ｜ ".join(urls),
        f"> 快照时间：{TODAY}",
        f"> 说明：{note}。",
    ]
    if images:
        header.append(f"> 图片已本地化 {images} 张（`../_assets/`）。")
    content = "\n".join(header) + "\n\n---\n\n" + "\n\n---\n\n".join(parts) + "\n"
    with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(content)
    return {
        "dir": f"{SECTION}/{os.path.dirname(out_name)}".rstrip("/"),
        "file": os.path.basename(out_name),
        "title": titles[0] if len(titles) == 1 else heading,
        "urls": urls,
        "images": images,
        "bytes": len(content.encode("utf-8")),
    }


def snapshot(entry: tuple[str, list[str], str]) -> dict:
    out_name, urls, note = entry
    out_path = output_path(out_name)
    md_dir = os.path.dirname(out_path)

    counter = [0]
    parts: list[str] = []
    titles: list[str] = []
    for url in urls:
        status, blob, final_url = fetch(url)
        if status != 200:
            raise RuntimeError(f"HTTP {status} for {url}")
        title, body = html_to_markdown(blob, final_url, md_dir, counter)
        if not body:
            raise RuntimeError(f"no article body extracted from {url}")
        if "raw.githubusercontent.com" in url:
            body = localize_markdown_images(body, final_url, md_dir, counter)
        titles.append(title)
        if len(urls) > 1:
            parts.append(f"## 附：{title}\n\n> 原文链接：{url}\n\n{body}")
        else:
            parts.append(body)
    return write_snapshot(out_name, titles, parts, urls, note, counter[0])


def load_manifest() -> dict:
    if os.path.exists(MANIFEST):
        with open(MANIFEST, encoding="utf-8") as fh:
            return json.load(fh)
    return {"fetchedAt": TODAY, "source": "Android Developers", "ok": 0, "fail": 0, "docs": []}


def save_manifest(manifest: dict) -> None:
    with open(MANIFEST, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)
        fh.write("\n")


def register_rendered(records: list[dict]) -> None:
    """Record the JS-rendered snapshots (taken outside this script) so the
    manifest stays the single index of the whole docs tree."""
    manifest = load_manifest()
    fresh = {(r["dir"], r["file"]) for r in records}
    manifest["docs"] = [x for x in manifest["docs"] if (x.get("dir"), x.get("file")) not in fresh]
    manifest["docs"].extend(records)
    manifest["docs"].sort(key=lambda x: (x.get("dir", ""), x.get("file", "")))
    manifest["fetchedAt"] = TODAY
    manifest["ok"] = len(manifest["docs"])
    save_manifest(manifest)
    print(f"registered {len(records)} rendered snapshots, manifest now {manifest['ok']} entries")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", action="store_true", help="status only, write nothing")
    parser.add_argument("--force", action="store_true", help="refresh files that already exist")
    parser.add_argument("--only", default="", help="only entries whose output path contains this")
    args = parser.parse_args()

    results, failures = [], []
    for entry in MANIFEST_ENTRIES:
        out_name = entry[0]
        if args.only and args.only not in out_name:
            continue
        exists = os.path.exists(output_path(out_name))
        if args.probe:
            marks = []
            for url in entry[1]:
                try:
                    status, _, _ = fetch(url)
                except Exception as exc:
                    status = getattr(exc, "code", "ERR")
                marks.append(str(status))
            flag = "skip" if exists and not args.force else "FETCH"
            print(f"  {flag:5} {'/'.join(marks):9} {out_name}")
            continue
        if exists and not args.force:
            print(f"  skip  {out_name}")
            continue
        print(f"  fetch {out_name}")
        try:
            results.append(snapshot(entry))
            print(f"        ok  {results[-1]['bytes']} bytes, {results[-1]['images']} images")
        except Exception as exc:
            failures.append((out_name, str(exc)))
            print(f"        FAIL {exc}")

    if args.probe:
        return 0

    manifest = load_manifest()
    fresh = {(r["dir"], r["file"]) for r in results}
    manifest["docs"] = [x for x in manifest["docs"] if (x.get("dir"), x.get("file")) not in fresh]
    manifest["docs"].extend(results)
    manifest["docs"].sort(key=lambda x: (x.get("dir", ""), x.get("file", "")))
    manifest["fetchedAt"] = TODAY
    manifest["ok"] = len(manifest["docs"])
    manifest["fail"] = len(failures)
    save_manifest(manifest)

    print(f"\n{len(results)} written, {len(failures)} failed, manifest now {manifest['ok']} entries")
    for name, err in failures:
        print(f"  FAILED {name}: {err}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
