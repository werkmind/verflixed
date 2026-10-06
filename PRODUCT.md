# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

One household, on the sofa, using a Fire TV or Android TV with a D-pad remote. Mostly in the evening in a dark room, three metres from the screen. Several people share the device through profiles.

## Product Purpose

Verflixed is a private TV app for series and films. It exists so the household can continue what they were watching, see which new episodes arrive this week, find a title, and pick a season and episode with as few remote presses as possible.

## Operating Context

- Input is a D-pad remote only: up, down, left, right, select, back. No touch, no pointer.
- Viewing distance is long and the room is dark, so type is large and the ground is dark.
- The app has a per-profile display size setting (zoom), so every screen has to hold at larger scale without clipping.
- Updates are delivered in-app from the GitHub release manifest.

## Capabilities and Constraints

- Screens: splash, profiles, profile edit, setup, home (library, series, films), search, series detail with seasons and episodes, player, settings.
- Home navigation exists in two modes per profile: top bar or side rail.
- "Diese Woche" row lists upcoming episodes by calendar day.
- Native Android Views and XML layouts, minSdk 25. No Compose.
- Interface language is German.

## Brand Commitments

- The name Verflixed and its wordmark stay.
- Visual direction pinned by the owner on 2026-10-06: a blend of the TV interfaces of Netflix, Prime Video, Disney+ and Hulu.
- Layout direction added by the owner the same day: Plex Media Server.

## Evidence on Hand

- Real catalogue artwork (posters, backdrops, episode stills) loaded at runtime.
- No statistics, testimonials or marketing claims exist, and none may be invented.

## Product Principles

1. Focus is never ambiguous. At any moment exactly one element is unmistakably focused.
2. Artwork is the content. Chrome stays out of its way.
3. Fewest presses to playback.
4. Nothing clips at any display size.

## Accessibility & Inclusion

Text contrast at WCAG AA or better against every ground it crosses. Every control is reachable and operable by D-pad, with a visible focus state. Motion respects the system animation setting.
