#!/usr/bin/env python3
"""
Original Verflixed brand sting — organic cinematic TA–DUMM.

Layered acoustic-style hits (sub, body, inharmonic mallet partials, soft
attack noise) through a synthetic hall. Deliberately not a copy of any
studio ident.
"""
from __future__ import annotations

import subprocess
import wave
from pathlib import Path

import numpy as np

SR = 48000
DUR = 3.4
RNG = np.random.default_rng(20260814)

WEB = Path("/agent/StreamVault/webapp/renderer/splash_tudum.ogg")
RAW = Path("/agent/StreamVault/app/src/main/res/raw/splash_tudum.ogg")
WAV = Path("/tmp/splash_tudum.wav")


def t_axis(n: int) -> np.ndarray:
    return np.arange(n) / SR


def one_pole_lp(x: np.ndarray, cutoff: float) -> np.ndarray:
    a = (1.0 / SR) / (1.0 / (2 * np.pi * cutoff) + 1.0 / SR)
    out = np.empty_like(x)
    y = 0.0
    for i, v in enumerate(x):
        y += a * (v - y)
        out[i] = y
    return out


def one_pole_hp(x: np.ndarray, cutoff: float) -> np.ndarray:
    rc = 1.0 / (2 * np.pi * cutoff)
    a = rc / (rc + 1.0 / SR)
    out = np.empty_like(x)
    y = 0.0
    prev = 0.0
    for i, v in enumerate(x):
        y = a * (y + v - prev)
        prev = v
        out[i] = y
    return out


def hit(n: int, start: float, sub: float, decay: float, gain: float) -> np.ndarray:
    """One TA/DUMM stroke: sub, body, inharmonic partials, breath, mallet."""
    out = np.zeros(n)
    i0 = int(start * SR)
    length = min(n - i0, int((decay * 5 + 0.2) * SR))
    if length <= 0:
        return out
    u = t_axis(length)

    # Slight downward pitch glide + slow vibrato: reads as a struck body,
    # not an oscillator.
    glide = 1.0 - 0.06 * (1 - np.exp(-u / (decay * 0.7)))
    vib = 1.0 + 0.004 * np.sin(2 * np.pi * 4.6 * u + RNG.uniform(0, 3))

    def partial(mult: float, amp: float, dec: float, phase: float = 0.0) -> np.ndarray:
        f = sub * mult * glide * vib
        ph = 2 * np.pi * np.cumsum(f) / SR + phase
        return amp * np.sin(ph) * np.exp(-u / dec)

    body = partial(1.0, 0.80, decay * 1.7)
    body += partial(2.0, 0.60, decay * 1.05, 0.6)
    body += partial(3.01, 0.38, decay * 0.62, 1.4)
    # Inharmonic partials give the wood/metal character.
    body += partial(4.37, 0.26, decay * 0.42, 2.1)
    body += partial(6.83, 0.16, decay * 0.30, 0.3)
    body += partial(9.41, 0.08, decay * 0.20, 1.9)

    # Mallet contact: filtered noise burst in the low mids — the "thud" that
    # makes the hit sound struck rather than synthesized.
    noise = one_pole_lp(RNG.standard_normal(length), 1100)
    noise = one_pole_hp(noise, 140) * np.exp(-u / 0.045)
    # Air moving in the room right after the stroke.
    breath = one_pole_lp(RNG.standard_normal(length), 260) * np.exp(-u / (decay * 1.2)) * 0.5

    stroke = body + noise * 2.20 + breath * 0.35
    # Soft attack ramp avoids a digital click.
    stroke *= np.minimum(1.0, u / 0.006)
    out[i0 : i0 + length] = stroke * gain
    return out


def swell(n: int, until: float) -> np.ndarray:
    """Dark rising air before the first hit."""
    out = np.zeros(n)
    length = int(until * SR)
    u = t_axis(length)
    noise = one_pole_lp(RNG.standard_normal(length), 190)
    noise = one_pole_hp(noise, 26)
    shape = (u / until) ** 2.2 * np.exp(-((u - until) ** 2) / (2 * 0.10**2))
    out[:length] = noise * shape * 0.55
    return out


def reverb(x: np.ndarray, seconds: float = 1.5, mix: float = 0.34) -> np.ndarray:
    """Synthetic hall: decaying noise impulse response, dark tail."""
    ln = int(seconds * SR)
    ir = RNG.standard_normal(ln) * np.exp(-t_axis(ln) / (seconds * 0.34))
    ir = one_pole_lp(ir, 900)
    ir[: int(0.012 * SR)] = 0.0  # pre-delay
    ir /= np.abs(ir).sum() / 40.0
    wet = np.convolve(x, ir, mode="full")[: len(x)]
    wet /= max(np.abs(wet).max(), 1e-9)
    return x * (1 - mix) + wet * np.abs(x).max() * mix


def main() -> None:
    n = int(SR * DUR)
    mix = swell(n, 0.78)
    # TA (brighter, shorter) then DUMM a fifth below (deep, long).
    mix += hit(n, 0.78, sub=49.0, decay=0.30, gain=0.85)
    mix += hit(n, 1.06, sub=32.7, decay=0.70, gain=1.00)

    mix = one_pole_hp(mix, 24)
    mix = one_pole_lp(mix, 2600)
    mix = reverb(mix)
    mix = np.tanh(mix * 1.25)
    mix /= max(np.abs(mix).max(), 1e-9)
    left = mix * 0.93

    # Narrow stereo: tiny delay + tilt, center stays solid on TV speakers.
    d = int(0.0045 * SR)
    right = np.concatenate([left[:d] * 0.9, left[:-d] * 0.97])

    frames = np.stack([left, right], axis=1)
    pcm = (np.clip(frames, -1, 1) * 32767).astype("<i2")
    with wave.open(str(WAV), "w") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())

    RAW.parent.mkdir(parents=True, exist_ok=True)
    for dest in (WEB, RAW):
        subprocess.check_call(
            ["ffmpeg", "-y", "-i", str(WAV), "-c:a", "libvorbis", "-q:a", "6", str(dest)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        print("wrote", dest, dest.stat().st_size)


if __name__ == "__main__":
    main()
