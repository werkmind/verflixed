---
# Android TV build. Colours are Android #AARRGGBB (alpha first), exactly as in
# app/src/main/res/values/colors.xml. Sizes are dp and sp as the resources state them.
name: Verflixed
description: Private Android TV app for series and films, lit by the artwork of the title in focus.
colors:
  # Primary: the one accent
  accent: "#FF2F80FF"            # sv_accent, sv_progress
  accent-soft: "#332F80FF"       # sv_accent_soft
  glow: "#FF4D95FF"              # sv_glow
  # Focus: lit white and its ink
  focus-white: "#FFFFFFFF"       # sv_focus_ring, sv_focus_fill
  focus-lit-end: "#FFE3E9F5"     # literal in the focused button drawables
  focus-pressed: "#FFC9CDD4"     # sv_focus_pressed
  ink: "#FF0A0B0E"               # sv_ink
  # Ground and solid surfaces
  ground: "#FF050608"            # sv_bg_deep
  ground-light: "#66163A7A"      # literal in bg_app, start of the radial light
  ground-light-far: "#260F2247"  # literal in bg_app, middle of the radial light
  surface: "#FF16181D"           # sv_surface
  surface-elevated: "#FF24272E"  # sv_surface_elevated
  hairline: "#FF2E323A"          # sv_card_stroke
  artwork-ground: "#FF111318"    # sv_poster_bg
  skeleton: "#FF14161B"          # sv_skeleton
  # Glass
  glass: "#2EFFFFFF"             # sv_glass
  glass-strong: "#47FFFFFF"      # sv_glass_strong
  glass-edge: "#3DFFFFFF"        # sv_glass_edge
  panel-glass: "#1FFFFFFF"       # literal in bg_panel
  panel-edge: "#24FFFFFF"        # literal in bg_panel
  progress-track: "#66FFFFFF"    # sv_progress_track
  # Text
  text-primary: "#FFF4F5F7"      # sv_text_primary, sv_watched
  text-secondary: "#FFB4B9C3"    # sv_text_secondary
  text-muted: "#FF8D939E"        # sv_text_muted
  # Scrims, all the ground colour at a stated alpha
  badge-scrim: "#E6050608"       # sv_accent_badge
  scrim-strong: "#F2050608"      # sv_scrim_strong
  scrim-mid: "#B8050608"         # sv_hero_scrim_mid
  backdrop-dim: "#59050608"      # sv_backdrop_dim
  scrim-clear: "#00050608"       # sv_hero_scrim_start
typography:
  display:
    fontFamily: "Archivo (archivo_display: weight 800, width 87.5)"
    fontSize: "44sp"
    fontWeight: 800
    lineHeight: 0.95
    letterSpacing: "-0.015em"
  headline:
    fontFamily: "Archivo (archivo_display: weight 800, width 87.5)"
    fontSize: "32sp"
    fontWeight: 800
    lineHeight: 0.95
    letterSpacing: "-0.015em"
  numeral:
    fontFamily: "Archivo (archivo_display: weight 800, width 87.5)"
    fontSize: "26sp"
    fontWeight: 800
  title:
    fontFamily: "Archivo (archivo_bold)"
    fontSize: "18sp"
    fontWeight: 700
    letterSpacing: "-0.005em"
  body:
    fontFamily: "Archivo (archivo_regular)"
    fontSize: "15sp"
    fontWeight: 400
    lineHeight: 1.35
  meta:
    fontFamily: "Archivo (archivo_medium)"
    fontSize: "15sp"
    fontWeight: 500
  label:
    fontFamily: "Archivo (archivo_semibold)"
    fontSize: "15sp"
    fontWeight: 600
    letterSpacing: "0"
  tile-title:
    fontFamily: "Archivo (archivo_medium)"
    fontSize: "13sp"
    fontWeight: 500
  caption:
    fontFamily: "Archivo (archivo_regular)"
    fontSize: "12sp"
    fontWeight: 400
rounded:
  control: "8dp"   # vf_radius_control
  image: "8dp"     # vf_radius_image
  panel: "12dp"    # vf_radius
