# Verflixed – Installation auf Fire TV Stick

## Downloads (1.6.0)

| Datei | Link |
|---|---|
| **APK Fire TV** | https://gofile.io/d/Y6ykVA |
| APK Spiegel (72h) | https://litter.catbox.moe/cndwtx.apk |
| APK Spiegel | https://tmpfiles.org/wgw0Oc8n8qQv/verflixed-firetv.apk |
| **Webapp macOS/Windows** | https://gofile.io/d/03TBCj |
| Webapp Spiegel | https://tmpfiles.org/wFwJOH8p8Lif/verflixed-webapp-macos-windows.zip |

Lokal im Repo:
- `dist/Verflixed-FireTV.apk` (versionCode **15** / **1.6.0**)
- `dist/silk/Verflixed-FireTV.apk`
- `dist/webapp/Verflixed-Webapp-macOS-Windows.zip`
- Helper: `dist/serve-for-firetv.sh`

---

## Fire TV – Installation (empfohlen: Downloader-App)

### A) Mit „Downloader“ von AFTVnews (einfachste Variante)

1. Auf dem Fire TV Stick: **Apps** → Suche → **Downloader** (von AFTVnews) installieren.
2. Einstellungen → **Mein Fire TV** → **Entwickleroptionen**
   - **Apps aus unbekannten Quellen** → für **Downloader** erlauben  
   - (falls sichtbar) **ADB-Debugging** kann aus bleiben
3. Downloader öffnen → in die URL-Zeile einfügen:
   ```text
   https://gofile.io/d/Y6ykVA
   ```
   oder Direktlink:
   ```text
   https://litter.catbox.moe/cndwtx.apk
   ```
4. Download starten → wenn fertig **Installieren** tippen.
5. Nach der Installation: **Apps** → **Meine Apps** → **Verflixed** starten.

### B) Mit Silk Browser

1. Fire TV: Entwickleroptionen → **Apps aus unbekannten Quellen** → **Silk Browser** erlauben.
2. Silk öffnen und eine der APK-URLs aufrufen (siehe oben).
3. Datei herunterladen → Benachrichtigung / Downloads → Installieren.

### C) Vom PC im Heimnetz (ohne Cloud)

Auf dem PC im StreamVault-Ordner:

```bash
./dist/serve-for-firetv.sh
```

Im Silk-/Downloader dann z. B.:

```text
http://DEINE-PC-IP:8080/Verflixed-FireTV.apk
```

---

## Erste Einrichtung in der App

1. **Serien-Quellen-URL** setzen, z. B. `https://aniworld.to` oder `https://serienstream.cx`
2. **Filme-Quellen-URL** setzen, z. B. `https://filmpalast.to`
3. Oben zwischen **Serien** und **Filme** umschalten
4. Optional: Profil anlegen → Favoriten landen getrennt je Medientyp

### Fernbedienung (TV)

- **OK** / Enter: auswählen  
- **← →**: Seek ±10s im Player  
- **Menü / Back**: zurück  
- Weiterschauen & Meine Liste auf dem Home-Tab

---

## Neu in 1.6.0

- **Serien + Filme**: getrennte Quellen-URLs, Serien/Filme-Schalter, Favoriten getrennt
- **Filmpalast-kompatibel**: Katalog `/movies/new`, Suche `/search/title/…`, Detail `/stream/…`
- **Hoster-Fallback**: VOE HD (Share-Redirect) → Vidara `/api/stream` → weitere
- Self-Tests: `node webapp/scripts/test-filmpalast-voe-bg.js`, `test-movie-search.js`

### Neu in 1.5.1

- **Live-Site-Suche**: hijack von SerienStream `/api/search/suggest?term=` und AniWorld `POST /ajax/search`
- Suche findet Titel außerhalb des Home-Katalogs (z. B. SpongeBob auf serienstream.cx)
- Self-Test: `node webapp/scripts/test-site-search.js`

### Neu in 1.5.0

- **Multi-Profil Experience**: Favoriten + Watch-Progress + Stream-Cache pro Profil
- **Staffel-Art**: Cover/Backdrop je Staffel (Site + TVMaze)
- **Player**: Auto VOE→HLS wie Desktop; keine Debug-Buttons; TV ±10s / Next / Auto-Next
- **Webapp 1.5.0**: Netflix/Plex UI, PWA/Favicon, Custom TV-Controls, Meine Liste

### Neu in Desktop-Webapp 1.4.8

- **Background VOE-Capture**: kein sichtbarer Player-iframe – Frame-Tree + Observer lesen `/e/` aus iframe-Document/URL (Host egal, Proxys rotieren)
- **Multi-Episode**: AniWorld vollautomatisch; SerienStream `/r?t=` → hidden capture (Captcha nur wenn Gate es verlangt)
- Self-Test: `node scripts/test-multi-voe-proxies.js`
- Bundle: `dist/webapp/Verflixed-Webapp-macOS-Windows.zip`

### Neu in Desktop-Webapp 1.4.7

- **SerienStream VOE-Capture**: `/r?t=`-Play-Blobs; iframe-`/e/`-Link capturieren → m3u8
- Self-Test: `node scripts/test-lioness-voe-capture.js` (Special Ops: Lioness S01E01)
- Bundle: `dist/webapp/Verflixed-Webapp-macOS-Windows.zip`

### Neu in Desktop-Webapp 1.4.6

- **APK-Parity**: Catalog-/Detail-Parser wie Android (`episode-row`, `card-mini`, SiteImages 2x-desktop/JPEG)
- **Cover**: direkt von der Seite; Lazy Detail-Resolve; Fallback **TVMaze ohne API-Key**
- **Namen**: echte Serien-/Episodentitel (kein Rating-/Zeitstempel-Müll, keine nackten „1/2/3“)
- **Layout**: responsive Fire-TV-ähnliche Shelves, Hero-Detail, kompakte Episode-Rows
