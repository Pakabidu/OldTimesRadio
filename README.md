# Wireless

An internet radio player for Android, built as a 1940s portable wireless set in well-used
condition: alligator leatherette case, leather handle, speaker grille, a lit amber meter that
swings with the station's loudness, five black station push-buttons on an etched chrome plate,
a lit "now playing" window, and the station log in the open lid. The cabinet style comes from
PropWatch.

- Tap a button (or a station in the log) to play; tap the lit one again to switch off.
- Long-press to change, move or remove a station; the first five in the log sit on the buttons.
- Plays Shoutcast/Icecast MP3 and AAC streams in the background, with a notification.
- SOUND rocker mutes; MOOD LIGHT darkens the room so the dials glow.

CI (`.github/workflows/build.yml`) runs unit tests and builds the APK on every push; builds
from `main` are published as the `latest` release, which the in-app updater reads.