spacing:
  edge: "58dp"            # vf_edge
  edge-vertical: "30dp"   # vf_edge_vertical
  tile-gap: "14dp"
  control-gap: "10dp"
  stack: "12dp"
  section: "28dp"
  text: "8dp"
  text-tight: "2dp"
  focus-stroke: "3dp"     # vf_focus_stroke
components:
  button:
    backgroundColor: "{colors.glass}"
    textColor: "{colors.text-primary}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "12dp 20dp"
    height: "48dp"
  button-focused:
    backgroundColor: "{colors.focus-white}"
    textColor: "{colors.ink}"
  button-pressed:
    backgroundColor: "{colors.focus-pressed}"
    textColor: "{colors.ink}"
  button-primary:
    backgroundColor: "{colors.glass-strong}"
    textColor: "{colors.text-primary}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "12dp 20dp"
    height: "48dp"
  button-ghost-selected:
    backgroundColor: "{colors.accent-soft}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.control}"
  icon-button:
    backgroundColor: "{colors.glass}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.control}"
    padding: "12dp"
    size: "48dp"
  nav-tab:
    textColor: "{colors.text-secondary}"
    typography: "{typography.meta}"
    rounded: "{rounded.control}"
    padding: "6dp 14dp"
    height: "40dp"
  nav-tab-selected:
    textColor: "{colors.text-primary}"
  nav-tab-focused:
    backgroundColor: "{colors.focus-white}"
    textColor: "{colors.ink}"
  season-tab:
    textColor: "{colors.text-secondary}"
    typography: "{typography.meta}"
    rounded: "{rounded.control}"
    padding: "8dp 14dp"
    height: "40dp"
  tile-poster:
    backgroundColor: "{colors.artwork-ground}"
    rounded: "{rounded.image}"
    width: "140dp"
    height: "210dp"
  tile-landscape:
    backgroundColor: "{colors.artwork-ground}"
    rounded: "{rounded.image}"
    width: "256dp"
    height: "144dp"
  tile-day:
    backgroundColor: "{colors.artwork-ground}"
    rounded: "{rounded.image}"
    width: "208dp"
    height: "117dp"
  tile-episode:
    backgroundColor: "{colors.artwork-ground}"
    rounded: "{rounded.image}"
    width: "248dp"
    height: "140dp"
  badge:
    backgroundColor: "{colors.badge-scrim}"
    textColor: "{colors.text-primary}"
    padding: "4dp 8dp"
  panel:
    backgroundColor: "{colors.panel-glass}"
    rounded: "{rounded.panel}"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.control}"
    padding: "18dp"
    height: "56dp"
  input-focused:
    backgroundColor: "{colors.surface-elevated}"
  dialog:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.panel}"
---

# Design System: Verflixed

Recorded from the shipped build on 2026-10-06. Platform: native Android TV and Fire TV, Views and resource XML, D-pad only. There is no CSS. Tokens live in `app/src/main/res/values/colors.xml`, `dimens.xml` and `themes.xml`; state colours in `res/color/`; surfaces in `res/drawable/bg_*.xml`; effects in `ui/util/`.

## Overview

**Creative North Star: "The Lit Room"**

The light in the room comes from the title you are on. Home is built in three layers. At the back, the focused title's artwork is shrunk to 40 by 22 pixels and stretched across the whole screen, so it arrives as soft coloured light on a near-black, cool ground. In the middle, the sharp hero artwork dissolves along its bottom edge into that light. At the front sit glass controls and artwork tiles. Moving the remote changes the colour of the room.

Focus is the second idea. A focused control turns white with dark ink. A focused tile gets a white frame on its artwork, grows a little and throws a blue halo. Nothing else on screen is opaque white, so the position of the remote is never in doubt from three metres away. Blue is kept for what is chosen or current: the wordmark, the active tab, a selected option, progress, today's date, a text link.

Density is low and the type is large because the viewer is on a sofa in a dark room. Artwork carries the screen; tiles have no card around them and their titles sit underneath in plain text. Screens that have no artwork (profiles, settings) stand on the same ground, lit in deep blue from the upper right.

