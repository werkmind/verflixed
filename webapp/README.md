# Verflixed Desktop Webapp

Lokale Desktop-App (Electron) für **macOS** und **Windows** – Stand **1.6.9** (parity mit Fire-TV-APK).

## Features

- **Serien / Filme**-Schalter mit getrennten Quellen-URLs
- Browse / Genre-Regale / **Live-Suche**
- Favoriten & Fortschritt **pro Profil**
- **DE/EN** nur auf Detail, und nur wenn mehrere Sprachen existieren
- Film-Sprachseiten (Filmpalast Sibling) + sprachsicherer Stream-Cache
- Player: Escape/Zurück blendet Controls aus, zweites Zurück beendet
- **Update-Check** über GitHub Releases (`werkmind/verflixed`)
- Cover von der Seite + TVMaze-Fallback
- VOE → m3u8 + Vidara/Firestream-Fallback

## Download (persistent)

| | Link |
|---|---|
| **Latest Webapp** | https://github.com/werkmind/verflixed/releases/latest/download/Verflixed-Webapp.zip |
| **Latest Fire TV APK** | https://github.com/werkmind/verflixed/releases/latest/download/Verflixed-FireTV.apk |
| **Update-Manifest** | https://github.com/werkmind/verflixed/releases/latest/download/verflixed-update.json |
| Releases | https://github.com/werkmind/verflixed/releases/latest |

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
