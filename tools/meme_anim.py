#!/usr/bin/env python3
"""Record upstream *animated* memes into replayable per-frame scripts.

Why this exists
---------------
The static exporter can only read ``draw_text`` calls. Animated memes build a
PIL frame list in a loop, so the only faithful way to learn their geometry is
to *run* the upstream function and watch every operation it performs.

We do exactly that, locally, at export time:

1. Stub ``meme_generator`` / ``arclet`` / ``pydantic`` so the template module
   imports without pulling the whole upstream stack.
2. Execute the module with ``BuildImage`` methods patched: every call records
   ``(op, args)`` and tags the returned object with a provenance node
   (``user image`` / ``text layer`` / ``static frame`` / ``canvas composite``).
3. Call the meme function with dummy images and text. ``save_gif`` hands us the
   frame list; each frame's provenance already describes how it was built.
4. Keep only templates whose every operation maps to the Kotlin primitive set:
   square / circle / resize / rotate / crop / paste / static frames / text.

Anything else (perspective, pixel math, random, no static frames) is reported
as unsupported, and the caller drops it from the app catalog entirely.

The emitted JSON is consumed by ``core/meme/MemeAnimRenderer.kt``.
"""

from __future__ import annotations

import ast
import io
import json
import pathlib
import sys
import types

from PIL import Image

# ── supported transform vocabulary ───────────────────────────────────────────
TX_OPS = {"square", "circle", "resize", "rotate", "crop"}
MAX_FRAMES = 72
MAX_SIDE = 520

_UNSUPPORTED = object()


# ── stub modules: let template modules import without the real upstream ───────
class _Any:
    """Permissive stand-in: any attribute access / call returns another stub."""

    def __init__(self, *a, **k):
        pass

    def __getattr__(self, name):
        return _Any()

    def __call__(self, *a, **k):
        return _Any()

    def __iter__(self):
        return iter(())

    def __bool__(self):
        return False


class _StubModule(types.ModuleType):
    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        return _Any


_STUB_NAMES = [
    "meme_generator", "meme_generator.utils", "meme_generator.exception",
    "meme_generator.tags", "meme_generator.database", "meme_generator.manager",
    "arclet", "arclet.alconna", "arclet.alconna.tools", "pydantic",
    "typing_extensions",
]


def install_stubs(capture: dict):
    for name in _STUB_NAMES:
        if name in sys.modules:
            continue
        mod = _StubModule(name)
        sys.modules[name] = mod
    sys.modules["meme_generator.exception"].TextOverLength = type(
        "TextOverLength", (Exception,), {}
    )
    sys.modules["meme_generator"].MemeArgsModel = _Any
    sys.modules["meme_generator"].MemeArgsType = _Any
    sys.modules["meme_generator"].ParserOption = _Any
    sys.modules["meme_generator"].add_meme = lambda *a, **k: None

    def save_gif(frames, duration=0.1, **k):
        capture["frames"] = list(frames)
        capture["delay"] = float(duration or 0.1)
        return capture["frames"]

    sys.modules["meme_generator.utils"].save_gif = save_gif


# ── provenance helpers ───────────────────────────────────────────────────────
def _basename(p) -> str:
    return pathlib.PurePath(str(p)).name


