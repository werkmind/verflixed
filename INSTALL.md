# Verflixed – Installation

## Fire TV APK (1.10.1)

| | Link |
|---|---|
| **GitHub direkt** | https://github.com/werkmind/verflixed/releases/download/v1.10.1/Verflixed-FireTV.apk |
| **Latest** | https://github.com/werkmind/verflixed/releases/latest/download/Verflixed-FireTV.apk |

### 1.10.1 (APK)
- Weiterschauen versteht „als gesehen markiert“: Play springt auf die nächste Folge nach der zuletzt gesehenen
- Filme: „Neu erschienen“ (echte Release-Jahre via TMDb) steht ganz oben — nicht Plattform-Neuzugänge
- „Aus Weiterschauen entfernen“ (Home-Menü + Detail ⋯)
- „Zufällige Folge abspielen“ im Detail ⋯
- Empfehlungen bevorzugen frische Titel (Recency-Boost)

### 1.10.0 (APK)
- Animiertes „3D“-Verflixed-Logo beim Start (Canvas, scharf in jeder Auflösung)
- Neuer Startklang: Whoosh → Doppel-Bass-Impact → Shimmer (Prime/Netflix-Charakter)
- Player: Logo-Vorspann deckt Laden + WebView-Wechsel ab (Captcha hat Vorrang)
- Profilbilder: ~18 DiceBear-Stile + echte Gesichter aus der TMDb-Personen-DB
- Größerer Hero, größere Karten/Schrift, klarere Fokus-Ringe
- Schnellere Fokus-Animationen mit Overshoot, Reihen-Einblendung, Hero-Crossfade
- Einheitliche Activity-Übergänge

### 1.9.2
- TMDb fest eingebaut (öffentlicher Kodi-Scraper-Key, wie Plex — kein Nutzer-Konto)

### 1.9.1
- Keine API-Keys / kein Konto: TVMaze + Wikidata fest verdrahtet, TMDb-Feld entfernt

### 1.9.0
- Crowd-Skip: TheIntroDB + SkipDB + AniSkip (nur Buttons, kein Auto-Skip)
- Favoriten cachen alle Staffeln/Episoden; Entfernen löscht den Cache
- Detail: Hero/Episoden-Scroll gefixt, Filme ohne Fake-Episode, Progressbars
- „Das gefällt dir bestimmt“ aus Profil-Favoriten
- Schnelleres Stream-Warmup (3 parallel) + Bild-Cache

### 1.8.0
- Adaptive Abspann/Intro-Heuristik + Lernen
