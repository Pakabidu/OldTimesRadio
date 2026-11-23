# Old Times Radio — notes for Claude

Old Times Radio is an Android internet radio player (Kotlin, views built in code, no
layout XML), drawn as a 1940s portable wireless set. Its cabinet style is shared with the
user's PropWatch app (`Radio`, `Wear`, `Cabinet` were copied from there); keep the two
looking like they came from the same maker.

## Design direction (always)

The whole app must feel like **warm, old radio equipment in used condition**: not just
"vintage", but a set that has sat on a sideboard for decades:

- **Warm light.** Dials, meters, jewel lamps, name windows and the valve glow behind the
  grille glow amber/orange like valve-era lamps behind old glass. Lit things should look
  lit; prefer warm wattage over cold white. The only green is the magic eye. Lights warm
  up and dim with the set (`BroadcastSetView`'s lamp glow, `onLight`). Anything lit
  implements `MoodGlow` so it glows under the mood light.
- **Used, not new.** Use the `Wear` helpers (scratches, scuffs, grime, dust, chips,
  rubbed lettering, rust, coffee rings) and `WornDrawable` on new panels and buttons.
  Chrome wears through to brass, plating to bare metal, paint chips at edges.
- **Period materials.** Black alligator leatherette, bakelite, etched/mottled chrome
  (`Hardware.plate`), slotted screws, brass/copper hardware, cream engraved lettering,
  gold dial scales, faceted jewel lamps (`Hardware.jewel`), backlit glass (`Screen`).
- **Readable for older eyes.** Custom-drawn text uses the `t` text unit (1.3–1.5× and
  follows the system font scale); TextViews 13sp and up. Long labels shrink with
  `fitText` rather than overflow.
- **Restraint.** Add detail where a real set would have it; don't clutter.
- One cabinet only: the 1940s portable (styled after the classic portable sets, without
  any maker's names or logos).

## How it fits together

- `StreamService`: Media3 ExoPlayer foreground service. It holds the playing state, the
  ICY "now playing" title, the loudness meter and the sleep timer (fade, then off). The
  UI listens via `StreamService.listen`. It finds the playing station by URL, so the log
  can be reordered.
- `BroadcastSetView`: the meter and the five preset buttons (the first five stations in
  the log). `GrilleView`: speaker, valve glow and magic eye. `TonePlateView`: SOUND,
  MOOD LIGHT and SLEEP. `NowPlayingView`: the lit title window.
- `Store`: preferences, including the station log as JSON.
- `Updater`: reads the `latest` GitHub release of `Pakabidu/OldTimesRadio`.

## Working on it

- No Android SDK in the cloud container: the app can't be built locally. CI
  (`.github/workflows/build.yml`) runs unit tests and builds the APK on every push;
  watch it. Only builds from `main` are published as releases (with `version.txt`),
  which the in-app updater reads (it checks on every start, and at most hourly on return).
- Pure-Kotlin modules (`Stations`, `Sleep`) have JVM tests in `app/src/test`. Run them
  locally with a plain Kotlin/JVM Gradle project. Keep new rules pure so they're testable.
- Preview custom-drawn UI with a browser canvas mock (same geometry) before pushing.
- Watch for name clashes in the single `uk.dx.oldtimesradio` package (e.g. `Station`).
- Default stream URLs can't be checked from the cloud container (the network blocks
  them); say so rather than claiming they play.
- Versioning: `versionName` stays "1.0" until the user says otherwise; only the build
  number (`versionCode` = CI run number) changes.
