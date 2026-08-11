# Verflixed – Installation auf Fire TV Stick (Silk Browser)

## Datei

- APK: `dist/Verflixed-FireTV.apk` (aktuell **1.6.0** / versionCode 15)
- Silk-Ordner: `dist/silk/Verflixed-FireTV.apk`
- Helper: `dist/serve-for-firetv.sh`

### Neu in 1.6.0

- **Serien + Filme**: getrennte Quellen-URLs, Serien/Filme-Schalter, Favoriten getrennt
- **Filmpalast-kompatibel**: Katalog `/movies/new`, Suche `/search/title/…`, Detail `/stream/…`
- **Hoster-Fallback**: VOE HD (Share-Redirect + Soft-Redirect) → Vidara `/api/stream` → weitere
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
- Downloads: siehe 

### Neu in Desktop-Webapp 1.4.8

- **Background VOE-Capture**: kein sichtbarer Player-iframe – Frame-Tree + Observer lesen `/e/` aus iframe-Document/URL (Host egal, Proxys rotieren)
- **Multi-Episode**: AniWorld vollautomatisch; SerienStream `/r?t=` → hidden capture (Captcha nur wenn Gate es verlangt)
- Self-Test: `node scripts/test-multi-voe-proxies.js`
- Bundle: `dist/webapp/Verflixed-Webapp-macOS-Windows.zip`
  - Download: https://gofile.io/d/2Ed5f4
  - Spiegel: https://tmpfiles.org/wCwvO9xdNMt2/verflixed-webapp-macos-windows.zip

### Neu in Desktop-Webapp 1.4.7

- **SerienStream VOE-Capture**: `/r?t=`-Play-Blobs; iframe-`/e/`-Link capturieren → m3u8
- Self-Test: `node scripts/test-lioness-voe-capture.js` (Special Ops: Lioness S01E01)
- Bundle: `dist/webapp/Verflixed-Webapp-macOS-Windows.zip`

### Neu in Desktop-Webapp 1.4.6

- **APK-Parity**: Catalog-/Detail-Parser wie Android (`episode-row`, `card-mini`, SiteImages 2x-desktop/JPEG)
- **Cover**: direkt von der Seite; Lazy Detail-Resolve; Fallback **TVMaze ohne API-Key**
- **Namen**: echte Serien-/Episodentitel (kein Rating-/Zeitstempel-Müll, keine nackten „1/2/3“)
- **Layout**: responsive Fire-TV-ähnliche Shelves, Hero-Detail, kompakte Episode-Rows

### Neu in 1.4.5

- **VOE → m3u8**: wie xstream/Cloudstream – VOE-Embed wird zu HLS geclaimed (ExoPlayer), statt VOE-iframe
- **Weniger SerienStream-Captcha**: sobald VOE-URL da ist (Cookies/Unlock), direkt VOE/m3u8 statt Gate-UI; Episode-Seite nur noch Bootstrap
- **Desktop-Webapp**: Electron für macOS/Windows (`Start-macOS.command` / `Start-Windows.bat`)
  - Voraussetzung: Node.js LTS; Empfohlen Base-URL `https://aniworld.to`

### Neu in 1.4.4

- **Layouts/UI**: kompakte Home/Detail-Layouts für Fire-TV 720p, 16:9 Poster/Stills, juicier Focus-Scale
- **Player**: lädt immer die Episode-Seite (nie `/r?t=` top-level); Captcha/Gate bleibt nutzbar; HLS→ExoPlayer; Mode-Bar HLS/Web über dem WebView
- **Vorschaubilder**: keine Serien-Art mehr auf jede Episode gestempelt; TVMaze-Stills pro Episode bevorzugt

### Neu in 1.4.3

- **Browse/Search**: kein Image-/Meta-Disk-Cache (Glide `DiskCacheStrategy.NONE`), nur smart lazy Cover-Resolve ohne Room/TVMaze

### Neu in 1.4.2

- **Fire-TV Kachel-Logo**: blaues „V“-Favicon korrekt in `mipmap` + Banner; Icon/Banner auch an der Leanback-Launcher-Activity (behebt rotes/altes Abschnitts-Logo in „Meine Apps“)

### Neu in 1.4.1

- **High-Res Cover**: SerienStream `2x-desktop` Channel/Backdrop (JPEG), lazy ohne Browse-DB-Cache
- **HLS-Claim**: Playlist wird vor dem Einbetten geclaimed; bei Problemen Buttons „HLS Player“ / „Web-Player“
- **Fire-TV D-Pad**: nested Scroll fix, Fokus-Kette, Profil→Bearbeiten per Fernbedienung

### Player-Hinweis

- Direkte `.m3u8`-URLs im Katalog → ExoPlayer mit TV-Controls
- Episode-Watch-Seiten → WebView (Captcha/VOE), dann HLS-Intercept → ExoPlayer
- `/r?t=` Play-Blobs sind iframe-only und werden **nicht** als Top-Level-URL geladen
- SerienStream-Gates brauchen ggf. Captcha im WebView; ohne gelöstes Gate kommt kein HLS
## Variante A – Silk Browser (empfohlen)

### 1) Unbekannte Quellen erlauben (Fire TV)

1. **Einstellungen** öffnen  
2. **Mein Fire TV** (ggf. ganz nach unten)  
3. **Entwickleroptionen**  
   - Falls nicht sichtbar: **Mein Fire TV → Info → „Fire TV Stick“ 7× klicken**
4. **Apps aus unbekannten Quellen** → **Silk Browser** → **An**

### 2) APK im WLAN bereitstellen (PC)

Auf dem PC (gleicher WLAN wie Fire TV):

```bash
cd /pfad/zu/Verflixed/dist
./serve-for-firetv.sh 8080
```

Der Script zeigt eine URL wie:

`http://192.168.x.x:8080/Verflixed-FireTV.apk`

### 3) Auf dem Fire TV installieren

1. **Silk Browser** öffnen  
2. URL aus Schritt 2 eintippen (exakt)  
3. Download starten  
4. Nach dem Download: **Öffnen** → **Installieren**  
5. App **Verflixed** starten  

### 4) Erster Start

1. **Base-URL** deiner Mediathek eingeben (z. B. `http://192.168.1.20:8080`)  
2. Optional: TMDb API Key  
3. **Weiter**  

Demo-Katalog:

```bash
cd Verflixed/sample
python3 -m http.server 8080
```

Base-URL auf dem Stick: `http://DEINE-PC-IP:8080`

## Variante B – ADB

```bash
adb connect FIRE_TV_IP:5555
adb install -r Verflixed-FireTV.apk
```

## Funktionen nach Favorisieren

- Metadaten/Poster cachen (optional TMDb)
- **Alle Episoden-Player-Links** sammeln (m3u8 + VOE-URLs)
- Fortschritt / gesehen / Weiterschauen
- Auto-Next über Episoden **und Staffeln**
- Prefetch der nächsten Episode während der Wiedergabe
