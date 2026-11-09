#!/usr/bin/env python3
"""Export MemeCrafters/meme-generator templates into Kiba Android assets.

Upstream: https://github.com/MemeCrafters/meme-generator

What this does
--------------
1. Fetch the upstream git tree (recursive) and every ``memes/<key>/__init__.py``.
2. Parse ``add_meme(...)`` metadata (text counts, keywords, defaults, images, gif).
3. Statically extract ``BuildImage.draw_text(...)`` calls into declarative text
   layers. Templates that need user-supplied images, perspective/rotation,
   multi-frame gifs or procedural rendering are still listed in the catalog,
   just marked ``renderable: false`` so the app can explain why.
4. Download the background image of every renderable template, convert it to
   WebP and write it to ``app/src/main/assets/meme/img/<key>.webp``.
5. Write ``app/src/main/assets/meme/templates.json`` (full catalog).

Templates whose Python code cannot be expressed declaratively but are worth
keeping get a hand-written entry in OVERRIDES / SPECIAL (high_EQ, 5000choyen).

Usage
-----
    python tools/meme_export.py                 # online, uses GH_TOKEN if set
    python tools/meme_export.py --cache DIR    # reuse a previous src/ + tree

The result is committed to the repo; this script is only needed when upstream
adds new memes. No runtime dependency on Python in the app.
"""

from __future__ import annotations

import argparse
import ast
import base64
import io
import concurrent.futures
import json
import os
import pathlib
import re
import sys
import time
import urllib.request

REPO = "MemeCrafters/meme-generator"
API = f"https://api.github.com/repos/{REPO}"
PREFIX = "meme_generator/memes/"
TOKEN = os.environ.get("GH_TOKEN", "")
HERE = pathlib.Path(__file__).resolve().parent
DEFAULT_OUT = HERE.parent / "app/src/main/assets/meme"

# animated memes are recorded by executing the upstream functions (see meme_anim)
ANIM_FRAME_CAP = 440
ANIM_WEBP_QUALITY = 82

# ── declarative overrides for templates whose code is not statically parseable ──
# rects / colours copied verbatim from upstream __init__.py.
SPECIAL = {
    "high_EQ": {
        "name": "低情商高情商",
        "aliases": ["高低情商", "低情商高情商", "eq"],
        "renderer": "generic",
        "background": "0.jpg",
        "layers": [
            {
                "text": "{0}", "rect": [40, 540, 602, 1140],
                "maxFont": 100, "minFont": 50, "wrap": True,
                "fill": "#FFFFFF", "strokeFill": "#000000", "strokeRatio": 0.05,
            },
            {
                "text": "{1}", "rect": [682, 540, 1244, 1140],
                "maxFont": 100, "minFont": 50, "wrap": True,
                "fill": "#FFFFFF", "strokeFill": "#000000", "strokeRatio": 0.05,
            },
        ],
    },
    "5000choyen": {
        "name": "gocho（5000兆円）",
        "aliases": ["gocho", "5000兆", "五千兆", "红银字"],
        # Kotlin implements the exact multi-layer gradient/stroke/skew pipeline.
        "renderer": "choyen",
        "background": "",
        "layers": [],
    },
}

# Upstream keywords are Chinese; a few keys need a friendlier display name.
NAME_FIX = {
    "high_EQ": "低情商高情商",
    "5000choyen": "gocho（5000兆円）",
    "nokia": "诺基亚砸字",
    "luxun_say": "鲁迅说过",
    "good_news": "喜报",
    "bad_news": "悲报",
    "pornhub": "Pornhub 风格",
    "google": "Google 搜索",
    "my_opinion": "我的意见如下",
    "dont_press": "不要按",
    "ascension": "升天",
    "certificate": "奖状",
    "find_chips": "找薯片",
    "charpic": "字符画",
    "slogan": "标语",
    "note_for_leave": "请假条",
    "steam_message": "Steam 消息",
    "wechat_pay": "微信支付",
    "alipay": "支付宝",
    "douyin": "抖音",
    "youtube": "YouTube",
    "loading": "加载中",
    "nokia": "诺基亚",
}

