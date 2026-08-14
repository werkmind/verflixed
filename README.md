# Verflixed

Fire TV (Android) + Desktop Webapp für Serien & Filme.

**Version 1.17.1** (versionCode 51) – Fire TV APK + Desktop-Webapp (voller APK-Port)

Plattform-Hinweise (PS4 / Samsung): [docs/PLATFORMS.md](docs/PLATFORMS.md)

## Persistent Downloads

| Asset | Kurzlink | Direct (GitHub latest) |
|---|---|---|
| **Fire TV APK** | https://clck.ru/3VBtLD | https://github.com/werkmind/verflixed/releases/latest/download/Verflixed-FireTV.apk |
| **Desktop Webapp** | https://clck.ru/3VBtLE | https://github.com/werkmind/verflixed/releases/latest/download/Verflixed-Webapp.zip |
| **Update-Manifest** | https://clck.ru/3VBtLH | https://github.com/werkmind/verflixed/releases/latest/download/verflixed-update.json |

Repo: https://github.com/werkmind/verflixed · Releases: https://github.com/werkmind/verflixed/releases/latest

Apps prüfen Updates über das GitHub-Manifest (kein Catbox-Ablauf).

## Apps

- `app/` – Android TV / Fire TV (`com.verflixed.tv`)
- `webapp/` – Electron Desktop (macOS / Windows)

## Lokal bauen

```bash
./gradlew :app:assembleDebug
bash scripts/package-webapp.sh dist/Verflixed-Webapp.zip
```

Release per Tag (`v1.7.0`) → GitHub Actions baut APK + Webapp.

## Neu in 1.7.0

- Splash: bassiger BUM-BUM + animiertes V-Logo
- Sidebar (Standard) / Topbar pro Profil; Serien & Filme als eigene Seiten
- Globale Suche mit Tab-Priorität; Library weniger Duplikate; Kacheln/Cards
- Skeleton-Loader; Episode-Ready-Dots + Hintergrund-Stream-Warmup
- Scroll zurück zur Nav; Clipping/Focus-Polish