**Key Characteristics:**
- Three layers on home: blurred room light, sharp hero that fades out at the bottom, glass and artwork in front.
- Focus is lit white; selection is blue; focus wins when both apply.
- One blue accent, one display voice (Archivo at weight 800, width 87.5).
- Artwork tiles without boxes, titles under the artwork.
- One shared content edge of 58dp for nav, hero and shelves.
- Two corner sizes: 8dp for controls and artwork, 12dp for panels.
- Motion is short (150 to 160 ms focus moves) and switches off with the system animation setting.

## Colors

A near-black cool ground, translucent white glass, one blue, and opaque white reserved for focus.

### Primary
- **Brand Blue** (`accent`, `sv_accent`): the wordmark, the 2dp underline of the active nav and season tab, the 2dp frame of a selected option, progress bars, today's date numeral, text links, the active-profile label, the loading spinner, the ring on the selected avatar.
- **Soft Blue Wash** (`accent-soft`): fill of a selected option under its blue frame, and the theme's control highlight.
- **Halo Blue** (`glow`, `sv_glow`): a lighter blue used only for the halo a focused tile throws. It is not a second accent and never fills or outlines anything.

### Neutral
- **Ground** (`ground`, `sv_bg_deep`): the base of every screen, the side rail, the status and navigation bar colour, and the colour every scrim fades into.
- **Deep Blue Room Light** (`ground-light`, `ground-light-far`): the radial light in `bg_app`, centred at 85% across and the very top, radius 75% of the screen. It is what a screen without artwork is lit by.
- **Surface and Raised Surface** (`surface`, `surface-elevated`): solid fills for things that must not show the room through them: text inputs, dialogs, player banners, the status pill. The raised tone is the focused input and the selected side-rail item.
- **Hairline** (`hairline`): 1dp edge on inputs and dialogs, list dividers.
- **Artwork Ground and Skeleton** (`artwork-ground`, `skeleton`): what a tile shows before its image arrives, and the loading blocks.
- **Glass, Dense Glass, Glass Edge** (`glass`, `glass-strong`, `glass-edge`): buttons at rest. Play uses the denser glass. All carry a 1dp glass edge.
- **Panel Glass and Panel Edge** (`panel-glass`, `panel-edge`): quieter glass for static grouping panels and the search query field.
- **Lit White, Lit White End, Pressed** (`focus-white`, `focus-lit-end`, `focus-pressed`): the focused fill. Buttons, icon buttons and ghost buttons run a top-to-bottom gradient from white to the cooler end tone; nav tabs, season tabs and plain icons use flat white. Pressed is the grey step.
- **Ink** (`ink`): text and icons on any lit control.
- **Text Primary, Secondary, Muted** (`text-primary`, `text-secondary`, `text-muted`): titles and focused labels; resting labels, body copy and resting tile titles; episode codes, plot lines under tiles, hints.
- **Scrims** (`scrim-strong`, `scrim-mid`, `backdrop-dim`, `badge-scrim`, `scrim-clear`): the ground colour at fixed alphas. Scrims always fade toward the ground, never toward pure black.

### Named Rules
**The Lit Focus Rule.** Opaque white fill and the 3dp white frame belong to focus and to nothing else. Text is the softer `text-primary`; glass is translucent. If something is solid white, the remote is on it.

**The Blue Means Chosen Rule.** Blue marks what is selected or current: active tab, selected option, progress, today, a link. It never marks focus. In every state list the focused item comes before the selected item, so a control that is both shows white.

**The Room Light Rule.** On home, colour beyond the accent comes from the artwork itself: the blurred back layer at 62% opacity under a scrim that lets it through at the top (25% ground) and settles to 90% ground at the bottom. Do not paint decorative colour onto the ground; let the title supply it.

## Typography

**Display Font:** Archivo, static instance `archivo_display` (weight 800, width 87.5), cut from the OFL variable font.
**Body Font:** Archivo at normal width: `archivo_regular` 400, `archivo_medium` 500, `archivo_semibold` 600, `archivo_bold` 700, bundled as the `@font/archivo` family and set as the theme font.

**Character:** One family in two widths. Heavy and slightly condensed for names of things, normal width for everything said about them.

