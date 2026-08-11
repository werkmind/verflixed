# Verflixed – Installation auf Fire TV Stick

## Downloads (1.6.3)

| Datei | Link |
|---|---|
| **APK Fire TV (kurz)** | https://clck.ru/3VBiUd |
| **Update (kurz)** | https://clck.ru/3VBiPU |
| APK Gofile | https://gofile.io/d/R2PXAw |
| APK Direkt (72h) | https://litter.catbox.moe/tjxvr3.apk |

Lokal: `dist/Verflixed-FireTV.apk` (versionCode **19** / **1.6.3**)

> Hinweis: `clck.ru` + litter sind unveränderlich. Ab 1.6.2 → 1.6.3 bitte **einmal neu installieren** (alter Update-Link `3VBgFW` zeigt weiter auf 1.6.2). Danach nutzt die App `3VBiPU` als Update-Manifest.

---

## Fire TV – Installation (Downloader)

1. Apps → **Downloader** (AFTVnews) installieren  
2. Unbekannte Quellen für Downloader erlauben  
3. URL eingeben:
   ```text
   https://clck.ru/3VBiUd
   ```
4. Installieren → **Verflixed** starten

### Menü-Taste (3 Balken)

Auf Poster/Episode: **als gesehen**, **Favorit**, **ungesehen**, **Metadaten neu laden**

### Player – Zurück

1. Zurück → Custom Controls ausblenden (falls sichtbar)  
2. Nochmal Zurück (innerhalb 2s) → Stream beenden  

### Defaults

- Serien: `https://serienstream.cx`
- Filme: `https://filmpalast.to`

---

## Neu in 1.6.3

- **Filmpalast / Firestream**: nativer Resolve (`/e/` → `/api/.../resolve`) – **kein** WebView, kein Captcha-SCAM, kein iframe
- Filme scheitern mit VF-302 statt Web-Player-Fallback
- Poster/Episode: OK / Enter startet (nicht nur Fokus)
- Doppel-Zurück beendet den Stream (erst Controls)
- Redundantes „Film“-Label entfernt
- Portrait-Poster, CenterCrop, Buttons ohne weißen Material-Tint, weniger Overflow

### 1.6.2

- Echte Episodentitel, VOE multi-proxy, Library/Kalender/Neu, Menü-Taste
