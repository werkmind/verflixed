# Verflixed (Fire TV + Desktop Webapp)

Android-TV-/Fire-TV-Client und **Desktop-Webapp** (macOS/Windows) – **1.6.1**.

- Plex-/Netflix-ähnliche Browse-UI
- **Serien + Filme** (Defaults: serienstream.cx + filmpalast.to)
- Netflix-Style Such-Keyboard · Live-Site-Suche · VOE→m3u8 · Vidara-Fallback
- Multi-Profil (Favoriten / Fortschritt / Cache)

## Downloads

| | Link |
|---|---|
| **APK Fire TV (kurz)** | https://clck.ru/3VBfVg |
| **Update (kurz)** | https://clck.ru/3VBfTs |
| APK Gofile | https://gofile.io/d/w1RrZe |
| APK Direkt (72h) | https://litter.catbox.moe/e3csn1.apk |

**Fire-TV-Installation:** siehe [`INSTALL.md`](INSTALL.md).

## Lokal

- APK: `dist/Verflixed-FireTV.apk` (versionCode **17** / **1.6.1**)
- Webapp: `dist/webapp/Verflixed-Webapp-macOS-Windows.zip`

```bash
cd webapp && npm start
./gradlew :app:assembleRelease
```

## Quellen (Default)

- Serien: `https://serienstream.cx`
- Filme: `https://filmpalast.to`