### Hierarchy
- **Display** (800 at width 87.5, 44sp, line multiplier 0.95, tracking -0.015): the hero title, two lines at most, 560dp wide at most. Drops to 36sp for the title on the detail screen.
- **Headline** (same face, 32sp): the one title of a screen without artwork ("Wer schaut gerade?", "Einstellungen").
- **Numeral** (same face, 26sp): the date numeral in the week strip. The same face at 64sp and 35% white is the placeholder numeral on an episode that has no still.
- **Wordmark** (same face, 20sp, uppercase, tracking 0.04, brand blue): the only uppercase text and the only tracked-out text in the product.
- **Title** (700, 18sp, tracking -0.005): shelf headings, settings group headings, profile names, the search query.
- **Body** (400, 15sp, line multiplier 1.35): plot text, three lines at most, 520dp wide at most in the hero.
- **Meta** (500, 15sp): the fact line under a title, nav tabs, season tabs.
- **Label** (600, 15sp, tracking 0, sentence case): buttons. 12dp vertical and 20dp horizontal padding.
- **Tile title** (500, 13sp, one line): under poster tiles. 600 at 14sp under day and episode tiles. Secondary colour at rest, primary when the tile is focused.
- **Caption** (400, 12sp, muted): episode code, "+9 weitere", two-line episode plot (line multiplier 1.3).

### Named Rules
**The One Display Voice Rule.** `archivo_display` is for names and numerals only: a title, a screen name, a date, the wordmark. Never for buttons, body or labels.

**The Sentence Case Rule.** `textAllCaps` is off on every button style. Labels are written as German sentences are. The wordmark is the single exception.

**The Sofa Floor Rule.** The reviewed shelves bottom out at 12sp, and only for muted supporting lines. Anything a viewer must read to choose is 13sp or larger.

## Layout

The screen is one vertical feed that runs edge to edge under a floating top bar. Its first item is the hero; everything after is a horizontal shelf.

- **Shared edge.** Nav, hero text, shelf headings and the first tile of every shelf start 58dp from the left (`vf_edge`), which is a little over 5% of the screen width at the default display size. The right edge mirrors it for the nav actions. A focused control never comes closer than 30dp to the top or bottom (`vf_edge_vertical`).
- **Top bar.** 64dp tall: wordmark, then tabs (40dp tall, 14dp side padding, 2dp apart, 18dp after the wordmark), a spacer, then 40dp icon actions 4dp apart and the 36dp avatar. Over the hero it sits on a scrim that fades down from 85% ground. Once the feed has scrolled it turns solid and dissolves over its lowest 14%, so shelves fade out under it.
- **Hero.** At least 260dp tall with a floor of 45% of the feed height, growing with long copy or a larger display size. Text block is bottom-left: title, 10dp, fact line, 8dp, plot, 18dp, then Play (160dp minimum width) and a 48dp details button 10dp apart.
- **Shelves.** Heading at the shared edge, 16dp above it, 14dp between heading and tiles, 12dp under the tiles. Tiles are 14dp apart. Tile sizes: poster 140 by 210dp, landscape 256 by 144dp, day 208 by 117dp, episode 248 by 140dp. Title sits 8dp under the artwork (10dp on episodes), supporting lines 2dp apart.
- **Shelf snap.** When focus moves to a shelf, the feed jumps (it does not animate) so the shelf's heading rests 64dp from the top, directly under the bar. With the side rail it rests at 12dp. The hero always rests at the very top.
- **Search.** Two columns from the shared edge: 42% for the query field and a six-column key grid (keys 40dp tall with 3dp margins), 58% for live results.
- **Detail.** Poster 132 by 198dp at the shared edge, 40dp from the top; text column 24dp to its right; action row of Play, three 48dp icon buttons and the language button, 10dp apart; season tabs; then one shelf of episode tiles.
- **Screens without artwork.** Left-aligned stack at the shared edge: headline, then content in 12dp steps, groups separated by 28dp to 32dp.
- **Display size.** Each profile picks 75, 85, 100, 115 or 130 percent. Layouts are written in dp and sp with wrap-content heights and minimum sizes so that they grow instead of clipping.

### Named Rules
**The Shared Edge Rule.** Everything that starts a row starts at `vf_edge`. Use the dimension, never a literal.

**The Growing Room Rule.** Shelves reserve their edge and their top and bottom space as padding with clipping off, not as margin, so tiles scroll out under the screen edge and a focused tile and its halo are never cut. Focus growth is capped at 1.06 because the padding is sized for exactly that.

