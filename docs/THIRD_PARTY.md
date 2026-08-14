# Third-party notices

## Cloudflare Turnstile “bypass” repos

Most repos under https://github.com/topics/cloudflare-turnstile-bypass are
**Python / Playwright / paid solver APIs**. They do **not** run inside a Fire TV
WebView and are not integrated.

Verflixed instead:

1. Uses [darkryh/Cloudflare-Bypass](https://github.com/darkryh/Cloudflare-Bypass) for classic CF IUAM pages.
2. Forces a **captcha-first gate UI** on the episode page (hide SerienStream chrome,
   never open black VOE top-level) so Turnstile is actually visible and solvable with OK.
