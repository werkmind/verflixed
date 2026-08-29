# Design Decisions

<!-- Lazy-loaded — loaded only when a task requires prior rationale or decision reference.
     Append-only log. Never delete entries; mark superseded ones with a note.
     Format: ### YYYY-MM-DD — {title} followed by **Status**: accepted | rejected | tried -->

### 2026-08-28 — Design-Vision "Midnight Cinema" vorgeschlagen

**Status**: accepted (Proposal-Phase, Umsetzung ausstehend)

Vollständiges Redesign-Konzept für Verflixed als FireTV-App erstellt (Release-Basis v1.25.0, versionCode 60). Ausgearbeitetes Proposal mit interaktivem Mockup: `.ui-craft/proposals/2026-08-28-tv-redesign/proposal.html`

Kern-Entscheidungen:
- **Glow-Fokus als Signature Element** - fokussierte Karte wächst (scale 1.1, 350ms Spring) mit weißem Kern-Ring + Eis-Halo (rgba(92,168,255,.38)) + Inset-Licht; Geschwister dimmen auf 62%. Löst den flachen Weiß-Ring aus v1.25 ab. Grund: TV-UI lebt vom Fokus - er muss emotionaler Anker sein, nicht nur funktionaler Rahmen.
- **Amber #FFB454 als funktionale Progress-Farbe** - "Weiterschauen"-Reihe zeigt Fortschritt/Restzeit in Bernstein (Prime/Disney+-Grammatik). Blau bleibt Brand, Grün nur noch für "gesehen"-Haken. Grund: zwei konkurrierende Blautöne + Grün-Progress ohne Semantik-Verbindung waren Ist-Zustand.
- **Hero-Ausblutung** - Backdrop läuft full-bleed, Scrim-Gradient über 60% Höhe statt harter Kante zum Feed. Grund: Tiefe entsteht durch Licht-Führung, nicht durch Layout-Grenzen.
- **Fokus-Chip mit Kontext** - unter fokussierter Karte Glas-Chip mit Titel + Episode + Restdauer + Aktion ("OK = Fortsetzen"). Grund: 10-foot-UX - Nutzer sehen vor dem Tastendruck genau was passiert.
- **Outfit als durchgängige Familie** - liegt bereits in scripts/brand/fonts, wurde aber nur für Wordmark genutzt. Hero 42sp/800 mit -2.8% Tracking. Grund: Konsistenz Splash→App, Display-Qualität der bereits lizenzierten Font nutzen.
- Alles im View-System (XML) abbildbar, kein Compose - bewusste Entscheidung gegen Migrationsrisiko, bestehende drawables/styles werden erweitert.

Alternativen erwogen:
- Compose-Migration - verworfen: Risiko/Zeit nicht gerechtfertigt, View-System liefert gleiche visuelle Qualität bei TV-Performance
- Glassmorphism-Indizes auf Karten - verworfen: RV-Performance auf Fire TV Stick (Blur teuer), Glas nur als Overlay-Chip auf Fokus

Rollout-Plan in 4 PRs: (1) Token + Glow-Fokus, (2) Hero-Scrim + Amber-Progress, (3) Fokus-Chip + Motion, (4) Detail/Player-Ableitung + QA auf echtem Stick.
