# Project Brief

## Design Intent

**"Midnight Cinema"** - Verflixed soll sich anfühlen wie das dimmbare Licht eines späten Filmabends: tiefer Schwarzgrund, Inhalte die aus dem Dunkel aufleuchten, ein Fokus der wie ein Lichtstrahl wirkt. Kein Interface-Wettbewerb mit dem Content - der Bildschirm tritt zurück, die Story tritt vor. Emotion und Tiefe entstehen durch Ausbluten (Scrims), Glühen (Fokus-Halo) und atmen (Sibling-Dim), nicht durch Dekoration.

## Audience

Fire-TV-Stick-Nutzer auf dem Sofa, 10-foot-UI, reine D-Pad-Navigation (keine Touch-Targets nötig). Sie wollen sofort weiterschauen, was sie angefangen haben. Entfernungs-typisch: niedrige Lesedistanz-Aufmerksamkeit, große Typo, klare Fokussierung, keine Kompromisse bei der Fokus-Sichtbarkeit.

## Voice and Tone

Cineastisch-direkt, Deutsch,DU-Form. Knappe Handlungstexte (max 2 Zeilen im Hero). Fortschritt ehrlich kommunizieren ("21 Min. übrig", nicht "64%"). Kein Marketing-Speak in UI-Strings ("Neu bei Verflixed" statt "Exklusiv nur hier!").

## Constraints

- Android View-System (XML), kein Compose - alle Tokens über colors.xml/drawables/styles abbildbar
- Fire TV Stick Ziel: 1080p, D-Pad-Fokus, Performance über Deko (keine Blur-in-Listen, keine Schatten-Overlays in RV-Items)
- Brand: Outfit (liegt in scripts/brand/fonts), Wordmark-Gradient #D9E9FF → #6FB0FF → #2F80FF als Eis-Motiv
- Reduce-Motion-Einstellung in der App muss alle Motion-Tokens respektieren (dann nur Farb-Transitions)
- Bestehendes Akzent-Blau #2F80FF bleibt Brand-Anker; neue Amber #FFB454 ist rein funktional (Progress/Restzeit)

## Learned constraints

- 2026-08-28: Nutzer will "optimiert, modern, fluid, schön (mit Emotion und Tiefe)" als FireTV-Release. Interpretation: Glow-Fokus + Hero-Ausblutung + Sibling-Dim statt flacher Weiß-Ring. Proposal: .ui-craft/proposals/2026-08-28-tv-redesign/

## Basis

- Release: v1.25.0 (versionCode 60), "Immersives Home, Glas-Fokus"
- Analyse-Basis: Emulator VerflixedTV (1920×1080), Release-APK direkt von GitHub