COLOR_NAMES = {
    "white": "#FFFFFF", "black": "#000000", "red": "#FF0000",
    "green": "#008000", "blue": "#0000FF", "yellow": "#FFFF00",
    "gray": "#808080", "grey": "#808080", "orange": "#FFA500",
    "pink": "#FFC0CB", "purple": "#800080", "gold": "#FFD700",
    "silver": "#C0C0C0", "brown": "#A52A2A",
}

# Operations that a static "background + text layers" renderer cannot express.
COMPLEX_CALLS = {
    "paste", "rotate", "perspective", "resize", "circle", "square", "round",
    "crop", "transpose", "flip", "filter", "motion_blur", "gaussian_blur",
    "colorize", "mask", "convert", "alpha_composite", "blend", "quantize",
    "draw_line", "draw_rectangle", "draw_arc", "draw_ellipse", "draw_polygon",
    "draw_point", "save_gif", "to_image", "seek", "gradient", "create_paint",
}
COMPLEX_NAMES = {"Text2Image", "LinearGradient", "ColorStop", "ImageSequence", "Transform"}

_UNRESOLVED = object()


class Resolver:
    """Evaluate the tiny arithmetic subset used by upstream draw_text rects."""

    def __init__(self, width: int, height: int, variables: dict):
        self.width = width
        self.height = height
        self.variables = variables

    def val(self, node):
        if isinstance(node, ast.Constant):
            return node.value
        if isinstance(node, (ast.Tuple, ast.List)):
            return [self.val(e) for e in node.elts]
        if isinstance(node, ast.BinOp):
            a, b = self.val(node.left), self.val(node.right)
            if a is _UNRESOLVED or b is _UNRESOLVED:
                return _UNRESOLVED
            try:
                if isinstance(node.op, ast.Add):
                    return a + b
                if isinstance(node.op, ast.Sub):
                    return a - b
                if isinstance(node.op, ast.Mult):
                    return a * b
                if isinstance(node.op, ast.Div):
                    return a / b
                if isinstance(node.op, ast.FloorDiv):
                    return a // b
            except (ArithmeticError, TypeError, ValueError):
                return _UNRESOLVED
            return _UNRESOLVED
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
            v = self.val(node.operand)
            return -v if v is not _UNRESOLVED else _UNRESOLVED
        if isinstance(node, ast.Attribute):
            if node.attr == "width":
                return self.width
            if node.attr == "height":
                return self.height
            return _UNRESOLVED
        if isinstance(node, ast.Name):
            return self.variables.get(node.id, _UNRESOLVED)
        if isinstance(node, ast.Subscript):
            return self.text(node)
        if isinstance(node, ast.Call):
            # e.g. texts[0].strip() / str(...)
            if isinstance(node.func, ast.Attribute) and node.func.attr in {
                "strip", "replace", "upper", "lower", "format"
            }:
                return self.text(node.func.value)
            return _UNRESOLVED
        return _UNRESOLVED

    def text(self, node):
        """Turn a text expression into a '{0}' placeholder template string."""
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            return node.value
        if isinstance(node, ast.IfExp):
            # e.g. texts[3] if len(texts) >= 4 else "fixed caption"
            return self.text(node.body)
        if isinstance(node, ast.JoinedStr):
            out = []
            for part in node.values:
                if isinstance(part, ast.Constant) and isinstance(part.value, str):
                    out.append(part.value)
                elif isinstance(part, ast.FormattedValue):
                    v = self.text(part.value)
                    if v is _UNRESOLVED:
                        return _UNRESOLVED
                    out.append(v)
                else:
                    return _UNRESOLVED
            return "".join(out)
        if isinstance(node, ast.Subscript):
            # texts[0] / text_list[1]
            if isinstance(node.value, ast.Name) and "text" in node.value.id.lower():
                idx = self.val(node.slice)
                if isinstance(idx, int):
                    return "{%d}" % idx
            return _UNRESOLVED
        if isinstance(node, ast.Name):
            return self.variables.get(node.id, _UNRESOLVED)
        if isinstance(node, ast.Call):
            if isinstance(node.func, ast.Attribute) and node.func.attr in {
                "strip", "replace", "upper", "lower"
            }:
                return self.text(node.func.value)
            if isinstance(node.func, ast.Name) and node.func.id in {"str", "f"}:
                return self.text(node.args[0]) if node.args else _UNRESOLVED
            return _UNRESOLVED
        if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Call):
            return self.text(node.value)
        return _UNRESOLVED


