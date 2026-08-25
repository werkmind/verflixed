#!/usr/bin/env python3
"""
Offline GPU renderer for the Verflixed startup ident.

`ident.glsl` is raymarched headlessly into a linear HDR buffer, the wordmark is
composited in the same linear space so it picks up the scene's bloom for free,
and the result is tonemapped, dithered and piped straight into ffmpeg.

The shader is the only description of the visuals; this file is the harness.

    python render_ident.py --preview          # fast contact sheet, no video
    python render_ident.py                    # full 1080p60 master
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import gaussian_filter, zoom

import timeline

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
FONT = HERE / "fonts" / "Outfit-Medium.ttf"

WORDMARK = "VERFLIXED"
WORD_WIDTH_FRAC = 0.300   # tracked width of the wordmark as a share of frame width
WORD_TRACKING_EM = 0.340
WORD_BASELINE_FRAC = 0.795
WORD_COLOR = np.float32([0.86, 0.92, 1.00])   # white with the faintest blue lean

VERTEX_SHADER = """
#version 410 core
const vec2 V[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
void main() { gl_Position = vec4(V[gl_VertexID], 0.0, 1.0); }
"""


# ---------------------------------------------------------------------------
# choreography shared with the shader
# ---------------------------------------------------------------------------

def seg(t: float, a: float, b: float) -> float:
    if b <= a:
        return 1.0 if t >= b else 0.0
    return min(1.0, max(0.0, (t - a) / (b - a)))


def smootherstep(x: float) -> float:
    return x * x * x * (x * (x * 6.0 - 15.0) + 10.0)


# ---------------------------------------------------------------------------
# wordmark
# ---------------------------------------------------------------------------

class Wordmark:
    """Letter-by-letter reveal rendered as a linear-light emissive mask."""

    def __init__(self, width: int, height: int):
        self.w, self.h = width, height
        size = 8
        font = ImageFont.truetype(str(FONT), size)
        # Solve the point size that lands the tracked wordmark on the target width.
        unit = self._tracked_width(font, size) / size
        self.size = int(round(WORD_WIDTH_FRAC * width / unit))
        self.font = ImageFont.truetype(str(FONT), self.size)
        self.tracking = WORD_TRACKING_EM * self.size
        self.advances = [self.font.getlength(ch) for ch in WORDMARK]
        total = sum(self.advances) + self.tracking * (len(WORDMARK) - 1)
        self.x0 = (width - total) / 2.0
        self.baseline = WORD_BASELINE_FRAC * height

    @staticmethod
    def _tracked_width(font: ImageFont.FreeTypeFont, size: int) -> float:
        adv = sum(font.getlength(ch) for ch in WORDMARK)
        return adv + WORD_TRACKING_EM * size * (len(WORDMARK) - 1)

    def mask(self, t: float) -> np.ndarray | None:
        beats = timeline.BEATS
        span = beats["WORD_OUT"] - beats["WORD_IN"]
        if t < beats["WORD_IN"]:
            return None

        layer = Image.new("L", (self.w, self.h), 0)
        draw = ImageDraw.Draw(layer)
        x = self.x0
        n = len(WORDMARK)
        drew = False
        for i, ch in enumerate(WORDMARK):
            # Letters arrive left to right, chasing the light sweep through the V.
            start = beats["WORD_IN"] + (i / n) * span * 0.62
            k = smootherstep(seg(t, start, start + span * 0.55))
            if k > 0.0:
                drew = True
                rise = (1.0 - k) * self.size * 0.30
                draw.text(
                    (x, self.baseline + rise),
                    ch,
                    font=self.font,
                    fill=int(255 * min(1.0, k)),
                    anchor="ls",
                )
            x += self.advances[i] + self.tracking
        if not drew:
            return None
        return np.asarray(layer, dtype=np.float32) / 255.0


# ---------------------------------------------------------------------------
# tone reproduction
# ---------------------------------------------------------------------------

def bloom(hdr: np.ndarray) -> np.ndarray:
    """Two-scale glow built from unclipped highlights, computed at 1/4 res."""
    bright = np.maximum(hdr - 1.30, 0.0)
    small = bright[::4, ::4]
    glow = gaussian_filter(small, sigma=(3.0, 3.0, 0.0)) * 0.34
    glow += gaussian_filter(small, sigma=(14.0, 14.0, 0.0)) * 0.30
    factors = (hdr.shape[0] / glow.shape[0], hdr.shape[1] / glow.shape[1], 1.0)
    return zoom(glow, factors, order=1)[: hdr.shape[0], : hdr.shape[1]]


def aces(x: np.ndarray) -> np.ndarray:
    a, b, c, d, e = 2.51, 0.03, 2.43, 0.59, 0.14
    return np.clip((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0)


def encode_srgb(linear: np.ndarray) -> np.ndarray:
    low = linear * 12.92
    high = 1.055 * np.power(np.maximum(linear, 1e-8), 1.0 / 2.4) - 0.055
    return np.where(linear <= 0.0031308, low, high)


def to_bytes(hdr: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    srgb = encode_srgb(aces(hdr)) * 255.0
    # Triangular dither, well under one code value. Deep blue-on-black gradients
    # band badly in 8-bit h264 without it, and this stays below visible grain.
    noise = rng.random(srgb.shape, dtype=np.float32) - rng.random(srgb.shape, dtype=np.float32)
    return np.clip(srgb + noise * 0.6 + 0.5, 0, 255).astype(np.uint8)


# ---------------------------------------------------------------------------
# renderer
# ---------------------------------------------------------------------------

class IdentRenderer:
    def __init__(self, width: int, height: int, supersample: int, tile_rows: int = 320):
        self.w, self.h = width, height
        self.ss = supersample
        self.sw, self.sh = width * supersample, height * supersample
        self.tile_rows = tile_rows

        self.ctx = moderngl.create_standalone_context()
        source = (HERE / "ident.glsl").read_text()
        source = source.replace("// @TIMELINE", timeline.glsl_defines())
        self.prog = self.ctx.program(vertex_shader=VERTEX_SHADER, fragment_shader=source)
        self.vao = self.ctx.vertex_array(self.prog, [])

        rows = min(tile_rows, self.sh)
        self.rb = self.ctx.renderbuffer((self.sw, rows), components=3, dtype="f4")
        self.fbo = self.ctx.framebuffer(color_attachments=[self.rb])
        self.prog["uRes"].value = (float(self.sw), float(self.sh))

        self.wordmark = Wordmark(width, height)
        self.rng = np.random.default_rng(20260825)

    def hdr(self, t: float) -> np.ndarray:
        """Render one frame to a linear HDR float32 buffer at output resolution."""
        self.prog["uT"].value = float(t)
        self.fbo.use()
        rows = self.rb.height
        full = np.empty((self.sh, self.sw, 3), dtype=np.float32)
        for y0 in range(0, self.sh, rows):
            n = min(rows, self.sh - y0)
            self.prog["uOrigin"].value = (0.0, float(y0))
            self.vao.render(moderngl.TRIANGLES, vertices=3)
            raw = self.fbo.read(components=3, dtype="f4")
            tile = np.frombuffer(raw, dtype=np.float32).reshape(rows, self.sw, 3)
            full[y0 : y0 + n] = tile[:n]

        if self.ss > 1:
            full = full.reshape(self.h, self.ss, self.w, self.ss, 3).mean(axis=(1, 3))
        # GL's origin is bottom-left.
        img = np.flipud(full)

        mask = self.wordmark.mask(t)
        if mask is not None:
            img = img + mask[:, :, None] * WORD_COLOR * 1.45
        return img

    def frame(self, t: float) -> np.ndarray:
        hdr = self.hdr(t)
        return to_bytes(hdr + bloom(hdr), self.rng)


# ---------------------------------------------------------------------------

def render_preview(
    r: IdentRenderer,
    out: Path,
    start: float = 0.0,
    stop: float | None = None,
    columns: int = 4,
    rows: int = 4,
) -> None:
    stop = timeline.DURATION if stop is None else stop
    count = columns * rows
    sheet = Image.new("RGB", (r.w * columns, r.h * rows), (0, 0, 0))
    draw = ImageDraw.Draw(sheet)
    label = ImageFont.truetype(str(FONT), max(14, r.h // 22))
    for i in range(count):
        t = start + (stop - start) * i / (count - 1)
        tile = Image.fromarray(r.frame(t), "RGB")
        x, y = (i % columns) * r.w, (i // columns) * r.h
        sheet.paste(tile, (x, y))
        draw.text((x + 8, y + 6), f"{t:.2f}s", font=label, fill=(120, 170, 255))
        print(f"  preview {i + 1}/{count}  t={t:.2f}s", flush=True)
    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out)
    print("wrote", out)


def render_video(r: IdentRenderer, out: Path, audio: Path | None, crf: int) -> None:
    frames = timeline.frames()
    cmd = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-f", "rawvideo", "-pix_fmt", "rgb24",
        "-s", f"{r.w}x{r.h}", "-framerate", str(timeline.FPS), "-i", "-",
    ]
    if audio and audio.is_file():
        cmd += ["-i", str(audio), "-map", "0:v", "-map", "1:a",
                "-c:a", "aac", "-b:a", "192k", "-ac", "2", "-ar", "48000"]
    cmd += [
        "-c:v", "libx264", "-profile:v", "high", "-level", "4.2",
        "-pix_fmt", "yuv420p", "-preset", "veryslow", "-crf", str(crf),
        "-x264-params", "aq-mode=3:aq-strength=1.1",
        "-movflags", "+faststart", "-shortest", str(out),
    ]
    out.parent.mkdir(parents=True, exist_ok=True)
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    assert proc.stdin is not None
    for i in range(frames):
        proc.stdin.write(r.frame(i / timeline.FPS).tobytes())
        if i % 20 == 0:
            print(f"  frame {i}/{frames}", flush=True)
    proc.stdin.close()
    if proc.wait() != 0:
        raise SystemExit("ffmpeg failed")
    print("wrote", out, f"{out.stat().st_size / 1024:.0f} KB")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--preview", action="store_true", help="render a contact sheet only")
    ap.add_argument("--from", dest="start", type=float, default=0.0, help="preview window start")
    ap.add_argument("--to", dest="stop", type=float, default=None, help="preview window end")
    ap.add_argument("--width", type=int, default=1920)
    ap.add_argument("--supersample", type=int, default=2)
    ap.add_argument("--crf", type=int, default=17)
    ap.add_argument("--audio", type=Path, default=Path("/tmp/verflixed_ident.wav"))
    ap.add_argument("--out", type=Path, default=ROOT / "app/src/main/res/raw/brand_intro.mp4")
    ap.add_argument("--preview-out", type=Path, default=Path("/tmp/verflixed_ident_sheet.png"))
    args = ap.parse_args()

    if not FONT.is_file():
        raise SystemExit(f"missing wordmark font: {FONT}")

    width = args.width
    height = round(width * 9 / 16)
    if args.preview:
        width, height, args.supersample = 640, 360, 1

    print(f"context… {width}x{height} ss={args.supersample} {timeline.FPS}fps "
          f"{timeline.DURATION}s ({timeline.frames()} frames)")
    r = IdentRenderer(width, height, args.supersample)
    print("GL", r.ctx.info["GL_VERSION"], "|", r.ctx.info["GL_RENDERER"])

    if args.preview:
        render_preview(r, args.preview_out, args.start, args.stop)
    else:
        render_video(r, args.out, args.audio, args.crf)


if __name__ == "__main__":
    sys.exit(main())