## Elevation & Depth

Depth is built from layers of light, not from shadows. Button styles set elevation to 0 with no outline provider. Elevation appears in the source only to fix draw order: 12dp on the top bar, 10dp on the side rail, 2dp of translationZ on whatever is focused.

The three layers on home, back to front:
1. **Room light.** The focused title's backdrop (or poster) loaded at 40 by 22 pixels and stretched to the screen, cross-faded over 700 ms, held at 62% opacity, with an additional 48px blur on API 31 and later. Over it, a vertical scrim of the ground colour at 25%, 60% and 90%.
2. **Hero.** Sharp artwork cropped to keep the upper part of the image (vertical bias 0.15) so faces survive a wide, short banner. A left shade (85% ground at the edge, 20% at 45% across, clear after) carries the text. Artwork and shade fade to transparent together over the bottom 120dp. A slow drift between 1.02 and 1.09 scale over 16 s runs on the artwork when motion is enabled.
3. **Front.** Glass controls and artwork tiles.

The detail screen is built differently: its backdrop is sharp and full-screen under an even 35% dim, a bottom gradient to the ground and a left scrim. It has no room-light layer.

### Light Vocabulary
- **Halo** (`HaloDrawable`): 14 stacked outlines of Halo Blue around a focused tile's artwork frame, spreading 14dp, peak alpha 150 of 255 with quadratic falloff. Fades in over 180 ms and out over 140 ms. Tiles only; controls do not glow.
- **Focus growth** (`FocusFx`): scale to at most 1.06 in 150 ms, back in 160 ms, on the curve (0.23, 1, 0.32, 1). Press dips to 0.96 in 100 ms.
- **Title legibility shadow**: the hero title carries a soft text shadow (60% black, 3 down, radius 18) because it crosses live artwork. It is a legibility aid, not a style.

### Named Rules
**The No Surface Shadow Rule.** No surface casts an elevation or drop shadow. The only light a surface throws is the halo of a focused tile.

**The Still When Asked Rule.** Every decorative motion checks the system animator scale. At zero, growth, halo, drift, cross-fades and entrances snap to their end state; the white fill and white frame remain.

## Shapes

Rounded rectangles in two sizes. Controls and artwork use 8dp (`vf_radius_control`, `vf_radius_image`); panels, dialogs and the profile frame use 12dp (`vf_radius`). Nothing is a pill; the only circle is the avatar and its 3dp ring.

Artwork is clipped to its 8dp corners and the focus frame is drawn as a foreground on the artwork frame itself, 3dp wide (`vf_focus_stroke`), so it hugs the image and not the text below. Poster badges round only the two corners that follow the artwork (top-left and bottom-right). Progress is a square-ended 4dp bar flush with the bottom of the artwork. Glass carries a 1dp edge; inputs and dialogs carry a 1dp hairline that becomes a 3dp white frame on focus.

### Named Rules
**The No-Box Rule.** An artwork tile has no card, no outline at rest and no background beyond its own image. Its title and supporting lines sit under it as plain text.

## Components

### Buttons
Glass at rest, lit when focused.
- **Shape:** 8dp corners, 48dp minimum height, 12dp by 20dp padding, zero insets, no elevation.
- **Default (`SvButton`):** glass fill with a 1dp glass edge, primary text, semibold 15sp.
- **Primary (`SvButton.Primary`):** the same button in dense glass. Used for Play and the one main action of a screen. Play carries a leading play icon with 8dp padding and a 160dp minimum width.
- **Ghost (`SvButton.Ghost`):** identical at rest to the default; adds a selected state of soft blue wash with a 2dp blue frame. Used for option pickers (display size) and search keys.
- **Focused:** white-to-cool-white vertical gradient, ink text and icon. **Pressed:** grey step, ink text.
- **Icon button:** 48dp square of the same glass with 12dp padding beside a button; in the top bar, 40dp with no fill at rest and flat white on focus. Icons are vector drawables tinted by state.

### Tabs (top nav, season, filter)
- **Style:** text only at rest in secondary colour, medium 15sp, 40dp tall, 14dp side padding.
- **Selected:** primary text and a 2dp blue underline inset 14dp from each side.
- **Focused:** flat white fill with 8dp corners and ink text.