class Recorder:
    """Patch pil_utils.BuildImage / Text2Image to record ops + provenance."""

    def __init__(self, frame_names: set[str]):
        self.prov: dict[int, dict] = {}
        self.frame_names = frame_names
        self.user_seq = 0
        self.text_seq = 0
        self.used_files: set[str] = set()
        self.unsupported: str | None = None
        self.patched: list[tuple[object, str, object]] = []
        # >0 while an already-recorded call runs its own implementation; nested
        # calls made *by pil_utils itself* (square() internally crops + pastes,
        # resize() internally resamples) must not be recorded again.
        self.busy = 0

    # provenance plumbing ---------------------------------------------------
    def tag(self, obj, node: dict):
        self.prov[id(obj)] = node
        img = getattr(obj, "_image", None) or getattr(obj, "image", None)
        if img is not None:
            self.prov[id(img)] = node

    def node_of(self, obj):
        return self.prov.get(id(obj))

    def fail(self, why: str):
        if self.unsupported is None:
            self.unsupported = why
        return _UNSUPPORTED

    def _tx(self, obj) -> list:
        node = self.node_of(obj)
        return node.setdefault("tx", []) if node else []

    # patching --------------------------------------------------------------
    def patch(self, cls, name, handler, prop=False):
        if prop:
            owner = next((c for c in cls.__mro__ if name in vars(c)), None)
            if owner is None:
                return
            orig = vars(owner)[name]
            if not isinstance(orig, property):
                return

            def getter(self_, _orig=orig.fget, _h=handler):
                val = _orig(self_)
                _h(self_, val)
                return val

            setattr(owner, name, property(getter))
            self.patched.append((owner, name, orig))
            return
        owner = next((c for c in cls.__mro__ if name in vars(c)), None)
        if owner is None:
            return
        raw = vars(owner)[name]
        if isinstance(raw, classmethod):

            def inner(cls_, *a, _orig=raw.__func__, _h=handler, **kw):
                self.busy += 1
                try:
                    res = _orig(cls_, *a, **kw)
                finally:
                    self.busy -= 1
                if self.busy == 0:
                    _h(cls_, res, a, kw)
                return res

            setattr(owner, name, classmethod(inner))
            self.patched.append((owner, name, raw))
            return
        if not callable(raw):
            return

        def inner(self_, *a, _orig=raw, _h=handler, **kw):
            self.busy += 1
            try:
                res = _orig(self_, *a, **kw)
            finally:
                self.busy -= 1
            if self.busy == 0:
                _h(self_, res, a, kw)
            return res

        setattr(owner, name, inner)
        self.patched.append((owner, name, raw))

    def unpatch(self):
        for cls, name, orig in reversed(self.patched):
            setattr(cls, name, orig)
        self.patched.clear()
        if getattr(self, "patched_ga", None) is not None:
            from pil_utils import BuildImage
            BuildImage.__getattribute__ = self.patched_ga
            self.patched_ga = None

    def install(self):
        from pil_utils import BuildImage, Text2Image

        # ``frame.image`` is a plain dataclass attribute, so the only way to
        # learn which PIL image came from which BuildImage is to intercept the
        # attribute read. Templates always go through it when collecting frames.
        rec = self
        orig_ga = BuildImage.__getattribute__

        def ga(self_, name, _orig=orig_ga):
            val = _orig(self_, name)
            if name == "image":
                node = rec.prov.get(id(self_))
                if node is not None:
                    rec.prov[id(val)] = node
            return val

        BuildImage.__getattribute__ = ga
        self.patched_ga = orig_ga

        # -- construction ---------------------------------------------------
        self.patch(BuildImage, "open", self._on_open)
        self.patch(BuildImage, "new", self._on_new)

        # -- geometry -------------------------------------------------------
        for name, op in (("square", "square"), ("circle", "circle")):
            self.patch(
                BuildImage, name,
                lambda self_, res, a, kw, _op=op: self._derive(self_, res, {"op": _op}),
            )
        self.patch(BuildImage, "convert", self._on_convert)
        self.patch(BuildImage, "resize", self._on_resize)
        self.patch(BuildImage, "resize_width", self._on_resize_width)
        self.patch(BuildImage, "resize_height", self._on_resize_height)
        self.patch(BuildImage, "rotate", self._on_rotate)
        self.patch(BuildImage, "crop", self._on_crop)

        # -- text -----------------------------------------------------------
        self.patch(BuildImage, "draw_text", self._on_draw_text)
        self.patch(Text2Image, "from_text", self._on_text_from_text)
        self.patch(Text2Image, "to_image", self._on_text_to_image)

        # -- compositing ----------------------------------------------------
        self.patch(BuildImage, "paste", self._on_paste)
        self.patch(BuildImage, "image", self._on_image_prop, prop=True)

    # handlers --------------------------------------------------------------
    def _on_open(self, self_, res, a, kw):
        name = _basename(a[0]) if a else ""
        if name not in self.frame_names:
            self.fail(f"missing frame asset {name}")
            return
        self.used_files.add(name)
        self.tag(res, {"t": "file", "name": name, "tx": []})

    def _on_new(self, self_, res, a, kw):
        size = a[1] if len(a) > 1 else kw.get("size")
        color = a[2] if len(a) > 2 else kw.get("color")
        w, h = (int(size[0]), int(size[1])) if size else (0, 0)
        self.tag(res, {
            "t": "canvas", "w": w, "h": h,
            "color": _color_hex(color), "layers": [],
        })

    def _add_tx(self, obj, tx: dict):
        node = self.node_of(obj)
        if node is None:
            return
        node.setdefault("tx", []).append(tx)

    def _derive(self, src_obj, res, tx: dict | None, drop_tx: bool = False):
        """Record one transform and carry provenance onto the returned object."""
        node = self.node_of(src_obj)
        if node is None:
            return
        if tx is None:
            new = node
        else:
            new = {**node, "tx": [] if drop_tx else list(node.get("tx", [])) + [tx]}
        if res is None:
            self.tag(src_obj, new)
        else:
            self.tag(res, new if res is not src_obj else new)
            if res is src_obj:
                self.prov[id(src_obj)] = new

    def _on_convert(self, self_, res, a, kw):
        self._derive(self_, res, None)

    def _on_resize(self, self_, res, a, kw):
        size = a[0] if a else kw.get("size")
        if isinstance(size, (tuple, list)) and len(size) == 2:
            self._derive(self_, res, {"op": "resize", "w": int(size[0]), "h": int(size[1])})

    def _on_resize_width(self, self_, res, a, kw):
        w = int(a[0] if a else kw.get("width", 0))
        self._derive(self_, res, {"op": "resize", "w": w, "h": int(getattr(res, "height", 0))})

    def _on_resize_height(self, self_, res, a, kw):
        h = int(a[0] if a else kw.get("height", 0))
        self._derive(self_, res, {"op": "resize", "w": int(getattr(res, "width", 0)), "h": h})

    def _on_rotate(self, self_, res, a, kw):
        deg = float(a[0] if a else kw.get("angle", 0))
        expand = bool(a[1] if len(a) > 1 else kw.get("expand", False))
        self._derive(self_, res, {"op": "rotate", "deg": round(deg, 3), "expand": expand})

    def _on_crop(self, self_, res, a, kw):
        box = a[0] if a else kw.get("box")
        if isinstance(box, (tuple, list)) and len(box) == 4:
            self._derive(self_, res, {"op": "crop", "box": [int(v) for v in box]})

    def _on_draw_text(self, self_, res, a, kw):
        rect = a[0] if a else kw.get("box")
        text = a[1] if len(a) > 1 else kw.get("text", "")
        if not isinstance(rect, (tuple, list)) or len(rect) != 4:
            self.fail("draw_text rect unresolved")
            return
        spec = {
            "box": [int(v) for v in rect],
            "maxFont": float(kw.get("max_fontsize", 60) or 60),
            "minFont": float(kw.get("min_fontsize", 20) or 20),
            "wrap": bool(kw.get("allow_wrap", False)),
            "fill": _color_hex(kw.get("fill")),
            "strokeFill": _color_hex(kw.get("stroke_fill")),
            "strokeRatio": 0.0,
            "align": kw.get("lines_align", "center") or "center",
        }
        idx = self.text_seq
        self.text_seq += 1
        self.tag(self_, {"t": "text", "i": idx, "placeholder": str(text), "spec": spec, "tx": []})

    def _on_text_from_text(self, cls, res, a, kw):
        text = a[0] if a else kw.get("text", "")
        size = float(a[1] if len(a) > 1 else kw.get("fontsize", 60))
        self._last_text = {
            "placeholder": str(text), "fontSize": size,
            "fill": _color_hex(kw.get("fill")),
            "strokeFill": _color_hex(kw.get("stroke_fill")),
            "strokeWidth": float(kw.get("stroke_width", 0) or 0),
            "wrap": 0,
        }
        self.tag(res, {"t": "t2i", "spec": dict(self._last_text), "tx": []})

    def _on_text_to_image(self, self_, res, a, kw):
        node = self.node_of(self_) or {"spec": dict(getattr(self, "_last_text", {}))}
        spec = dict(node.get("spec", {}))
        box = getattr(self_, "size", None) or (res.width, res.height)
        spec["box"] = [int(box[0]), int(box[1])]
        spec.setdefault("maxFont", spec.get("fontSize", 60))
        spec.setdefault("minFont", max(12.0, spec.get("fontSize", 60) * 0.4))
        spec.setdefault("wrap", False)
        idx = self.text_seq
        self.text_seq += 1
        self.tag(res, {"t": "text", "i": idx, "spec": spec, "tx": []})

    def _on_paste(self, self_, res, a, kw):
        src = a[0] if a else kw.get("img")
        pos = a[1] if len(a) > 1 else kw.get("pos")
        below = bool(kw.get("below", False))
        alpha = bool(kw.get("alpha", True))
        child = self.node_of(src)
        if child is None:
            self.fail("paste source without provenance")
            return
        node = self.node_of(self_)
        if node is None or node.get("t") not in ("canvas",):
            # pasting into a plain frame/file turns it into a composite
            node = {
                "t": "canvas", "w": 0, "h": 0, "color": None,
                "layers": [], "base": node,
            }
            self.tag(self_, node)
        x, y = (int(pos[0]), int(pos[1])) if pos else (0, 0)
        node["layers"].append({
            "src": child, "x": x, "y": y, "below": below, "alpha": alpha,
        })

    def _on_image_prop(self, self_, val):
        node = self.node_of(self_)
        if node is not None and val is not None:
            self.prov[id(val)] = node