def http_json(url: str):
    last = None
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={
                "Authorization": f"Bearer {TOKEN}" if TOKEN else "",
                "Accept": "application/vnd.github+json",
                "User-Agent": "kiba-meme-export",
            })
            with urllib.request.urlopen(req, timeout=40) as r:
                return json.loads(r.read())
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(0.6 * (attempt + 1))
    raise RuntimeError(f"GET failed {url}: {last}")


def fetch_blob(sha: str) -> bytes:
    data = http_json(f"{API}/git/blobs/{sha}")
    return base64.b64decode(data["content"])


def literal(node, default=None):
    try:
        return ast.literal_eval(node)
    except Exception:  # noqa: BLE001
        return default


def parse_add_meme(tree: ast.Module):
    meta = {}
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        fn = node.func
        if not (isinstance(fn, ast.Name) and fn.id == "add_meme"):
            continue
        for kw in node.keywords:
            if kw.arg in {
                "min_texts", "max_texts", "min_images", "max_images",
                "default_texts", "keywords", "tags",
            }:
                v = literal(kw.value)
                if v is not None:
                    meta[kw.arg] = v
        meta["_shortcuts"] = "shortcuts" in {kw.arg for kw in node.keywords}
        break
    return meta


def analyse_source(key: str, code: str):
    tree = ast.parse(code)
    meta = parse_add_meme(tree)
    fn = next(
        (n for n in tree.body if isinstance(n, ast.FunctionDef)
         and any(a.arg == "texts" for a in n.args.args)),
        None,
    )
    info = {
        "key": key,
        "minTexts": int(meta.get("min_texts") or 0),
        "maxTexts": int(meta.get("max_texts") or 0),
        "minImages": int(meta.get("min_images") or 0),
        "keywords": [str(x) for x in (meta.get("keywords") or [])][:8],
        "defaultTexts": [str(x) for x in (meta.get("default_texts") or [])][:6],
        "shortcuts": bool(meta.get("_shortcuts")),
        "animated": "save_gif" in code or "ImageSequence" in code,
        "needsImage": "images[" in code or int(meta.get("min_images") or 0) > 0,
        "complex": False,
        "bg": "",
        "draws": [],
        "variables": {},
    }
    # background: BuildImage.open(img_dir / "0.png"), possibly via an
    # intermediate variable (img_path = img_dir / "0.png").
    m = re.search(r'img_dir\s*/\s*"([^"]+\.(?:png|jpe?g|webp))"', code)
    if m:
        info["bg"] = m.group(1)
    if fn is None:
        info["complex"] = True
        return info
    for node in ast.walk(fn):
        if isinstance(node, (ast.For, ast.While, ast.ListComp)):
            info["complex"] = True
        if isinstance(node, ast.Call):
            name = node.func.attr if isinstance(node.func, ast.Attribute) else (
                node.func.id if isinstance(node.func, ast.Name) else ""
            )
            if name in COMPLEX_CALLS or name in COMPLEX_NAMES:
                info["complex"] = True
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            # text = texts[0] / text = f"...{texts[0]}..."
            r = Resolver(0, 0, info["variables"])
            v = r.text(node.value)
            if v is not _UNRESOLVED and isinstance(v, str):
                info["variables"][node.targets[0].id] = v
            else:
                n = r.val(node.value)
                if n is not _UNRESOLVED and isinstance(n, (int, float, list, tuple)):
                    info["variables"][node.targets[0].id] = n
    return info