### Artwork tiles
- **Corner Style:** 8dp, image clipped.
- **Background:** artwork ground until the image arrives; no card.
- **Focus:** 3dp white frame on the artwork, growth to at most 1.06, blue halo, title brightens from secondary to primary.
- **Furniture:** optional badge (semibold on 90% ground, 4dp by 8dp padding, top-left); 4dp blue progress bar on the bottom edge, over a 40% white track on episodes; a 24dp seen mark top-right on episodes.

### Week strip ("Diese Woche")
The signature shelf. Each day is a column with no box: the date numeral in the display face at 26sp with the weekday beside it in medium 14sp, then a 208 by 117dp still, the title in semibold 14sp, the episode code and a count line in muted 12sp. Today's numeral is blue through the `sv_cal_day` selector; every other numeral is primary text.

### Inputs / Fields
- **Style:** solid surface, 8dp corners, 1dp hairline, 18dp padding, 56dp minimum height, 16sp text, muted hint.
- **Focus:** raised surface with a 3dp white frame.
- **Search query:** not an input. It is a panel-glass label fed by the on-screen key grid; the system keyboard is never shown for search.

### Panels and dialogs
- **Panel (`bg_panel`):** panel glass, 12dp corners, 1dp panel edge. Static grouping only, never focusable.
- **Dialog:** solid surface, 12dp corners, 1dp hairline, at least 70% of the short screen side wide, 60% dim behind.

### Profile tile
The avatar is the artwork: a 148dp panel-glass frame with 12dp corners that takes a 3dp white frame on focus, the name under it in bold 18sp, and an active label in blue 13sp.

### Navigation
Two modes per profile. The top bar is described under Layout. The side rail is 72dp wide on solid ground with 48dp icon buttons 8dp apart; its selected item is a raised-surface fill and its focused item flat white.

### Loading
Skeleton blocks in the skeleton tone with 8dp corners, in the shape of the content to come, swept by a 12% white shimmer. The spinner is blue.

## Do's and Don'ts

### Do:
- **Do** feed the room light from the focused title: one blurred back layer at 62% opacity under the ground scrim.
- **Do** make focus white: lit fill with ink text on controls, a 3dp white frame on artwork.
- **Do** list the focused state before the selected state in every state list, so focus wins.
- **Do** keep blue for chosen and current things: active tab underline, selected frame, progress, today, links.
- **Do** start every row at `vf_edge` (58dp) and keep focusable controls 30dp clear of the top and bottom.
- **Do** give shelves their breathing room as padding with clipping off, and cap focus growth at 1.06.
- **Do** put tile titles under the artwork, secondary at rest and primary on focus.
- **Do** fade scrims into the ground colour, at the alphas already defined.
- **Do** check `FocusFx.motionEnabled` before any decorative motion and keep the static focus cue when it is off.
- **Do** write layouts in dp and sp with minimum sizes and wrap-content heights so all five display sizes hold.

### Don't:
- **Don't** fill or frame anything in opaque white unless it is focused.
- **Don't** use blue as a focus signal, and don't use Halo Blue as a fill, stroke or text colour.
- **Don't** put a card, outline or resting border around an artwork tile.
- **Don't** add elevation or drop shadows to surfaces.
- **Don't** set buttons, body or labels in `archivo_display`, and don't uppercase labels.
- **Don't** end the home hero on a painted scrim; it fades out over its bottom 120dp into the room light.
- **Don't** replace `vf_edge` with a literal inset.
- **Don't** raise the system keyboard for search.

## Known gaps

Open at the time of recording. None of these are rules.

- The search results list still opens with a featured hero. In that narrower column its artwork has hard top and left edges instead of dissolving.
- Down from a top nav tab lands on the hero's details button, not on Play.
- The halo and the blurred room light were verified on an API 31 emulator only. Below API 31 the room light relies on the stretched thumbnail alone. minSdk is 25.
- Player, splash, setup, profile edit and the side-rail nav mode were not visually reviewed. What this file says about them comes from source only.
- Evidence screenshots are 1920 by 1080 emulator captures in `.impeccable/review/`.