def _color_hex(v):
    if v is None:
        return None
    if isinstance(v, str):
        return v
    if isinstance(v, (tuple, list)):
        if len(v) == 4 and v[3] < 6:
            return None
        rgb = [int(max(0, min(255, c))) for c in v[:3]]
        return "#%02X%02X%02X" % tuple(rgb)
    return None


# ── flatten provenance into a replayable script ──────────────────────────────
def flatten(node, depth=0):
    """Return {"kind": ...} op source, or _UNSUPPORTED."""
    if depth > 4:
        return _UNSUPPORTED
    t = node.get("t")
    if t in ("user", "text", "file"):
        tx = []
        for op in node.get("tx", []):
            if op.get("op") not in TX_OPS:
                return _UNSUPPORTED
            tx.append(op)
        out = {"kind": t, "tx": tx}
        if t == "user":
            out["index"] = node["i"]
        elif t == "text":
            out["index"] = node["i"]
            out["spec"] = node["spec"]
        else:
            out["file"] = node["name"]
        return out
    return _UNSUPPORTED


def script_from_frames(rec: Recorder, frames: list, delay: float) -> dict | None:
    if rec.unsupported:
        return None
    if not frames or len(frames) > MAX_FRAMES:
        return None
    out_frames = []
    width = height = 0
    for f in frames:
        node = rec.prov.get(id(f))
        if node is None:
            return None
        layers = []
        base_name = None
        base_canvas = None
        if node.get("t") == "file":
            base_name = node["name"]
            width = width or f.width
            height = height or f.height
        elif node.get("t") == "canvas":
            base = node.get("base")
            if base and base.get("t") == "file":
                base_name = base["name"]
                layers.extend(_paste_ops(rec, base.get("layers", [])))
            base_canvas = {
                "w": int(node.get("w") or f.width),
                "h": int(node.get("h") or f.height),
                "color": node.get("color"),
            }
            width = max(width, base_canvas["w"])
            height = max(height, base_canvas["h"])
        else:
            return None
        ops = _paste_ops(rec, node.get("layers", []))
        if ops is None:
            return None
        layers.extend(ops)
        fr = {"ops": layers}
        if base_name:
            fr["base"] = {"file": base_name}
        elif base_canvas:
            fr["base"] = base_canvas
        else:
            return None
        out_frames.append(fr)
    if not out_frames:
        return None
    return {
        "width": width,
        "height": height,
        "delayMs": max(20, int(round(delay * 1000))),
        "frames": out_frames,
    }


