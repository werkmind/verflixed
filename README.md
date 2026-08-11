# Verflixed (Fire TV + Desktop Webapp)

Android-TV-/Fire-TV-Client und **Desktop-Webapp** (macOS/Windows).

- Plex-/Netflix-ähnliche Browse-UI
- **Serien + Filme** (getrennte Quellen-URLs, Tabs, Favoriten)
- Live-Site-Suche (Schnellsuche / Filmpalast title-search)
- Favoriten / Watch-Progress (pro Profil)
- **VOE → m3u8** (Share-Redirect + Hintergrund-Resolve); Fallback **Vidara**
- AniWorld-Redirects ohne Captcha; SerienStream-Gate nur wenn nötig

## Fire TV APK

- APK: `dist/Verflixed-FireTV.apk` (**1.6.0** / versionCode 15)
- Install: [`INSTALL.md`](INSTALL.md)

## Desktop Webapp (macOS / Windows)

- Bundle: `dist/webapp/Verflixed-Webapp-macOS-Windows.zip` (**1.6.0**)
- Anleitung: [`webapp/README.md`](webapp/README.md)
- Start: `Start-macOS.command` bzw. `Start-Windows.bat` (Node.js LTS nötig)

```bash
cd webapp
npm start
# Self-Tests:
node scripts/test-site-search.js
node scripts/test-filmpalast-voe-bg.js
node scripts/test-voe-playback.js
```

## Quellen (Beispiel)

- Serien: `https://aniworld.to` oder `https://serienstream.cx`
- Filme: `https://filmpalast.to`

## Build Android

```bash
export ANDROID_HOME=~/android-sdk
./gradlew :app:assembleRelease
```
