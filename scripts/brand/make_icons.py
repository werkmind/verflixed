#!/usr/bin/env python3
"""
Generate launcher icons and the Android TV banner from the real ident render.

Uses the exact same GL pipeline as the intro video, so the app icon is the
actual logo frame, not a re-drawn approximation.

    .venv-brand/bin/python make_icons.py
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

import timeline
from render_ident import IdentRenderer

HERE = Path(__file__).resolve().parent
RES = HERE.parent.parent / "app/src/main/res"

# Final resting pose: V crisp, surroundings faded, wordmark handled per-asset.
T_FINAL = timeline.DURATION - 0.01

MIPMAPS = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def render_frame(with_wordmark: bool) -> Image.Image:
    r = IdentRenderer(1920, 1080, supersample=2)
    if not with_wordmark:
        r.wordmark.mask = lambda t: None  # type: ignore[method-assign]
    return Image.fromarray(r.frame(T_FINAL), "RGB")


def v_square(frame: Image.Image, pad_frac: float = 0.34) -> Image.Image:
    """Crop a square centered on the V's luminance bounding box."""
    arr = np.asarray(frame.convert("L"), dtype=np.float32)
    ys, xs = np.where(arr > 60)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    half = max(x1 - x0, y1 - y0) / 2 * (1 + pad_frac)
    left, top = int(cx - half), int(cy - half)
    side = int(half * 2)
    return frame.crop((left, top, left + side, top + side))


def round_icon(square: Image.Image) -> Image.Image:
    mask = Image.new("L", square.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, square.width - 1, square.height - 1), fill=255)
    out = Image.new("RGB", square.size, (2, 4, 10))
    out.paste(square, (0, 0), mask)
    return out


def main() -> None:
    print("rendering logo frame (no wordmark)…")
    logo = render_frame(with_wordmark=False)
    square = v_square(logo)

    for folder, px in MIPMAPS.items():
        d = RES / folder
        icon = square.resize((px, px), Image.LANCZOS)
        icon.save(d / "ic_launcher.png")
        round_icon(square).resize((px, px), Image.LANCZOS).save(d / "ic_launcher_round.png")
        print(f"  {folder}/ic_launcher(.png/_round.png) {px}px")

    print("rendering banner frame (with wordmark)…")
    full = render_frame(with_wordmark=True)
    # TV banner is 16:9 (320x180 baseline). Replace every density variant.
    banner_sizes = {
        "drawable": 320,
        "drawable-tvdpi": 426,
        "drawable-xhdpi": 640,
        "drawable-xxhdpi": 960,
        "drawable-nodpi": 640,
    }
    for folder, w in banner_sizes.items():
        d = RES / folder
        if not d.is_dir():
            continue
        full.resize((w, round(w * 9 / 16)), Image.LANCZOS).save(d / "app_banner.png")
        print(f"  {folder}/app_banner.png {w}px")
    print("done")


if __name__ == "__main__":
    main()