def color(value, default="#000000"):
    if isinstance(value, str):
        if value.startswith("#"):
            return value.upper()
        return COLOR_NAMES.get(value.lower(), default)
    if isinstance(value, (tuple, list)) and len(value) >= 3:
        try:
            return "#%02X%02X%02X" % tuple(int(v) for v in value[:3])
        except Exception:  # noqa: BLE001
            pass
    return default


def build_layers(info, width, height, code):
    """Extract draw_text calls, including those hidden behind a nested helper.

    Upstream templates frequently wrap the drawing in
    ``def draw(pos, text): frame.draw_text(pos, text, ...)`` and then call
    ``draw((x1,y1,x2,y2), texts[0])``. Both shapes are normalised here.
    """
    tree = ast.parse(code)
    fn = next(
        (n for n in tree.body if isinstance(n, ast.FunctionDef)
         and any(a.arg == "texts" for a in n.args.args)),
        None,
    )
    if fn is None:
        return None
    resolver = Resolver(width, height, info["variables"])

    helpers = {}
    helper_draws = set()
    for node in ast.walk(fn):
        if not isinstance(node, ast.FunctionDef) or node is fn:
            continue
        for sub in ast.walk(node):
            if (isinstance(sub, ast.Call) and isinstance(sub.func, ast.Attribute)
                    and sub.func.attr == "draw_text"):
                helpers[node.name] = (
                    [a.arg for a in node.args.args],
                    {k.arg: k.value for k in sub.keywords if k.arg},
                )
                helper_draws.add(id(sub))
                break

    found = []
    for node in ast.walk(fn):
        if not isinstance(node, ast.Call) or len(node.args) < 2:
            continue
        f = node.func
        kw = None
        if isinstance(f, ast.Attribute) and f.attr == "draw_text":
            if id(node) in helper_draws:
                continue  # parameterised body, handled via helper call sites
            kw = {k.arg: k.value for k in node.keywords if k.arg}
        elif isinstance(f, ast.Name) and f.id in helpers:
            params, inner = helpers[f.id]
            kw = dict(inner)
            for pname, pval in zip(params, node.args):
                if pname in ("align", "lines_align", "halign"):
                    kw["halign"] = pval
                elif pname == "valign":
                    kw["valign"] = pval
        if kw is None:
            continue
        rect = resolver.val(node.args[0])
        text = resolver.text(node.args[1])
        if (rect is _UNRESOLVED or text is _UNRESOLVED or not isinstance(text, str)
                or not isinstance(rect, (tuple, list)) or len(rect) not in (2, 4)):
            # Non-text decoration (e.g. a timestamp) — keep the rest of the image.
            continue
        if any(v is _UNRESOLVED or not isinstance(v, (int, float)) for v in rect):
            continue

        def num(name, default=None):
            if name not in kw:
                return default
            v = resolver.val(kw[name])
            return None if v is _UNRESOLVED else v

        def strv(name, default=None):
            v = num(name, default)
            return v if isinstance(v, str) else default

        fill = color(resolver.val(kw["fill"]) if "fill" in kw else None)
        stroke_fill = color(
            resolver.val(kw["stroke_fill"]) if "stroke_fill" in kw else None,
            default="#000000",
        )
        stroke_ratio = num("stroke_ratio", 0.0) or 0.0
        max_font = num("max_fontsize", 60)
        min_font = num("min_fontsize", 20)
        fixed_size = num("font_size", None) or num("fontsize", None)
        found.append((node.lineno, {
            "text": text,
            "rect": [int(v) for v in rect],
            "maxFont": float(max_font or 60),
            "minFont": float(min_font or 12),
            "wrap": bool(num("allow_wrap", False)),
            "fill": fill,
            "strokeFill": stroke_fill,
            "strokeRatio": float(stroke_ratio),
            "align": strv("lines_align", strv("halign", "center")) or "center",
            "valign": strv("valign", "center") or "center",
            "bold": strv("font_style", "") == "bold",
            "fontSize": float(fixed_size) if fixed_size else 0.0,
            "rotate": float(num("rotate", 0) or 0),
        }))
    found.sort(key=lambda p: p[0])
    return [layer for _, layer in found] or None


