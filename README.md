# Verflixed

Fire TV (Android) + Desktop Webapp für Serien & Filme.

**Version 1.6.9** (versionCode 25)

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

Release per Tag (`v1.6.9`) → GitHub Actions baut APK + Webapp.

## Neu in 1.6.9

- Back: Controls ausblenden, dann Doppel-Zurück (Fire TV + Webapp)
- DE/EN nur auf Detail, nur wenn ≥2 Sprachen
- Filme: Filmpalast-Sprachseiten; sprachsicherer Cache
- Updates über GitHub Releases
