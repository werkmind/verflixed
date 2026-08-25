#!/usr/bin/env python3
"""
Generate launcher icons, TV banner, and webapp mark from the shipping intro.

Source of truth: VerflixedIntroView's final settled pose (progress = 1).
Not the experimental GL ident — that was discarded.

    .venv-brand/bin/python make_icons.py
"""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
RES = ROOT / "app/src/main/res"
WEB_ICONS = ROOT / "webapp/renderer/icons"
WEB_FAVICON = ROOT / "webapp/renderer/favicon.png"
TIZEN_ICON = ROOT / "tizen/icon.png"
FONT = HERE / "fonts" / "Outfit-Medium.ttf"

# Exact palette from VerflixedIntroView.kt
FACE_TOP = (0x8F, 0xC4, 0xFF, 255)
FACE_MID = (0x2F, 0x80, 0xFF, 255)
FACE_BOTTOM = (0x13, 0x48, 0xA8, 255)
SIDE_DARK = (0x0A, 0x1E, 0x44, 255)
SIDE_LIGHT = (0x1B, 0x5F, 0xC4, 255)
GLOW_INNER = (0x2F, 0x80, 0xFF, 0x66)
GLOW_MID = (0x2F, 0x80, 0xFF, 0x22)
BG = (0x02, 0x04, 0x0A, 255)
WORD = (255, 255, 255, 255)

MIPMAPS = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

BANNER_SIZES = {
    "drawable": 320,
    "drawable-tvdpi": 426,
    "drawable-xhdpi": 640,
    "drawable-xxhdpi": 960,
    "drawable-nodpi": 640,
}


def v_polygon(size: float) -> list[tuple[float, float]]:
    """Same chevron outline as VerflixedIntroView.buildV."""
    half_w = size * 0.62
    top = -size * 0.60
    bottom = size * 0.62
    thick = size * 0.30
    return [
        (-half_w, top),
        (-half_w + thick, top),
        (0.0, bottom - thick * 0.55),
        (half_w - thick, top),
        (half_w, top),
        (thick * 0.30, bottom),
        (-thick * 0.30, bottom),
    ]


def lerp_rgba(a, b, t: float):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(4))


