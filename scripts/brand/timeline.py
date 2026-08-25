"""
Single source of truth for the Verflixed ident's beat map.

Both the picture (`ident.glsl` via `render_ident.py`) and the sound
(`make_sting.py`) read these numbers, so a retimed cut can never drift out of
sync with the sonic logo. Values are seconds from the first frame.
"""
from __future__ import annotations

DURATION = 6.80
FPS = 60

BEATS: dict[str, float] = {
    # Near-total blackness; only two grazing reflections at the far edges.
    "DARK_OUT": 0.95,
    # Glass surfaces resolve out of the dark, camera still crawling.
    "EMERGE_IN": 0.60,
    "EMERGE_OUT": 2.40,
    # The dolly through and between the structures; speed peaks late.
    "MOVE_IN": 0.00,
    "MOVE_OUT": 4.85,
    # Deepest point of the tunnel — frame entirely filled with glass.
    "TUNNEL_PEAK": 3.40,
    # Structures sweep outward and upward; the gap resolves into the V.
    "EXIT_IN": 4.05,
    # The impact. Negative space becomes the mark.
    "IMPACT": 4.85,
    # A single glow travels through the mark, left to right.
    "SWEEP_IN": 5.15,
    "SWEEP_OUT": 5.85,
    # Wordmark.
    "WORD_IN": 5.45,
    "WORD_OUT": 6.05,
    # Surrounding blue energy falls back to black; the V stays crisp.
    "FADE_IN": 6.05,
}

# Geometry constants the renderer and the shader must agree on.
TRAVEL_LEN = 26.0
MARK_SCALE = 1.75
MARK_Z = TRAVEL_LEN + 4.60
MARK_Y = 0.42


def glsl_defines() -> str:
    """Emit the beat map as `#define`s for injection into the shader."""
    lines = [
        f"#define T_DUR {DURATION:.4f}",
        f"#define TRAVEL_LEN {TRAVEL_LEN:.4f}",
        f"#define MARK_SCALE {MARK_SCALE:.4f}",
        f"#define MARK_Z {MARK_Z:.4f}",
        f"#define MARK_Y {MARK_Y:.4f}",
    ]
    lines += [f"#define T_{name} {value:.4f}" for name, value in BEATS.items()]
    return "\n".join(lines)


def frames() -> int:
    return round(DURATION * FPS)
