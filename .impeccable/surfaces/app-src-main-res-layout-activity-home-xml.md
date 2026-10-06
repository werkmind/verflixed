---
version: 1
slug: "app-src-main-res-layout-activity-home-xml"
primary_target: "app/src/main/res/layout/activity_home.xml"
related_targets: ["app/src/main/res/layout/activity_detail.xml"]
---

# Home, detail and shared chrome (Android TV)

Scope: the whole TV interface. Mode: Operate. Code-led build, chosen by the owner.

Audience and job: a household on the sofa with a D-pad, continuing a show, checking this week's episodes, searching, picking an episode.

Constraints: native Views, minSdk 25, German copy, per-profile display zoom, both nav modes stay.

Direction source: pinned by the owner as a blend of Netflix, Prime Video, Disney+ and Hulu, with Plex Media Server for layout. The Netflix tokens come from the published Refero style reference; no published reference exists for the others, so their part is taken from their TV interfaces. Plex is honoured by the billboard that shows the focused title's facts above the shelves, titles set under the artwork, and the optional side rail.

Owner correction after the first build (2026-10-06): the flat black-and-white rendition was "bland and white", with no emotion next to the current Netflix TV app. The owner asked for depth, effects, layers, gradients and blur. That is binding and replaces the earlier flat rule "only focus is white, solid fills, no shadows".

## Direction contract

THESIS: The light in the room comes from the title you are on. Refuses both the dark-navy pill-and-glow template the app shipped before and the flat monochrome rendition that replaced it.

OWN-WORLD: Three layers. Back: the focused title's artwork, blurred across the whole screen, as coloured room light over a near-black cool ground. Middle: the sharp hero artwork, dissolving along its bottom edge into that light instead of ending on a scrim. Front: glass controls (translucent white with a hairline edge) and artwork tiles. Focus is lit white: a focused control turns white with dark ink, a focused tile gets a 3dp white frame, grows, and throws a blue glow. One accent, the brand blue, for the wordmark, the active tab underline and other selected states, progress, today's date, text links and the focus glow. 8dp corners on controls and artwork, 12dp on panels. Archivo, semi-condensed and heavy for titles, normal width for everything else. Screens without artwork get a ground lit in deep blue from the upper right.

STORY: The viewer sees what they can watch, always knows where the remote is, and reaches playback in two presses.

FIRST VIEWPORT: Full-bleed hero artwork to the top edge with faces kept, shaded on the left only. Left: title in heavy semi-condensed type, one meta line, then Abspielen and the details button in glass. Nav across the top at the shared content edge. The hero fades into the blurred light, and the first shelf is fully visible on it.

SIGNATURE: Spielplan. The "Diese Woche" row reads as a timetable strip: date numeral and weekday above each still, no card boxes, and today's numeral is the only blue numeral on the screen.

FORM: Pinned canon blend, no roll. Seed key: none (direction pinned by the owner).

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
