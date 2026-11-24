# Old Times Radio

An internet radio player for Android, built as a 1940s portable wireless set in well-used
condition: alligator leatherette case, leather handle, speaker grille, a lit amber meter that
swings with the station's loudness, five black station push-buttons on an etched chrome plate,
a lit "now playing" window, and the station log in the open lid. The cabinet style comes from
PropWatch.

- Lit like a valve set: amber jewel dial lamps, backlit station-name windows, the valves
  glowing through the speaker cloth and a green magic eye that pulses with the music.
- A Help button at the bottom of the lid explains every part of the set.
- Tap a button (or a station in the log) to play; tap the lit one again to switch off.
- Long-press to change, move or remove a station; the first five in the log sit on the buttons.
- Plays Shoutcast/Icecast MP3 and AAC streams in the background, with a notification.
- SCREEN ON rocker keeps the phone's screen awake while the set is open.
- CLOSE rocker stops the station (and any cast speaker) and closes the app; MOOD LIGHT darkens
  the room so the dials glow.
- Heart button beside the now-playing window: saves the song that's on to Favourite Songs in
  the lid; tap a saved song to find it on YouTube or Spotify.
- Under the mood light, the station log and favourite songs light up like the dial.
- Bluetooth car stereos, headphones and the lock screen show the song, performer and station
  (from the stream's "Artist - Title"); the car's next/previous buttons change station.
- CAST rocker: plays on a Chromecast, Google/Nest speaker or Cast TV (Google's Default Media
  Receiver), or a DLNA/UPnP renderer (smart TVs, Sonos, AV receivers, Kodi…) on the Wi-Fi;
  the buttons, dial and sleep timer then work the speaker.
- SLEEP knob: tap for 15, 30, 60 or 90 minutes (then off); the pointer winds back as time runs
  down, and the set fades out over the last half-minute and switches itself off.
- Checks for a new build every time it opens (and on coming back to it, at most hourly).

CI (`.github/workflows/build.yml`) runs unit tests and builds the APK on every push; builds
from `main` are published as the `latest` release, which the in-app updater reads.
