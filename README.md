# Verflixed (Fire TV + Desktop Webapp)

Android-TV-/Fire-TV-Client und **Desktop-Webapp** (macOS/Windows) – **1.6.0**.

- Plex-/Netflix-ähnliche Browse-UI
- **Serien + Filme** (getrennte Quellen-URLs, Tabs, Favoriten)
- Live-Site-Suche · VOE→m3u8 · Vidara-Fallback
- Multi-Profil (Favoriten / Fortschritt / Cache)

## Downloads

| | Link |
|---|---|
| **APK Fire TV** | https://gofile.io/d/Y6ykVA |
| APK Spiegel (72h) | https://litter.catbox.moe/cndwtx.apk |
| **Webapp macOS/Windows** | https://gofile.io/d/03TBCj |
| Webapp Spiegel | https://tmpfiles.org/wFwJOH8p8Lif/verflixed-webapp-macos-windows.zip |

**Fire-TV-Installation:** siehe [`INSTALL.md`](INSTALL.md) (Downloader-App empfohlen).

## Lokal

- APK: `dist/Verflixed-FireTV.apk` (versionCode **15**)
- Webapp: `dist/webapp/Verflixed-Webapp-macOS-Windows.zip`
- Start Webapp: `Start-macOS.command` / `Start-Windows.bat`

```bash
cd webapp && npm start
# Tests:
node scripts/test-site-search.js
node scripts/test-movie-search.js
node scripts/test-filmpalast-voe-bg.js
```

## Quellen (Beispiel)

- Serien: `https://aniworld.to` oder `https://serienstream.cx`
- Filme: `https://filmpalast.to`

## Build Android

```bash
export ANDROID_HOME=~/android-sdk
./gradlew :app:assembleRelease
```
