#!/usr/bin/env python3
# Copyright 2026, Kira contributors
# SPDX-License-Identifier: MIT
"""把萌娘百科条目更新进 App 内置知识库（app/src/main/assets/kb/）。

为什么需要这个脚本
------------------
Kiba Agent 的 `moegirl_kb` 工具读的是打包进 APK 的 `assets/kb/*.md`。条目内容会随
游戏版本变化（新版本、新机制、新术语），所以需要一个**可重复执行**的更新路径：
抓取 → 转 Markdown → 覆盖 assets → 抬版本号。跑完重新打包即可生效，不用改代码。

两种输入模式（萌娘百科有 Cloudflare，脚本不保证一定能直连）：

1. 直连抓取（默认，能连上就最省事）：
       python tools/update_moegirl_kb.py --fetch

2. 本地 HTML 快照（直连被拦时的稳妥做法）：
   浏览器打开三个条目 → 另存为 HTML（完整网页）到某个目录，命名随意，
   脚本按 manifest 里的 url 反查文件名（也支持 --html 单独指定）：
       python tools/update_moegirl_kb.py --html-dir tools/moegirl_src

其它参数
--------
    --kb-dir     覆盖输出目录（默认 app/src/main/assets/kb）
    --dry-run    只打印统计，不写文件
    --min-chars  单个条目最少字符数（低于视为抓取失败，默认 2000）
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import sys
import urllib.parse
import urllib.request
from datetime import date
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_KB_DIR = ROOT / "app" / "src" / "main" / "assets" / "kb"

# 条目清单：file 与 manifest.json 里的登记保持一致
SOURCES = [
    {
        "file": "maimai_dx.md",
        "title": "舞萌DX（maimai DX 中国大陆版）",
        "url": "https://zh.moegirl.org.cn/Maimai%E7%B3%BB%E5%88%97/%E8%88%9E%E8%90%8CDX",
        "page": "Maimai系列/舞萌DX",
    },
    {
        "file": "maimai_series.md",
        "title": "maimai（舞萌）系列",
        "url": "https://zh.moegirl.org.cn/Maimai%E7%B3%BB%E5%88%97",
        "page": "Maimai系列",
    },
    {
        "file": "chunithm.md",
        "title": "CHUNITHM（中二节奏）",
        "url": "https://zh.moegirl.org.cn/CHUNITHM",
        "page": "CHUNITHM",
    },
]

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

# 页面里这些块是导航 / 脚注 / 编辑按钮，全部丢掉
DROP_CLASS_HINTS = (
    "mw-editsection",
    "navbox",
    "nav-box",
    "reference",
    "mw-references",
    "toc",
    "catlinks",
    "printfooter",
    "noprint",
    "moe-infobox-nav",
    "sidebar",
    "mw-jump-link",
)


class WikiTextExtractor(HTMLParser):
    """把 MediaWiki 条目正文抽成 Markdown 风格纯文本。

    只处理对知识库有意义的结构：标题、段落、列表、表格行、粗体。
    链接只保留文字，图片/脚本/样式整体跳过。
    """

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.out: list[str] = []
        self.skip_depth = 0
        self.in_content = False
        self.content_depth = 0
        self.list_stack: list[str] = []
        self.row: list[str] | None = None

    # ---- 工具 ----

    def _startswith_class(self, attrs, hint: str) -> bool:
        for key, value in attrs:
            if key == "class" and value and hint in value:
                return True
        return False

    def _emit(self, text: str) -> None:
        if not self.in_content or self.skip_depth:
            return
        self.out.append(text)

    # ---- HTMLParser 回调 ----

    def handle_starttag(self, tag, attrs):
        attrs = attrs or []
        if tag in ("script", "style", "noscript"):
            self.skip_depth += 1
            return
        if self._startswith_class(attrs, "mw-parser-output"):
            self.in_content = True
            self.content_depth = 1
            return
        if self.in_content:
            self.content_depth += 1
        if self.skip_depth or not self.in_content:
            return
        if any(self._startswith_class(attrs, hint) for hint in DROP_CLASS_HINTS):
            self.skip_depth += 1
            return
        if tag in ("h2", "h3", "h4"):
            level = int(tag[1])
            self._emit("\n\n" + "#" * level + " ")
        elif tag in ("p", "div", "br"):
            self._emit("\n")
        elif tag == "li":
            indent = "  " * max(0, len(self.list_stack) - 1)
            self._emit(f"\n{indent}- ")
        elif tag in ("ul", "ol"):
            self.list_stack.append(tag)
        elif tag == "tr":
            self.row = []
        elif tag in ("td", "th"):
            if self.row is not None:
                self.row.append("")
        elif tag == "b":
            self._emit("**")
        elif tag == "table":
            self._emit("\n")

    def handle_endtag(self, tag):
        if tag in ("script", "style", "noscript"):
            self.skip_depth = max(0, self.skip_depth - 1)
            return
        if self.in_content:
            self.content_depth -= 1
            if self.content_depth <= 0:
                self.in_content = False
        if self.skip_depth:
            self.skip_depth = max(0, self.skip_depth - 1)
            return
        if tag in ("h2", "h3", "h4"):
            self._emit("\n")
        elif tag in ("ul", "ol"):
            if self.list_stack:
                self.list_stack.pop()
        elif tag == "tr" and self.row is not None:
            cells = [c.strip() for c in self.row if c.strip()]
            if cells:
                self._emit("\n| " + " | ".join(cells) + " |")
            self.row = None
        elif tag == "table":
            self._emit("\n")
        elif tag == "b":
            self._emit("**")

    def handle_data(self, data):
        if self.row is not None and self.in_content and not self.skip_depth:
            if self.row:
                self.row[-1] += data
            return
        self._emit(data)

    # ---- 输出 ----

    def markdown(self) -> str:
        text = "".join(self.out)
        text = html.unescape(text)
        # 压掉多余空行 / 行尾空格
        text = re.sub(r"[ \t]+\n", "\n", text)
        text = re.sub(r"\n{3,}", "\n\n", text)
        lines = [ln.rstrip() for ln in text.splitlines()]
        return "\n".join(lines).strip()


def extract(content: str) -> str:
    parser = WikiTextExtractor()
    parser.feed(content)
    return parser.markdown()


def fetch(url: str, timeout: int = 30) -> str:
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "zh-CN,zh;q=0.9",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read()
    return raw.decode("utf-8", errors="replace")


def find_snapshot(html_dir: Path, source: dict) -> Path | None:
    """在快照目录里按条目名 / 标题找对应的 HTML 文件。"""
    keys = [source["page"].replace("/", "_"), source["page"], source["title"].split("（")[0]]
    candidates = list(html_dir.glob("*.htm*"))
    for path in candidates:
        stem = path.stem
        for key in keys:
            if key and key in stem:
                return path
    return None


def build_doc(source: dict, body: str) -> str:
    header = (
        f"# {source['title']}\n\n"
        f"> 来源：萌娘百科《{source['page']}》 {source['url']}\n"
        f"> 抓取日期：{date.today().isoformat()} · 由 tools/update_moegirl_kb.py 自动生成\n\n"
    )
    return header + body.strip() + "\n"


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="更新 Kiba Agent 内置萌娘百科知识库")
    ap.add_argument("--fetch", action="store_true", help="直连抓取条目页面")
    ap.add_argument("--html-dir", type=Path, help="从本地 HTML 快照目录读取")
    ap.add_argument("--kb-dir", type=Path, default=DEFAULT_KB_DIR, help="输出目录")
    ap.add_argument("--dry-run", action="store_true", help="只打印，不写文件")
    ap.add_argument("--min-chars", type=int, default=2000, help="单个条目最少字符数")
    args = ap.parse_args(argv)

    if not args.fetch and not args.html_dir:
        ap.error("请指定 --fetch 或 --html-dir（二选一）")

    kb_dir: Path = args.kb_dir
    kb_dir.mkdir(parents=True, exist_ok=True)

    manifest_path = kb_dir / "manifest.json"
    manifest = {"version": 0, "generatedAt": "", "docs": []}
    if manifest_path.exists():
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            print("! 现有 manifest.json 解析失败，将重建", file=sys.stderr)

    results = []
    for source in SOURCES:
        label = source["file"]
        try:
            if args.html_dir:
                snapshot = find_snapshot(args.html_dir, source)
                if snapshot is None:
                    print(f"- {label}：快照目录里找不到对应 HTML，跳过")
                    continue
                raw = snapshot.read_text(encoding="utf-8", errors="replace")
                origin = f"snapshot:{snapshot.name}"
            else:
                raw = fetch(source["url"])
                origin = "fetch"
        except Exception as exc:  # noqa: BLE001 - 抓取失败要让用户看到原因
            print(f"! {label}：获取失败（{exc}）")
            continue

        body = extract(raw)
        doc = build_doc(source, body)
        if len(doc) < args.min_chars:
            print(
                f"! {label}：解析结果只有 {len(doc)} 字符（< {args.min_chars}），"
                f"多半被反爬页/空壳页替换了，跳过（来源 {origin}）"
            )
            continue

        sections = len(re.findall(r"^##\s", doc, flags=re.MULTILINE))
        digest = hashlib.sha256(doc.encode("utf-8")).hexdigest()[:16]
        results.append((source, doc, sections, digest, origin))
        print(f"+ {label}：{len(doc)} 字符 / {sections} 个二级小节 / sha256:{digest}（{origin}）")

    if not results:
        print("没有可写入的条目，manifest 保持不变。")
        return 1

    if not args.dry_run:
        for source, doc, _sections, _digest, _origin in results:
            (kb_dir / source["file"]).write_text(doc, encoding="utf-8")
        manifest["version"] = int(manifest.get("version", 0)) + 1
        manifest["generatedAt"] = date.today().isoformat()
        manifest["note"] = (
            "由 tools/update_moegirl_kb.py 生成；docs 数组里登记的 file 会被 App 载入知识库。"
        )
        manifest["docs"] = [
            {
                "file": source["file"],
                "title": source["title"].split("（")[0],
                "url": source["url"],
            }
            for source, *_ in results
        ]
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"\n已写入 {kb_dir}，manifest 版本抬到 v{manifest['version']}。")
        print("下一步：重新打包 APK（:app:assembleDebug / release）后生效。")
    else:
        print("\n[dry-run] 未写入任何文件。")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
