# Verflixed – Plattformen

## Fire TV / Android TV
**Unterstützt.** APK sideloaden:
https://github.com/werkmind/verflixed/releases/latest/download/Verflixed-FireTV.apk

## Desktop (Windows / macOS / Linux)
**Unterstützt.** Electron-Webapp (Design/Features ab **1.7.5** wie die APK):
https://github.com/werkmind/verflixed/releases/latest/download/Verflixed-Webapp.zip

## PlayStation 4
**Nicht möglich ohne Homebrew / Sony-Partnerschaft.**

Die PS4 erlaubt keine einfache Installation eigener Apps (kein Sideload wie bei Fire TV).
Alles, was „eigene App auf der PS4“ heißt, braucht entweder:

- offizielle PlayStation-Store-Veröffentlichung (Lizenzvertrag mit Sony), oder
- Jailbreak / Homebrew (nicht „einfach so“ und oft firmware-gebunden).

Laut Vorgabe („ohne Homebrew“) **lassen wir PS4 weg**.

## Samsung Smart TV
**Die Fire-TV-APK läuft nicht nativ auf Samsung-Tizen.**

Die meisten Samsung-TVs nutzen **Tizen OS** (`.wgt`/`.tpk`), nicht Android. APKs sind inkompatibel.

### Empfohlen (funktioniert sofort)
1. **Fire TV Stick / Android TV Box** an den Samsung-TV (HDMI)
2. Verflixed-APK darauf installieren

### Optional: Tizen-Sideload (Developer Mode)
Unter `tizen/` liegt ein experimentelles Web-Widget-Gerüst für Samsung Developer Mode.
Das ist **kein Store-Release** und braucht:

- Samsung-Account + Developer Mode am TV (Apps → 12345)
- Zertifikat / Tools (Tizen Studio oder z. B. Community-Installer)
- Einschränkungen: ohne Electron-Hauptprozess ist Stream-Auflösung (VOE/CORS) deutlich schwieriger

Ein „einfach aus dem Samsung Store installieren“-Build ist ohne Samsung-Seller-Account und Review **nicht** freigebbar.