def paint_v(
    size: int,
    *,
    with_wordmark: bool,
    pad: float = 0.22,
    bg: tuple[int, int, int, int] = BG,
) -> Image.Image:
    """
    Final settled intro frame: flyIn=1, settle=1, depth=0, rotY=0.
    scale = 1.06 (the overshoot term is zero at settle=1).
    """
    img = Image.new("RGBA", (size, size) if not with_wordmark else (size, int(size * 9 / 16)), bg)
    w, h = img.size
    mark = min(w, h) * (0.42 if with_wordmark else 0.58)
    # Settled scale from the view (depth term gone).
    scale = 1.06
    mark *= scale
    cx = w / 2
    cy = h / 2 - (mark * 0.22 if with_wordmark else 0.0)

    # Soft brand glow — same radial as drawBackglow at flyIn=1.
    glow_r = int(mark * 2.8)
    glow = Image.new("RGBA", (glow_r * 2, glow_r * 2), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for i in range(glow_r, 0, -1):
        t = i / glow_r
        if t < 0.45:
            c = lerp_rgba(GLOW_INNER, GLOW_MID, t / 0.45)
        else:
            c = lerp_rgba(GLOW_MID, (0x2F, 0x80, 0xFF, 0), (t - 0.45) / 0.55)
        # Match view glowAlpha ≈ 110 at settle.
        a = int(c[3] * (110 / 255) * 1.35)
        gd.ellipse((glow_r - i, glow_r - i, glow_r + i, glow_r + i), fill=(*c[:3], a))
    glow = glow.filter(ImageFilter.GaussianBlur(max(2, size // 80)))
    img.alpha_composite(glow, (int(cx - glow_r), int(cy - glow_r)))

    poly = v_polygon(mark)
    # Residual extrusion at depth=0: extrude = mark * 0.045
    extrude = mark * 0.045
    steps = 7
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    for i in range(steps, 0, -1):
        k = i / steps
        ox = -extrude * k * 1.15
        oy = extrude * k * 0.55
        pts = [(cx + x + ox, cy + y + oy) for x, y in poly]
        alpha = int(52 + 90 * (1 - k))
        # Approximate the side gradient with a single mid tone.
        side = lerp_rgba(SIDE_DARK, SIDE_LIGHT, 0.45)
        ld.polygon(pts, fill=(*side[:3], alpha))

    face = Image.new("RGBA", img.size, (0, 0, 0, 0))
    fd = ImageDraw.Draw(face)
    face_pts = [(cx + x, cy + y) for x, y in poly]
    # Fill then vertical gradient via a mask.
    fd.polygon(face_pts, fill=(255, 255, 255, 255))
    grad = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ys = [p[1] for p in face_pts]
    y0, y1 = min(ys), max(ys)
    for y in range(max(0, int(y0)), min(h, int(y1) + 1)):
        t = (y - y0) / max(1.0, y1 - y0)
        if t < 0.55:
            c = lerp_rgba(FACE_TOP, FACE_MID, t / 0.55)
        else:
            c = lerp_rgba(FACE_MID, FACE_BOTTOM, (t - 0.55) / 0.45)
        ImageDraw.Draw(grad).line([(0, y), (w, y)], fill=c)
    face = Image.composite(grad, Image.new("RGBA", img.size, (0, 0, 0, 0)), face.split()[-1])
    layer.alpha_composite(face)
    img.alpha_composite(layer)

    if with_wordmark and FONT.is_file():
        text = "VERFLIXED"
        # Match intro: textSize ≈ mark * 0.40, tracking ≈ 0.26 em.
        font_size = max(12, int(mark * 0.40))
        font = ImageFont.truetype(str(FONT), font_size)
        tracking = font_size * 0.26
        advances = [font.getlength(ch) for ch in text]
        total = sum(advances) + tracking * (len(text) - 1)
        x = cx - total / 2
        baseline = cy + mark * 1.16
        td = ImageDraw.Draw(img)
        for i, ch in enumerate(text):
            td.text((x, baseline), ch, font=font, fill=WORD, anchor="ls")
            x += advances[i] + tracking

    # Optional outer pad crop for square icons is handled by the caller.
    _ = pad
    return img


def v_square(frame: Image.Image, pad_frac: float = 0.34) -> Image.Image:
    """Crop a square around the V's opaque content."""
    alpha = frame.split()[-1]
    bbox = alpha.getbbox()
    if not bbox:
        return frame
    x0, y0, x1, y1 = bbox
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    half = max(x1 - x0, y1 - y0) / 2 * (1 + pad_frac)
    left, top = int(cx - half), int(cy - half)
    side = int(half * 2)
    # Pad with brand black if the crop goes outside.
    canvas = Image.new("RGBA", (side, side), BG)
    canvas.paste(frame, (-left, -top), frame)
    return canvas


def round_icon(square: Image.Image) -> Image.Image:
    mask = Image.new("L", square.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, square.width - 1, square.height - 1), fill=255)
    out = Image.new("RGBA", square.size, BG)
    out.paste(square, (0, 0), mask)
    return out


def save_rgb(img: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if img.mode == "RGBA":
        bg = Image.new("RGB", img.size, BG[:3])
        bg.paste(img, mask=img.split()[-1])
        bg.save(path)
    else:
        img.convert("RGB").save(path)


def main() -> None:
    print("painting VerflixedIntroView final frame (no wordmark)…")
    logo = paint_v(1024, with_wordmark=False)
    square = v_square(logo, pad_frac=0.28)

    # Master clear mark (transparent) for webapp / reuse.
    clear = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    mark = paint_v(512, with_wordmark=False, bg=(0, 0, 0, 0))
    cropped = v_square(mark, pad_frac=0.22).resize((512, 512), Image.LANCZOS)
    clear.paste(cropped, (0, 0), cropped)
    WEB_ICONS.mkdir(parents=True, exist_ok=True)
    clear.save(WEB_ICONS / "icon-mark-clear.png")
    # Keep Android drawable in sync if present.
    clear_android = RES / "drawable" / "ic_verflixed_mark_clear.png"
    if clear_android.parent.is_dir():
        clear.save(clear_android)
    print(f"  {WEB_ICONS / 'icon-mark-clear.png'}")

    for folder, px in MIPMAPS.items():
        d = RES / folder
        d.mkdir(parents=True, exist_ok=True)
        icon = square.resize((px, px), Image.LANCZOS)
        save_rgb(icon, d / "ic_launcher.png")
        save_rgb(round_icon(square).resize((px, px), Image.LANCZOS), d / "ic_launcher_round.png")
        print(f"  {folder}/ic_launcher(.png/_round.png) {px}px")

    # Also ship a sharp xxxhdpi-sized source for stores (512).
    store = RES / "mipmap-xxxhdpi" / "ic_launcher.png"
    # overwrite with 192 already done; write 512 next to web assets
    save_rgb(square.resize((512, 512), Image.LANCZOS), WEB_ICONS / "icon-512.png")

    print("painting banner frame (with wordmark)…")
    banner = paint_v(1280, with_wordmark=True)
    for folder, w in BANNER_SIZES.items():
        d = RES / folder
        if not d.is_dir():
            continue
        h = round(w * 9 / 16)
        save_rgb(banner.resize((w, h), Image.LANCZOS), d / "app_banner.png")
        print(f"  {folder}/app_banner.png {w}x{h}")

    print("webapp + tizen icons…")
    for px in (128, 192, 256):
        save_rgb(square.resize((px, px), Image.LANCZOS), WEB_ICONS / f"icon-{px}.png")
        print(f"  icons/icon-{px}.png")
    save_rgb(square.resize((32, 32), Image.LANCZOS), WEB_FAVICON)
    print(f"  {WEB_FAVICON.name}")
    if TIZEN_ICON.parent.is_dir():
        save_rgb(square.resize((117, 117), Image.LANCZOS), TIZEN_ICON)
        print(f"  {TIZEN_ICON}")

    print("done — source frame = VerflixedIntroView settle (progress=1)")


if __name__ == "__main__":
    main()
