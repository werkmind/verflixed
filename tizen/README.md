# Samsung Tizen (experimentell)

Die meisten Samsung Smart TVs laufen unter **Tizen**, nicht Android.
Die Verflixed-**APK** ist daher **nicht** direkt installierbar.

## Empfehlung
Fire TV Stick / Android TV Box → APK:
https://github.com/werkmind/verflixed/releases/latest/download/Verflixed-FireTV.apk

## Dieses Verzeichnis
Enthält nur ein **Platzhalter-Widget** (`config.xml` + `index.html`) für Developer Mode.
Ein Store-fähiges Streaming-Produkt bräuchte:

1. Samsung Seller / Partner-Account
2. Signierte `.wgt` mit TV-Zertifikat
3. Eigenen CORS-/Resolver-Stack (Electron-Main gibt es auf Tizen nicht)

PS4: siehe `docs/PLATFORMS.md` – ohne Homebrew nicht machbar.
