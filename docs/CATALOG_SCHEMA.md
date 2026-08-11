# Verflixed Catalog Schema

Die App lädt beim Start (und bei Refresh) den Katalog von deiner **Base-URL**.

## Reihenfolge der Endpunkte

1. `{baseUrl}/catalog.json`
2. `{baseUrl}/api/catalog.json`
3. `{baseUrl}/api/catalog`
4. `{baseUrl}` selbst (JSON oder HTML-Index)

## JSON (empfohlen)

```json
{
  "series": [
    {
      "id": "meine-serie",
      "title": "Meine Serie",
      "poster": "https://…/poster.jpg",
      "backdrop": "https://…/backdrop.jpg",
      "overview": "Kurzbeschreibung",
      "year": 2020,
      "tmdb_id": 12345,
      "seasons": [
        {
          "number": 1,
          "title": "Staffel 1",
          "episodes": [
            {
              "number": 1,
              "title": "Episode 1",
              "stream": "https://…/episode1/index.m3u8"
            },
            {
              "number": 2,
              "title": "Episode 2 (VOE)",
              "stream": "https://voe.sx/e/deine-id"
            }
          ]
        }
      ]
    }
  ]
}
```

### Felder

| Feld | Pflicht | Beschreibung |
|------|---------|--------------|
| `series[].id` | ja | stabile ID |
| `series[].title` | ja | Anzeigename |
| `series[].poster` | nein | Poster-URL |
| `series[].seasons[].episodes[].stream` | für Playback | direkte **m3u8**-URL **oder** VOE-Player-URL (`https://voe.sx/e/…`) |
| `series[].seasons[].episodes[].stream_page` | alternativ | Seite mit m3u8 **oder** VOE-Link |

## Playback

- `.m3u8` → ExoPlayer (HLS)
- VOE-URL (`voe.sx`) → WebView (offizieller Player)
- **Play-Blob** `/r?t=…` aus HTML (`data-play-url`) → WebView lädt den Link; der Player resolved den Embed selbst
- Bevorzugung: VOE + Deutsch, wenn Attribute vorhanden
- Popups/`window.open` bleiben blockiert

## HTML (Fallback)

Wenn kein JSON gefunden wird, parst die App eine Serien-Liste und Detailseiten.
Für Playback sollten direkte `.m3u8`- oder VOE-Links im Markup stehen.

## Favoriten / Cache

Beim Favorisieren werden Serie + Metadaten (optional TMDb) lokal gespeichert.
Direkte HLS-URLs können vorab gecached werden; VOE-Seiten öffnet der WebView on demand.
