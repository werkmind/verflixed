# Verflixed (Fire TV + Desktop Webapp)

Android-TV-/Fire-TV-Client und **Desktop-Webapp** (macOS/Windows).

- Plex-/Netflix-ähnliche Browse-UI
- Favoriten / Watch-Progress (Android)
- **Serien + Filme** (dual Base-URL, Serien/Filme-Schalter)
- **VOE → m3u8 Claim** in den eigenen Player (ExoPlayer / hls.js); Filme: VOE dann Vidara
- AniWorld-Redirects ohne Captcha; SerienStream-Gate nur wenn nötig
- **Live-Site-Suche** (Schnellsuche-API / Filmpalast title search)

## Fire TV APK

- APK: `dist/Verflixed-FireTV.apk` (**1.6.0** / versionCode 15)
- Install: [`INSTALL.md`](INSTALL.md)

## Desktop Webapp (macOS / Windows)

- Bundle: `dist/webapp/Verflixed-Webapp-macOS-Windows.zip` (**1.5.1**, Live-Suche)
- Anleitung: [`webapp/README.md`](webapp/README.md)
- Start: `Start-macOS.command` bzw. `Start-Windows.bat` (Node.js LTS nötig)

```bash
cd webapp
npm start
# oder Self-Test:
node scripts/test-site-search.js
node scripts/test-voe-playback.js
```

## Build Android

```bash
export ANDROID_HOME=~/android-sdk
./gradlew :app:assembleRelease
```