def chain_info(node, assigns, seen=None):
    """Parse ``images[0].convert(...).rotate(...).resize(...)`` chains."""
    seen = seen or set()
    if isinstance(node, ast.Name) and node.id in assigns and node.id not in seen:
        return chain_info(assigns[node.id], assigns, seen | {node.id})
    info = {"index": 0, "w": 0, "h": 0, "rotate": 0.0, "circle": False, "square": False}
    cur = node
    steps = []
    while isinstance(cur, ast.Call):
        f = cur.func
        name = f.attr if isinstance(f, ast.Attribute) else (
            f.id if isinstance(f, ast.Name) else ""
        )
        steps.append((name, cur))
        cur = f.value if isinstance(f, ast.Attribute) else None
    if isinstance(cur, ast.Subscript) and isinstance(cur.value, ast.Name) and "image" in cur.value.id:
        idx = cur.slice
        info["index"] = idx.value if isinstance(idx, ast.Constant) and isinstance(idx.value, int) else 0
    elif isinstance(cur, ast.Name) and cur.id in assigns and cur.id not in seen:
        sub = chain_info(assigns[cur.id], assigns, seen | {cur.id})
        if sub:
            info.update(sub)
        if info["index"] < 0:
            return None
    else:
        return None
    for name, call in reversed(steps):
        if name == "resize" and call.args:
            try:
                v = literal(call.args[0])
            except Exception:  # noqa: BLE001
                v = None
            if isinstance(v, (tuple, list)) and len(v) == 2:
                info["w"], info["h"] = int(v[0]), int(v[1])
        elif name == "rotate" and call.args:
            v = literal(call.args[0])
            if isinstance(v, (int, float)):
                info["rotate"] = float(v)
        elif name == "circle":
            info["circle"] = True
        elif name == "square":
            info["square"] = True
        elif name not in {"convert", "resize", "rotate", "circle", "square", "round", "copy"}:
            return None
    return info if (info["w"] or info["h"] or info["rotate"] or info["circle"]) else None


