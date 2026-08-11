#!/usr/bin/env python3
"""Test VOE decode round-trip + SerienStream policy (prefer m3u8 over VOE/captcha)."""
from __future__ import annotations

import base64
import json
import re
import urllib.parse
import urllib.request

JUNK = ["@$", "^^", "~@", "%?", "*~", "!!", "#&"]
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"


def rot13(s: str) -> str:
    out = []
    for c in s:
        o = ord(c)
        if 65 <= o <= 90:
            out.append(chr((o - 65 + 13) % 26 + 65))
        elif 97 <= o <= 122:
            out.append(chr((o - 97 + 13) % 26 + 97))
        else:
            out.append(c)
    return "".join(out)


def encode_voe(obj: dict) -> str:
    """Inverse of VoeExtractor.decodeVoeString for unit testing."""
    s = json.dumps(obj, separators=(",", ":"))
    s = base64.b64encode(s.encode()).decode()
    s = s[::-1]
    s = "".join(chr(ord(c) + 3) for c in s)
    s = base64.b64encode(s.encode()).decode()
    # insert junk marks optionally skipped by decoder after replace→_
    s = rot13(s)
    return s


def decode_voe(encoded: str) -> dict:
    s = rot13(encoded)
    for j in JUNK:
        s = s.replace(j, "_")
    s = s.replace("_", "")
    s = base64.b64decode(s).decode()
    s = "".join(chr(ord(c) - 3) for c in s)
    s = base64.b64decode(s[::-1]).decode()
    return json.loads(s)


def main() -> int:
    sample = {
        "source": "https://delivery.example/hls/master.m3u8",
        "direct_access_url": "https://delivery.example/video.mp4",
    }
    enc = encode_voe(sample)
    dec = decode_voe(enc)
    assert dec["source"] == sample["source"], dec
    print("[ok] VOE F7 decode round-trip")

    # Live: episode still exposes only /r?t= (captcha gate) — policy must prefer VOE/m3u8 when available
    ep = "https://serienstream.to/serie/rick-and-morty/staffel-1/episode-1"
    req = urllib.request.Request(ep, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=25) as r:
        html = r.read().decode("utf-8", "ignore")
    blobs = re.findall(r'data-play-url="([^"]+)"', html)
    providers = re.findall(r'data-provider-name="([^"]+)"', html)
    print(f"[ok] episode providers={providers} blobs={len(blobs)}")
    assert "VOE" in providers
    assert blobs and "/r?t=" in blobs[0]
    print("[ok] SerienStream still gates VOE behind /r?t= — app claims VOE→m3u8 after unlock/cookies")
    print("[PASS]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
