# Design Tokens - Verflixed TV ("Midnight Cinema")

## Colors

Basis: bestehende `app/src/main/res/values/colors.xml`. V2-Änderungen fett.

- Hintergrund: `#05060A` (bestehend `sv_bg_deep`, wird Standard)
- Surface: `#12161F` (neu, ersetzt `sv_surface #141820` - kälter, dunkler)
- Surface elevated: `#1A2029` (ersetzt `#1C222E`)
- Brand-Akzent (Eis): `#2F80FF` (bestehend, Wordmark-Anker)
- **Eis-hell (Hover/Glow): `#5CA8FF`** (neu)
- **Amber (Progress/Restzeit): `#FFB454`** (neu, funktional - nur für "Weitermachen"-Semantik)
- Success/Watched: `#46D369` (bestehend, nur Haken-Marker, kein Progress mehr)
- Text primary: `#F2F5FA` (leicht wärmer als #FFF)
- Text secondary: `#A7B0BF` (ersetzt `#C5CDD8` - dunkler, mehr Stufe)
- Text muted: `#6B7686` (ersetzt `#8B95A6`)
- Card stroke: `white/8%` (bestehend `sv_card_stroke`)
- **Fokus-Halo: `#5CA8FF` bei 38% Alpha als Glow-Shadow** (neu)
- **Fokus-Ring: `#FFFFFF` 2.5dp + Inset-Licht `#D8E9FF` bei 28%** (neu)

## Typography

- Familie: **Outfit** (liegt bereits in `scripts/brand/fonts/`, TTFs einbetten via fontFamily)
- Hero-Titel: 42sp, 800, letterSpacing -2.8% (bestehend SvHeroTitle 20sp wird gestuft)
- Row-Titel: **17sp, 700, -1%** (vorher 16sp Medium - deutliche Hierarchie)
- Card-Meta: 12sp, 500, secondary
- Body/Dialoge: 14-16sp, 400
- Labels/Eyebrows: 11sp, 700, uppercase, +12% tracking (sparsam, max 1 pro View)

## Spacing

4dp Grid. Screen-Rand 44dp (was 40dp), Row-Gap 26dp, Card-Gap 12dp. Top-Bar 64dp.

## Radius

- Poster: 12dp (vorher kleiner)
- Wide-Cards: 16dp
- Buttons: 10dp
- Chips/Pills: 999dp
- Modals/Dialoge: 14dp (bestehend)

## Shadows / Elevation

- Karte idle: nur 1dp stroke, kein Schatten (Performance in RV)
- Karte fokussiert: scale 1.10 + translateY -4dp + Glow (0 8dp 32dp rgba(92,168,255,.38)) + weißer 2.5dp Ring. Implementierung: View-Overlay oder animierter Gradient-Drawable, NICHT statische Schichten (RV-Performance)
- Hero: Scrim-Gradient über 60% Höhe (bestehend erweitern zu `bg_hero_scrim_v2`)

## Motion

- Fokus-Ring: 80ms (instant confirm)
- Fokus-Scale: 350ms, OvershootInterpolator(1.36)-ähnliche Spring-Kurve
- Sibling-Dim: 280ms ease-out, opacity 1 → 0.62
- Hero-Crossfade: 400ms
- Scrim-Fade: 200ms
- Activity-Übergänge: 280/220ms (bestehende vf_slide/vf_fade behalten, Timing anpassen)
- **Reduce Motion: alle Transformationen 0ms, nur Farbübergänge 150ms** (bestehender App-Schalter)