def _paste_ops(rec: Recorder, layers):
    ops = []
    for layer in layers:
        src = flatten(layer["src"])
        if src is _UNSUPPORTED:
            return None
        ops.append({
            "src": src, "x": layer["x"], "y": layer["y"],
            "below": bool(layer.get("below")),
        })
    return ops


# ── public entry point ───────────────────────────────────────────────────────
def record(key: str, code: str, frames_dir: pathlib.Path,
           user_image_count: int, text_count: int) -> dict | None:
    """Execute the upstream meme function and return a frame script, or None."""
    frame_names = {p.name for p in frames_dir.glob("*")} if frames_dir.is_dir() else set()
    if not frame_names:
        return None

    capture: dict = {}
    install_stubs(capture)
    rec = Recorder(frame_names)

    tree = ast.parse(code)
    fn = next(
        (n for n in tree.body
         if isinstance(n, ast.FunctionDef) and any(a.arg == "texts" for a in n.args.args)),
        None,
    )
    if fn is None:
        return None

    # drop metadata calls so exec() only defines the function
    tree.body = [
        n for n in tree.body
        if not (isinstance(n, ast.Expr) and isinstance(n.value, ast.Call)
                and isinstance(n.value.func, ast.Name) and n.value.func.id == "add_meme")
    ]
    code2 = ast.unparse(tree)

    module = types.ModuleType(f"meme_{key}")
    module.__file__ = str(frames_dir.parent / "__init__.py")
    module.__dict__["__name__"] = f"meme_{key}"

    from pil_utils import BuildImage

    rec.install()
    try:
        exec(compile(code2, module.__file__, "exec"), module.__dict__)
        fnobj = module.__dict__.get(key)
        if fnobj is None:
            return None
        images = []
        for i in range(max(1, user_image_count)):
            base = Image.new("RGBA", (240 + i * 10, 200 + i * 7), (200, 120, 90, 255))
            b = BuildImage(base)
            rec.tag(b, {"t": "user", "i": i, "tx": []})
            images.append(b)
        texts = [f"测试文本{i + 1}" for i in range(max(0, text_count))]
        args = _Any()
        try:
            fnobj(images, texts, args)
        except Exception as e:  # noqa: BLE001
            if rec.unsupported:
                return None
            print(f"  {key}: exec failed: {type(e).__name__}: {e}", file=sys.stderr)
            return None
        frames = capture.get("frames")
        if not frames:
            print(f"  {key}: no frames captured", file=sys.stderr)
            return None
        script = script_from_frames(rec, frames, capture.get("delay", 0.1))
        if script is None:
            print(f"  {key}: flatten failed ({rec.unsupported})", file=sys.stderr)
        return script
    finally:
        rec.unpatch()


def export_frames(raw_frames: dict[str, bytes], out_dir: pathlib.Path) -> tuple[int, int]:
    """Write downloaded PNG frames as WebP; return (width, height) of frame 0."""
    out_dir.mkdir(parents=True, exist_ok=True)
    first = None
    for name, raw in raw_frames.items():
        im = Image.open(io.BytesIO(raw))
        im.load()
        if max(im.width, im.height) > MAX_SIDE:
            im.thumbnail((MAX_SIDE, MAX_SIDE), Image.LANCZOS)
        if first is None:
            first = im.size
        rgba = im.convert("RGBA") if ("A" in im.getbands() or im.mode == "P") else im
        buf = io.BytesIO()
        rgba.save(buf, "WEBP", quality=86, method=6)
        (out_dir / f"{pathlib.PurePath(name).stem}.webp").write_bytes(buf.getvalue())
    return first or (0, 0)
