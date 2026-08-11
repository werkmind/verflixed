# Verflixed Desktop Webapp

Lokale Desktop-App (Electron) für **macOS** und **Windows** – an den Stand der Fire-TV-APK angeglichen.

## Features

- Browse / Genre-Regale / **Live-Suche** (SerienStream suggest · AniWorld ajax · Mirror)
- **Cover** direkt von der Seite (`2x-desktop` / JPEG), Lazy-Resolve, Fallback **TVMaze ohne API-Key**
- Serien- & Episodentitel wie in der APK (`episode-row`, keine Rating-/„1/2/3“-Artefakte)
- Episode → Hoster → **VOE → m3u8 Claim** + `hls.js`
- **SerienStream `/r?t=`**: verstecktes Background-Capture – iframe-DOM/Frame-URL (beliebiger VOE-Proxy), Captcha nur wenn nötig
- Responsives Desktop-Layout (Shelves, Hero-Detail, Episode-Rows)
- Suche hijackt die Seiten-Schnellsuche (nicht nur den Home-Katalog)

## Schnellstart

### Variante A – Starter im Bundle

1. Zip entpacken
2. **macOS:** `Start-macOS.command` doppelklicken  
   (ggf. Rechtsklick → Öffnen, wenn Gatekeeper blockiert)
3. **Windows:** `Start-Windows.bat` doppelklicken
4. Beim ersten Start wird Electron via npm geladen (Node.js nötig: https://nodejs.org)

### Variante B – manuell

```bash
cd webapp
npm install
npm start
```

Empfohlene Base-URL: `https://aniworld.to` (VOE-Redirect ohne Captcha).  
Alternativ: `https://serienstream.cx`

## Self-Tests

```bash
cd webapp
node scripts/test-site-search.js
node scripts/test-catalog-parity.js
node scripts/test-voe-playback.js
```
