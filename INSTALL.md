# Verflixed – Installation auf Fire TV Stick

## Downloads (1.6.1)

| Datei | Link |
|---|---|
| **APK Fire TV (kurz)** | https://clck.ru/3VBfVg |
| **Update (kurz)** | https://clck.ru/3VBfTs |
| APK Gofile | https://gofile.io/d/w1RrZe |
| APK Direkt (72h) | https://litter.catbox.moe/e3csn1.apk |

Lokal im Repo:
- `dist/Verflixed-FireTV.apk` (versionCode **17** / **1.6.1**)
- `dist/silk/Verflixed-FireTV.apk`
- Helper: `dist/serve-for-firetv.sh`

---

## Fire TV – Installation (empfohlen: Downloader-App)

### A) Mit „Downloader“ von AFTVnews (einfachste Variante)

1. Auf dem Fire TV Stick: **Apps** → Suche → **Downloader** (von AFTVnews) installieren.
2. Einstellungen → **Mein Fire TV** → **Entwickleroptionen**
   - **Apps aus unbekannten Quellen** → für **Downloader** erlauben
3. Downloader öffnen → URL:
   ```text
   https://clck.ru/3VBfVg
   ```
4. Download → **Installieren** → Apps → **Verflixed** starten.

### B) Mit Silk Browser

1. Unbekannte Quellen für Silk erlauben.
2. Öffnen: `https://clck.ru/3VBfVg` → installieren.

### C) Vom PC im Heimnetz

```bash
./dist/serve-for-firetv.sh
```

```text
http://DEINE-PC-IP:8080/Verflixed-FireTV.apk
```

---

## Erste Einrichtung

Defaults sind gesetzt:

1. **Serien**: `https://serienstream.cx`
2. **Filme**: `https://filmpalast.to`
3. Oben **Serien** / **Filme** umschalten
4. Optional: Profil → Favoriten getrennt je Medientyp

### Fernbedienung

- **OK**: auswählen · **← →**: Seek ±10s · **Back**: zurück
- Suche: Netflix-Keyboard links + Live-Treffer rechts (kein System-IME-Overlay)
- Episode **Markieren**-Badge per D-Pad rechts erreichbar

### In-App-Update

```text
https://clck.ru/3VBfTs
```

---

## Neu in 1.6.1

- Filme-URL wird zuverlässig gespeichert (Defaults beide URLs)
- VF-201 / Live-Suche öffnet Treffer auch ohne Katalog-Eintrag
- Markieren + Action-Buttons per D-Pad erreichbar
- Dichteres Layout (mehr Inhalt sichtbar)
- Antifilter entfernt
- Netflix-Style Such-Keyboard + AJAX Live-Results
- Keine eigenen Nav-Sounds (Fire-TV-System)
- Fluidere Apple-TV-ähnliche Focus-Animationen

### Neu in 1.6.0

- Serien + Filme, Filmpalast, VOE→Vidara-Fallback
