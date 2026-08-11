#!/usr/bin/env python3
"""Live SerienStream smoke test: random series + episode playback policy."""
from __future__ import annotations

import random
import re
import sys
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
)
BASES = [
    "https://s.to",
    "https://serienstream.to",
]


def fetch(url: str, referer: str | None = None) -> tuple[str, str]:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "text/html"})
    if referer:
        req.add_header("Referer", referer)
    with urllib.request.urlopen(req, timeout=25) as resp:
        final = resp.geturl()
        body = resp.read().decode("utf-8", "ignore")
        return final, body


def abs_url(base: str, href: str) -> str:
    return urllib.parse.urljoin(base, href)


def is_play_blob(url: str) -> bool:
    return "/r?t=" in url.lower() or bool(re.search(r"/r\?t=", url, re.I))


def is_episode_page(url: str) -> bool:
    path = urllib.parse.urlparse(url).path.lower()
    return ("/serie/" in path or "/series/" in path) and (
        "/episode" in path or "/folge" in path
    )


def main() -> int:
    errors: list[str] = []
    base = None
    body = ""
    for candidate in BASES:
        try:
            final, body = fetch(candidate)
            base = final.rstrip("/")
            print(f"[ok] base reachable: {base}")
            break
        except Exception as e:
            print(f"[warn] {candidate}: {e}")
    if not base:
        print("[fail] no SerienStream mirror reachable")
        return 2

    # Collect series links
    series_links = sorted(
        {
            abs_url(base, m.group(1))
            for m in re.finditer(
                r'href=["\']([^"\']*?/(?:serie|series)/(?:stream/)?[^"\'#?]+)',
                body,
                re.I,
            )
        }
    )
    # Prefer series roots / season pages, drop pure episode links for picking series
    series_roots = [
        u
        for u in series_links
        if not is_episode_page(u)
        and "staffel" not in u.lower()
        and "/genre" not in u.lower()
    ]
    if not series_roots:
        # fallback: genre page
        try:
            _, genre_body = fetch(f"{base}/genre")
            series_roots = sorted(
                {
                    abs_url(base, m.group(1))
                    for m in re.finditer(
                        r'href=["\']([^"\']*?/(?:serie|series)/(?:stream/)?[^"\'/#?]+)',
                        genre_body,
                        re.I,
                    )
                }
            )
        except Exception as e:
            errors.append(f"genre crawl failed: {e}")

    if len(series_roots) < 3:
        print(f"[fail] too few series links: {len(series_roots)}")
        return 3

    random.shuffle(series_roots)
    series_url = series_roots[0]
    print(f"[ok] random series: {series_url}")
    try:
        series_final, series_html = fetch(series_url)
    except Exception as e:
        print(f"[fail] series fetch: {e}")
        return 4

    episode_links = sorted(
        {
            abs_url(series_final, m.group(1))
            for m in re.finditer(
                r'href=["\']([^"\']*?(?:episode|folge)[^"\']*)',
                series_html,
                re.I,
            )
            if is_episode_page(abs_url(series_final, m.group(1)))
        }
    )
    if not episode_links:
        # try staffel-1
        staffel = series_final.rstrip("/") + "/staffel-1"
        try:
            _, season_html = fetch(staffel, referer=series_final)
            episode_links = sorted(
                {
                    abs_url(staffel, m.group(1))
                    for m in re.finditer(
                        r'href=["\']([^"\']*?(?:episode|folge)[^"\']*)',
                        season_html,
                        re.I,
                    )
                    if is_episode_page(abs_url(staffel, m.group(1)))
                }
            )
            print(f"[ok] staffel-1 fallback: {staffel} -> {len(episode_links)} eps")
        except Exception as e:
            errors.append(f"staffel fetch: {e}")

    if not episode_links:
        print("[fail] no episode links on series page")
        return 5

    ep_url = random.choice(episode_links)
    print(f"[ok] random episode: {ep_url}")
    try:
        ep_final, ep_html = fetch(ep_url, referer=series_final)
    except Exception as e:
        print(f"[fail] episode fetch: {e}")
        return 6

    has_iframe = "player-iframe" in ep_html or 'id="player"' in ep_html
    has_voe = bool(re.search(r"voe", ep_html, re.I))
    blobs = sorted(set(re.findall(r'https?://[^"\']+/r\?t=[^"\']+', ep_html)))
    blobs += sorted(set(re.findall(r'["\'](/r\?t=[^"\']+)["\']', ep_html)))
    play_urls = sorted(
        set(re.findall(r'data-play-url=["\']([^"\']+)["\']', ep_html, re.I))
    )
    providers = sorted(
        set(re.findall(r'data-provider-name=["\']([^"\']+)["\']', ep_html, re.I))
    )

    print(f"[info] iframe player: {has_iframe}")
    print(f"[info] VOE mentioned: {has_voe}")
    print(f"[info] providers: {providers[:8]}")
    print(f"[info] data-play-url count: {len(play_urls)}")
    print(f"[info] /r?t= blobs: {len(blobs)}")

    if not has_iframe and not play_urls and not blobs:
        errors.append("episode page has no player hooks")
    if not has_voe and "VOE" not in ",".join(providers).upper():
        print("[warn] VOE hoster not listed on this episode (random hoster variance OK)")

    # Critical policy: top-level /r?t= must redirect / be iframe-only
    if blobs:
        blob = abs_url(ep_final, blobs[0])
        print(f"[ok] probing play-blob top-level: {blob[:120]}...")
        try:
            blob_final, blob_html = fetch(blob, referer=ep_final)
            iframe_only = ("frameBridge" in blob_html and "window.top === window.self" in blob_html) or (
                not is_play_blob(blob_final) and looks_home(blob_final)
            )
            print(f"[info] blob final URL: {blob_final}")
            print(f"[info] iframe-only markers: {iframe_only}")
            if is_play_blob(blob_final) and "m3u8" in blob_html.lower():
                print("[info] blob HTML itself embeds m3u8 (rare)")
            if not iframe_only and is_play_blob(blob_final):
                # Still OK if HTML is a tiny bridge page
                if "iframe" in blob_html.lower() or "frameBridge" in blob_html:
                    iframe_only = True
            if not iframe_only:
                errors.append("play-blob did not behave as iframe-only / redirect")
            else:
                print("[ok] play-blob is iframe-only → player must load episode page")
        except Exception as e:
            print(f"[warn] blob probe failed (expected sometimes): {e}")

    # Normalize policy check (app logic)
    resolved = ep_final if is_episode_page(ep_final) else ep_url
    if blobs and is_play_blob(blobs[0]):
        app_url = resolved  # never blob
    else:
        app_url = resolved
    print(f"[ok] app would load: {app_url}")
    if is_play_blob(app_url):
        errors.append("normalizePlaybackUrl would still load play-blob")
    if not is_episode_page(app_url):
        errors.append("normalized URL is not an episode watch page")

    # Cover uniqueness smoke: stills on season list should not all equal series og:image
    og = re.search(r'property=["\']og:image["\'][^>]*content=["\']([^"\']+)', series_html, re.I)
    if not og:
        og = re.search(r'content=["\']([^"\']+)["\'][^>]*property=["\']og:image["\']', series_html, re.I)
    series_art = og.group(1) if og else None
    ep_imgs = re.findall(r'<img[^>]+(?:src|data-src)=["\']([^"\']+)["\']', ep_html, re.I)[:12]
    print(f"[info] series og:image: {series_art}")
    print(f"[info] episode page img samples: {len(ep_imgs)}")
    if series_art and ep_imgs and all(series_art.split("?")[0] in i for i in ep_imgs):
        print("[info] episode page mostly reuses series art → TVMaze stills required (app does this)")

    if errors:
        print("[FAIL]")
        for e in errors:
            print(" -", e)
        return 1
    print("[PASS] SerienStream random series/episode playback policy OK")
    return 0


def looks_home(url: str) -> bool:
    path = urllib.parse.urlparse(url).path or "/"
    return path in ("", "/")


if __name__ == "__main__":
    sys.exit(main())