def extract_image_layers(code):
    """Single-frame templates that paste the user's image onto a background.

    Returns (background filename, image layers) or None. Animated templates
    (loops / frame lists / gif output) and perspective-style compositions are
    deliberately excluded: the Android renderer cannot reproduce them.
    """
    tree = ast.parse(code)
    fn = next(
        (n for n in tree.body if isinstance(n, ast.FunctionDef)
         and any(a.arg == "images" for a in n.args.args)),
        None,
    )
    if fn is None or "save_gif" in code or "frames" in code:
        return None
    if any(isinstance(n, (ast.For, ast.While)) for n in ast.walk(fn)):
        return None
    assigns = {
        n.targets[0].id: n.value for n in ast.walk(fn)
        if isinstance(n, ast.Assign) and len(n.targets) == 1
        and isinstance(n.targets[0], ast.Name)
    }
    pastes = [
        n for n in ast.walk(fn)
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute)
        and n.func.attr == "paste"
    ]
    if not pastes:
        return None
    layers = []
    for p in pastes:
        if len(p.args) < 2:
            return None
        ci = chain_info(p.args[0], assigns)
        pos = literal(p.args[1])
        if ci is None or not isinstance(pos, (tuple, list)) or len(pos) != 2:
            return None
        layers.append({
            "index": ci["index"], "w": ci["w"], "h": ci["h"],
            "rotate": ci["rotate"], "circle": ci["circle"],
            "square": ci["square"], "x": int(pos[0]), "y": int(pos[1]),
        })
    m = re.search(r'BuildImage\.open\(\s*img_dir\s*/\s*"([^"]+)"', code)
    if not m:
        return None
    return m.group(1), layers


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", type=pathlib.Path, help="dir with tree.json + src/*.py")
    ap.add_argument("--out", type=pathlib.Path, default=DEFAULT_OUT)
    args = ap.parse_args()
    out = args.out.resolve()
    cache = args.cache
    if cache:
        tree_data = json.loads((cache / "tree.json").read_text(encoding="utf-8"))["tree"]
        sources = {p.stem: p.read_text(encoding="utf-8") for p in (cache / "src").glob("*.py")}
    else:
        tree_data = http_json(f"{API}/git/trees/main?recursive=1")["tree"]
        blobs = {
            x["path"][len(PREFIX):].split("/")[0]: x["sha"]
            for x in tree_data
            if x["type"] == "blob" and x["path"].startswith(PREFIX)
            and x["path"].endswith("/__init__.py")
        }
        print("fetching", len(blobs), "template sources ...")
        sources = {}
        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
            futs = {pool.submit(fetch_blob, s): k for k, s in blobs.items()}
            for f in concurrent.futures.as_completed(futs):
                try:
                    sources[futs[f]] = f.result().decode("utf-8")
                except Exception as e:  # noqa: BLE001
                    print("  failed", futs[f], e, file=sys.stderr)

    # resource index: key -> [(filename, sha, size)]
    resources = {}
    for x in tree_data:
        p = x["path"]
        if x["type"] != "blob" or not p.startswith(PREFIX) or "/images/" not in p:
            continue
        key = p[len(PREFIX):].split("/")[0]
        resources.setdefault(key, []).append((p.rsplit("/", 1)[1], x["sha"], x.get("size", 0)))

    catalog = []
    downloads = []  # (key, filename, sha)
    for key in sorted(sources):
        code = sources[key]
        info = analyse_source(key, code)
        special = SPECIAL.get(key)
        name = (special or {}).get("name") or NAME_FIX.get(key) or (
            info["keywords"][0] if info["keywords"] else key
        )
        aliases = list(dict.fromkeys(
            [key] + info["keywords"] + (special or {}).get("aliases", [])
        ))[:12]
        entry = {
            "key": key,
            "name": name,
            "aliases": aliases,
            "minTexts": info["minTexts"],
            "maxTexts": info["maxTexts"],
            "defaultTexts": info["defaultTexts"],
            "needsImage": bool(info["needsImage"]),
            "animated": bool(info["animated"]),
            "renderable": False,
            "renderer": "generic",
            "background": "",
            "width": 0,
            "height": 0,
            "layers": [],
            "imageLayers": [],
        }
        if special:
            entry["renderer"] = special["renderer"]
            if special.get("background"):
                imgs = resources.get(key, [])
                hit = next((i for i in imgs if i[0] == special["background"]), None)
                if hit:
                    downloads.append((key, hit[0], hit[1]))
                    entry["background"] = f"img/{key}.webp"
            entry["renderable"] = True
            entry["_special_layers"] = special["layers"]
        elif (
            info["bg"] and not info["complex"] and not info["needsImage"]
            and not info["animated"] and info["maxTexts"] > 0
        ):
            imgs = resources.get(key, [])
            hit = next((i for i in imgs if i[0] == info["bg"]), None)
            if hit:
                downloads.append((key, hit[0], hit[1]))
                entry["background"] = f"img/{key}.webp"
                entry["_code"] = code
        elif info["needsImage"] and not info["animated"]:
            # Single-frame "paste user's image onto template" memes.
            got = extract_image_layers(code)
            if got:
                bgname, image_layers = got
                hit = next((i for i in resources.get(key, []) if i[0] == bgname), None)
                if hit:
                    downloads.append((key, hit[0], hit[1]))
                    entry["background"] = f"img/{key}.webp"
                    entry["_image_layers"] = image_layers
                    entry["_code"] = code
        catalog.append(entry)

    # download images, convert to WebP, then resolve rects needing width/height
    img_dir = out / "img"
    img_dir.mkdir(parents=True, exist_ok=True)
    print("downloading", len(downloads), "background images ...")
    from PIL import Image
    import io

    def fetch_image(item):
        key, filename, sha = item
        raw = fetch_blob(sha)
        im = Image.open(io.BytesIO(raw))
        im.load()
        original_w = im.width
        if im.width > 1600 or im.height > 1600:
            im.thumbnail((1600, 1600), Image.LANCZOS)
        scale = im.width / original_w if original_w else 1.0
        rgba = im.convert("RGBA") if "A" in im.getbands() or im.mode == "P" else im.convert("RGB")
        buf = io.BytesIO()
        rgba.save(buf, "WEBP", quality=88, method=6)
        (img_dir / f"{key}.webp").write_bytes(buf.getvalue())
        return key, rgba.width, rgba.height, scale, len(buf.getvalue())

    dims = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        futs = {pool.submit(fetch_image, it): it[0] for it in downloads}
        for f in concurrent.futures.as_completed(futs):
            try:
                key, w, h, scale, n = f.result()
                dims[key] = (w, h, scale, n)
            except Exception as e:  # noqa: BLE001
                print("  image failed", futs[f], e, file=sys.stderr)

    for e in catalog:
        key = e["key"]
        if key not in dims:
            if not SPECIAL.get(key) or SPECIAL[key].get("background"):
                e["renderable"] = False
                e["background"] = ""
            continue
        w, h, scale, _ = dims[key]
        e["width"], e["height"] = w, h
        special_layers = e.pop("_special_layers", None)
        if special_layers is not None:
            defaults = {
                "wrap": False, "align": "center", "valign": "center",
                "bold": False, "fontSize": 0.0, "rotate": 0.0,
                "strokeFill": "#000000", "strokeRatio": 0.0,
                "minFont": 12.0, "maxFont": 60.0,
            }
            special_layers = [{**defaults, **l} for l in special_layers]
        code = e.pop("_code", None)
        image_layers = e.pop("_image_layers", [])
        layers = special_layers
        if layers is None and code:
            info = analyse_source(key, code)
            layers = build_layers(info, w, h, code)
        if layers is None:
            layers = []
        if not layers and not image_layers and e["renderer"] != "choyen":
            e["renderable"] = False
            e["background"] = ""
        else:
            e["renderable"] = True
            if scale != 1.0:
                for l in layers:
                    l["rect"] = [int(v * scale) for v in l["rect"]]
                    l["maxFont"] *= scale
                    l["minFont"] *= scale
                for l in image_layers:
                    for k in ("w", "h", "x", "y"):
                        l[k] = int(l[k] * scale)
            e["layers"] = layers
            e["imageLayers"] = image_layers

    for e in catalog:
        e.pop("_special_layers", None)
        e.pop("_code", None)

    # ── animated memes: execute upstream code and record per-frame ops ──────
    catalog = record_animated(catalog, sources, resources, out, cache)

    # 真不支持的模板直接移出目录（不在 App 里灰显刷屏）
    dropped = [e["key"] for e in catalog if not e["renderable"]]
    catalog = [e for e in catalog if e["renderable"]]

    renderable = catalog
    out.mkdir(parents=True, exist_ok=True)
    (out / "templates.json").write_text(
        json.dumps({"version": 2, "source": REPO, "templates": catalog},
                   ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    anim_count = sum(1 for e in catalog if e.get("anim"))
    print(f"catalog: {len(catalog)} templates ({anim_count} animated), dropped {len(dropped)}")
    print(f"assets: {sum(d[3] for d in dims.values()) / 1024 / 1024:.2f} MB webp")
    print("dropped:", dropped)
    print(json.dumps([e["key"] for e in renderable], ensure_ascii=False))


def record_animated(catalog, sources, resources, out, cache=None):
    """Fill in frame scripts for animated templates that the recorder can replay."""
    import meme_anim
    import tempfile
    from PIL import Image

    tmp_root = (cache / "anim_frames") if cache else (pathlib.Path(tempfile.gettempdir()) / "kiba_meme_anim")
    anim_dir = out / "anim"
    keys = [e["key"] for e in catalog if e["animated"] and not e["renderable"]]
    if not keys:
        return catalog
    print(f"recording {len(keys)} animated templates ...")

    by_key = {e["key"]: e for e in catalog}
    for key in keys:
        entry = by_key[key]
        files = resources.get(key, [])
        if not files:
            continue
        frames_dir = tmp_root / key / "images"
        frames_dir.mkdir(parents=True, exist_ok=True)
        raw = {}
        for name, sha, _size in files:
            target = frames_dir / name
            if target.is_file():
                raw[name] = target.read_bytes()
                continue
            try:
                data = fetch_blob(sha)
            except Exception as exc:  # noqa: BLE001
                print("  frame failed", key, name, exc, file=sys.stderr)
                continue
            target.write_bytes(data)
            raw[name] = data
        if not raw:
            continue
        info = analyse_source(key, sources[key])
        script = meme_anim.record(
            key, sources[key], frames_dir,
            user_image_count=max(1, info["minImages"]),
            text_count=max(1, info["minTexts"]),
        )
        if script is None:
            continue
        # rename every referenced frame asset to .webp and emit the files
        used = set()

        def rename(node):
            if isinstance(node, dict):
                for k, v in list(node.items()):
                    if k == "file" and isinstance(v, str):
                        new = pathlib.PurePath(v).stem + ".webp"
                        node[k] = new
                        used.add(v)
                    else:
                        rename(v)
            elif isinstance(node, list):
                for v in node:
                    rename(v)

        rename(script)
        target_dir = anim_dir / key
        target_dir.mkdir(parents=True, exist_ok=True)
        wrote = 0
        for name in used:
            data = raw.get(name)
            if data is None:
                continue
            webp_target = target_dir / (pathlib.PurePath(name).stem + ".webp")
            if webp_target.is_file():
                wrote += 1
                continue
            try:
                im = Image.open(io.BytesIO(data))
                im.load()
                if max(im.width, im.height) > ANIM_FRAME_CAP:
                    im.thumbnail((ANIM_FRAME_CAP, ANIM_FRAME_CAP), Image.LANCZOS)
                rgba = im.convert("RGBA") if ("A" in im.getbands() or im.mode == "P") else im.convert("RGB")
                buf = io.BytesIO()
                rgba.save(buf, "WEBP", quality=ANIM_WEBP_QUALITY, method=6)
                (target_dir / (pathlib.PurePath(name).stem + ".webp")).write_bytes(buf.getvalue())
                wrote += 1
            except Exception as exc:  # noqa: BLE001
                print("  frame convert failed", key, name, exc, file=sys.stderr)
        if wrote == 0:
            continue
        entry["renderable"] = True
        entry["animated"] = True
        entry["anim"] = script
        entry["width"] = script["width"]
        entry["height"] = script["height"]
        print(f"  {key}: {len(script['frames'])} frames "
              f"{script['width']}x{script['height']} @{script['delayMs']}ms")
    return catalog


if __name__ == "__main__":
    main()
