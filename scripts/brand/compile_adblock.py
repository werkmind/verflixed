#!/usr/bin/env python3
"""Compile EasyList / uBlock network filters into a compact host list."""
from __future__ import annotations

import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_KT = ROOT / "app/src/main/assets/adblock/hosts.txt"
OUT_JS = ROOT / "webapp/adblock/hosts.txt"

SOURCES = [
    "https://easylist.to/easylist/easylist.txt",
    "https://easylist.to/easylist/easyprivacy.txt",
    "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
]

HOST_RE = re.compile(r"^\|\|([a-z0-9.-]+\.[a-z]{2,})", re.I)
ALLOW = {
    "cloudflare.com",
    "cloudflareinsights.com",
    "challenges.cloudflare.com",
    "turnstile.com",
    "recaptcha.net",
    "google.com",
    "gstatic.com",
    "hcaptcha.com",
    "serienstream.cx",
    "serienstream.to",
    "s.to",
    "aniworld.to",
    "voe.sx",
    "filmpalast.to",
    "themoviedb.org",
    "tmdb.org",
    "wikidata.org",
    "wikipedia.org",
    "tvmaze.com",
}


def allowed(host: str) -> bool:
    h = host.lower().strip(".")
    return any(h == a or h.endswith("." + a) for a in ALLOW)


def compile_hosts() -> list[str]:
    hosts: set[str] = set()
    for url in SOURCES:
        print("fetch", url)
        req = urllib.request.Request(url, headers={"User-Agent": "VerflixedAdblock/1.0"})
        with urllib.request.urlopen(req, timeout=40) as r:
            text = r.read().decode("utf-8", "replace")
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith("!") or line.startswith("["):
                continue
            if line.startswith("@@"):
                continue
            m = HOST_RE.match(line)
            if not m:
                continue
            host = m.group(1).lower()
            if allowed(host):
                continue
            if host.count(".") < 1:
                continue
            hosts.add(host)
    return sorted(hosts)


def main() -> None:
    hosts = compile_hosts()
    body = "# EasyList + EasyPrivacy + uBlock filters (hosts only)\n" + "\n".join(hosts) + "\n"
    for dest in (OUT_KT, OUT_JS):
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(body)
        print("wrote", dest, "hosts", len(hosts), "bytes", dest.stat().st_size)


if __name__ == "__main__":
    main()
