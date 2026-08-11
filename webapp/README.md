# Verflixed Desktop Webapp

Lokale Desktop-App (Electron) für **macOS** und **Windows** – an den Stand der Fire-TV-APK angeglichen (**1.6.0**).

## Features

- **Serien / Filme**-Schalter mit getrennten Quellen-URLs
- Browse / Genre-Regale / **Live-Suche** (SerienStream · AniWorld · Filmpalast)
- Favoriten & Fortschritt **pro Profil** (Serien und Filme getrennt)
- **Cover** von der Seite + TVMaze-Fallback
- Episode/Film → Hoster → **VOE → m3u8** (Share-Redirect, Hidden Window) + **Vidara-Fallback**
- **SerienStream `/r?t=`**: Background-Capture, Captcha nur wenn nötig

## Schnellstart

### Variante A – Starter im Bundle

1. Zip entpacken
2. **macOS:** `Start-macOS.command` doppelklicken  
3. **Windows:** `Start-Windows.bat` doppelklicken
4. Beim ersten Start wird Electron via npm geladen (Node.js: https://nodejs.org)

### Variante B – manuell

```bash
cd webapp
npm install
npm start
```

Empfohlen:
- Serien: `https://aniworld.to` oder `https://serienstream.cx`
- Filme: `https://filmpalast.to`

## Self-Tests

```bash
cd webapp
node scripts/test-site-search.js
node scripts/test-movie-search.js
node scripts/test-filmpalast-voe-bg.js
npx electron scripts/test-filmpalast-voe-bg.js --bg
node scripts/test-catalog-parity.js
```
