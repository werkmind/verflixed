# Third-party notices

## Cloudflare Turnstile “bypass” repos

Most repos under https://github.com/topics/cloudflare-turnstile-bypass are
**Python / Playwright / paid solver APIs**. They do **not** run inside a Fire TV
WebView and are not integrated.

## Skip timestamps (intro / recap / credits)

Crowd-sourced **read-only** APIs (no auto-skip — the player shows a button):

- [TheIntroDB](https://theintrodb.org) — `GET https://api.theintrodb.org/v3/media` (TMDB / IMDb)
- [SkipDB](https://github.com/SkipDB-TV/skipdb) — `GET https://skipdb.tv/api/segments` (IMDb)
- [AniSkip](https://api.aniskip.com) — anime OP / ED / recap via MAL id

SmartTube’s SponsorBlock pipeline is YouTube-only and does not apply to VOE/HLS TV streams.

Verflixed instead:

1. Uses [darkryh/Cloudflare-Bypass](https://github.com/darkryh/Cloudflare-Bypass) for classic CF IUAM pages.
2. Forces a **captcha-first gate UI** on the episode page (hide SerienStream chrome,
   never open black VOE top-level) so Turnstile is actually visible and solvable with OK.
