#!/usr/bin/env python3
"""
Offline 3D renderer for the Verflixed brand opener.

Sphere-traced extruded chevron with Blinn-Phong + fresnel, reflective floor,
volumetric glow and a camera dolly. Renders PNG frames; encode_intro.sh muxes
them with the sting into brand_intro.mp4.
"""
from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1280, 720
FPS = 30
DURATION = 3.4
FRAMES = int(FPS * DURATION)
OUT = Path("/tmp/vf_intro_frames")

FONT_PATH = "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"

# Brand palette (linear-ish RGB).
BLUE = np.array([0.09, 0.40, 1.10])
BLUE_HI = np.array([0.42, 0.70, 1.20])
EDGE = np.array([0.70, 0.86, 1.00])
FLOOR_TINT = np.array([0.03, 0.05, 0.11])

# Chevron outline in object space (x, y up), extruded along z: arms on top,
# point at the bottom — a V, not a triangle.
CHEV = np.array(
    [
        (-0.62, 0.60),
        (-0.32, 0.60),
        (0.00, -0.28),
        (0.32, 0.60),
        (0.62, 0.60),
        (0.09, -0.62),
        (-0.09, -0.62),
    ]
)
HALF_Z = 0.17
BOUND_R = 1.05


def smoothstep(a: float, b: float, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def ease_out(t: float) -> float:
    return 1 - pow(1 - t, 3)


def seg(t: float, a: float, b: float) -> float:
    if b <= a:
        return 1.0 if t >= b else 0.0
    return min(1.0, max(0.0, (t - a) / (b - a)))


def poly2d_sdf(px, py):
    """Signed distance to the chevron polygon (negative inside)."""
    n = len(CHEV)
    d = (px - CHEV[0][0]) ** 2 + (py - CHEV[0][1]) ** 2
    sign = np.ones_like(px)
    for i in range(n):
        j = (i - 1) % n
        ex = CHEV[j][0] - CHEV[i][0]
        ey = CHEV[j][1] - CHEV[i][1]
        wx = px - CHEV[i][0]
        wy = py - CHEV[i][1]
        h = np.clip((wx * ex + wy * ey) / (ex * ex + ey * ey), 0.0, 1.0)
        bx = wx - ex * h
        by = wy - ey * h
        d = np.minimum(d, bx * bx + by * by)
        c1 = py >= CHEV[i][1]
        c2 = py < CHEV[j][1]
        c3 = ex * wy > ey * wx
        flip = (c1 & c2 & c3) | (~c1 & ~c2 & ~c3)
        sign = np.where(flip, -sign, sign)
    return sign * np.sqrt(d)


def sdf(p, bevel: float = 0.035):
    """Extruded chevron with a rounded bevel so highlights read as metal."""
    d2 = poly2d_sdf(p[0], p[1]) - bevel
    dz = np.abs(p[2]) - (HALF_Z - bevel)
    outside = np.sqrt(np.maximum(d2, 0.0) ** 2 + np.maximum(dz, 0.0) ** 2)
    return np.minimum(np.maximum(d2, dz), 0.0) + outside - bevel


def rot_matrix(rx: float, ry: float) -> np.ndarray:
    cy, sy = math.cos(ry), math.sin(ry)
    cx, sx = math.cos(rx), math.sin(rx)
    ry_m = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rx_m = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    return rx_m @ ry_m


def to_object(points, inv_rot, offset):
    """World -> object space (rows of points)."""
    q = points - offset
    return np.einsum("ij,jk->ik", inv_rot, q)


def march(ro, rd, inv_rot, offset, steps: int, max_t: float = 12.0):
    """Sphere trace active rays. ro/rd are (3, N)."""
    n = ro.shape[1]
    t = np.zeros(n)
    hit = np.zeros(n, dtype=bool)
    alive = np.ones(n, dtype=bool)
    for _ in range(steps):
        if not alive.any():
            break
        p = ro[:, alive] + rd[:, alive] * t[alive]
        d = sdf(to_object(p, inv_rot, offset))
        t_alive = t[alive] + np.maximum(d * 0.92, 1e-4)
        close = d < 2e-3
        idx = np.flatnonzero(alive)
        hit[idx[close]] = True
        t[alive] = t_alive
        alive_new = alive.copy()
        alive_new[idx[close | (t_alive > max_t)]] = False
        alive = alive_new
    return t, hit


def normals(p, inv_rot, offset, eps: float = 1.5e-3):
    base = to_object(p, inv_rot, offset)
    d0 = sdf(base)
    grad = np.empty_like(p)
    for axis in range(3):
        shift = np.zeros_like(p)
        shift[axis] = eps
        grad[axis] = sdf(to_object(p + shift, inv_rot, offset)) - d0
    length = np.linalg.norm(grad, axis=0)
    length[length < 1e-6] = 1e-6
    return grad / length


def env_color(rd):
    """Studio environment: cool top light, warm-dark floor bounce."""
    up = np.clip(rd[1] * 0.5 + 0.5, 0.0, 1.0)
    top = np.array([0.16, 0.28, 0.62])[:, None] * up
    bottom = np.array([0.02, 0.03, 0.07])[:, None] * (1 - up)
    # Soft key light blob behind camera-left.
    key = np.clip(rd[0] * -0.5 + rd[1] * 0.7 + 0.35, 0.0, 1.0) ** 6
    return top + bottom + np.array([0.9, 0.95, 1.0])[:, None] * key * 0.5


def shade(p, n, rd, light_dir, sweep_x, sweep_amt):
    ndl = np.clip(np.einsum("ij,i->j", n, light_dir), 0.0, 1.0)
    view = -rd
    half = light_dir[:, None] + view
    half /= np.maximum(np.linalg.norm(half, axis=0), 1e-6)
    spec = np.clip(np.einsum("ij,ij->j", n, half), 0.0, 1.0) ** 90
    fres = (1 - np.clip(np.einsum("ij,ij->j", n, view), 0.0, 1.0)) ** 3

    base = BLUE[:, None] * (0.26 + 0.95 * ndl)
    base += BLUE_HI[:, None] * ndl**3 * 0.45
    base += EDGE[:, None] * fres * 0.30
    base += np.array([1.0, 1.0, 1.0])[:, None] * spec * 1.4
    # Reflected environment keeps the faces from looking flat.
    refl = rd - 2 * np.einsum("ij,ij->j", n, rd) * n
    base += env_color(refl) * (0.16 + 0.20 * fres)
    # Travelling specular band (the "reveal" beat).
    if sweep_amt > 0:
        band = np.exp(-(((p[0] - sweep_x) / 0.16) ** 2))
        base += np.array([0.95, 0.98, 1.0])[:, None] * band * sweep_amt * 0.65
    return base


def render_frame(i: int) -> Image.Image:
    t = i / (FRAMES - 1)
    fly = ease_out(seg(t, 0.0, 0.55))
    settle = ease_out(seg(t, 0.80, 1.0))

    # Camera dolly: starts far and low, lands centered.
    cam_z = -6.2 + 3.35 * fly
    cam_y = 0.95 - 0.85 * fly
    focal = 1.5 + 0.35 * fly
    # Object spin settles to a slight three-quarter view.
    ry = -1.55 * (1 - fly) + 0.22 + 0.02 * math.sin(settle * math.pi)
    rx = 0.55 * (1 - fly) + 0.06
    rot = rot_matrix(rx, ry)
    inv_rot = rot.T
    # Sit the mark above the horizon so the wordmark gets its own space.
    offset = np.array([0.0, 0.58 - 0.10 * (1 - fly), 0.0])[:, None]

    xs = (np.arange(W) + 0.5) / W * 2 - 1
    ys = 1 - (np.arange(H) + 0.5) / H * 2
    gx, gy = np.meshgrid(xs * (W / H), ys)
    dirs = np.stack([gx.ravel(), gy.ravel() + cam_y * 0.0, np.full(gx.size, focal)])
    dirs /= np.linalg.norm(dirs, axis=0)
    origin = np.array([0.0, cam_y * 0.35, cam_z])[:, None]
    ro = np.repeat(origin, dirs.shape[1], axis=1)

    # Background: deep radial stage + horizon glow.
    r = np.sqrt(gx.ravel() ** 2 + (gy.ravel() - 0.18) ** 2)
    bg = np.array([0.035, 0.055, 0.115])[:, None] * (1 - smoothstep(0.0, 1.5, r))
    bg += np.array([0.004, 0.008, 0.018])[:, None]
    glow_amp = 0.55 * fly + 0.25
    bg += np.array([0.06, 0.22, 0.62])[:, None] * np.exp(-(r**2) / 0.5) * glow_amp * 0.5
    col = bg

    # Ray/bounding-sphere test keeps the march to the pixels that matter.
    oc = ro - offset
    b = np.einsum("ij,ij->j", oc, dirs)
    c = np.einsum("ij,ij->j", oc, oc) - BOUND_R**2
    disc = b * b - c
    active = disc > 0

    sweep_k = seg(t, 0.50, 0.72)
    sweep_amt = math.sin(sweep_k * math.pi) if 0 < sweep_k < 1 else 0.0
    sweep_x = -1.2 + 2.4 * sweep_k
    light_dir = np.array([0.45, 0.78, -0.44])
    light_dir /= np.linalg.norm(light_dir)

    contact_row = int(H * 0.62)
    if active.any():
        idx = np.flatnonzero(active)
        t_hit, hit = march(ro[:, idx], dirs[:, idx], inv_rot, offset, steps=72)
        hidx = idx[hit]
        if hidx.size:
            p = ro[:, hidx] + dirs[:, hidx] * t_hit[hit]
            n = normals(p, inv_rot, offset)
            local = to_object(p, inv_rot, offset)
            lit = shade(local, n, dirs[:, hidx], light_dir, sweep_x, sweep_amt)
            fade = min(1.0, seg(t, 0.02, 0.26) * 1.3)
            col[:, hidx] = lit * fade + col[:, hidx] * (1 - fade)
            # Put the floor exactly at the mark's lowest pixel so it stands
            # on the stage instead of floating above its own reflection.
            contact_row = int(hidx.max() // W) + 1

    # Reflective floor: mirror the mark below the horizon, blurred + faded.
    img = (col.T.reshape(H, W, 3)).clip(0, None)
    frame = np.power(img / (img + 0.9), 1 / 2.2)  # filmic tonemap
    rgb = (np.clip(frame, 0, 1) * 255).astype(np.uint8)
    pil = Image.fromarray(rgb, "RGB")

    horizon = min(max(contact_row, int(H * 0.40)), int(H * 0.74))
    top = pil.crop((0, 0, W, horizon))
    mirror = top.transpose(Image.FLIP_TOP_BOTTOM).filter(ImageFilter.GaussianBlur(5.5))
    mirror = mirror.crop((0, 0, W, H - horizon))
    floor = Image.new("RGB", mirror.size, tuple((FLOOR_TINT * 255).astype(int)))
    mirror = Image.blend(floor, mirror, 0.34 * fly)
    # Reflections fade out with distance; no hard seam at the contact line.
    fade_mask = Image.linear_gradient("L").resize(mirror.size).point(lambda v: 255 - v)
    pil.paste(mirror, (0, horizon), fade_mask)

    # Bloom pass so the specular hits feel cinematic.
    bright = pil.point(lambda v: max(0, v - 150) * 2)
    pil = Image.blend(pil, Image.blend(pil, bright.filter(ImageFilter.GaussianBlur(22)), 0.55), 0.7)

    draw_wordmark(pil, t)
    return pil


def draw_wordmark(pil: Image.Image, t: float) -> None:
    word = "VERFLIXED"
    size = int(H * 0.085)
    font = ImageFont.truetype(FONT_PATH, size)
    tracking = int(size * 0.24)
    widths = [font.getbbox(ch)[2] - font.getbbox(ch)[0] for ch in word]
    total = sum(widths) + tracking * (len(word) - 1)
    x = (W - total) / 2
    baseline = int(H * 0.76)

    layer = Image.new("RGBA", pil.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    any_visible = False
    for i, ch in enumerate(word):
        start = 0.60 + (i / len(word)) * 0.24
        k = ease_out(seg(t, start, start + 0.16))
        if k > 0:
            any_visible = True
            rise = int((1 - k) * size * 0.42)
            d.text((x, baseline + rise), ch, font=font, fill=(255, 255, 255, int(255 * min(1, k * 1.2))))
        x += widths[i] + tracking

    tk = ease_out(seg(t, 0.80, 0.97))
    if tk > 0:
        sub = ImageFont.truetype(FONT_PATH, int(size * 0.40))
        text = "Serien & Filme"
        tw = sub.getbbox(text)[2] - sub.getbbox(text)[0]
        d.text(
            ((W - tw) / 2, baseline + size * 1.25),
            text,
            font=sub,
            fill=(150, 188, 236, int(220 * tk)),
        )
        any_visible = True

    if not any_visible:
        return
    glow = layer.filter(ImageFilter.GaussianBlur(14))
    pil.paste(Image.alpha_composite(glow, layer), (0, 0), Image.alpha_composite(glow, layer))


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for f in OUT.glob("*.png"):
        f.unlink()
    for i in range(FRAMES):
        render_frame(i).save(OUT / f"f{i:04d}.png")
        if i % 10 == 0:
            print(f"frame {i}/{FRAMES}", flush=True)
    print("done", FRAMES, "frames ->", OUT)


if __name__ == "__main__":
    main()
