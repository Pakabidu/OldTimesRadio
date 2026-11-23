package uk.dx.oldtimesradio

/**
 * The tags sent to car stereos and headphones over Bluetooth (and shown on the lock
 * screen): what's playing, who by, and which station. Pure Kotlin, so it can be tested.
 */
data class Tags(val title: String, val artist: String, val album: String) {
    companion object {
        const val APP = "Old Times Radio"

        /**
         * Builds the tags from the station's name and its Shoutcast/Icecast "StreamTitle"
         * ([icy], usually "Artist - Title"). With a song: title = song, artist = performer,
         * album = station. With nothing (or just the station's own name): title = station,
         * artist = the app, album = the app, so a car display never shows "Unknown".
         */
        fun of(station: String, icy: String?): Tags {
            val name = clean(station).ifEmpty { APP }
            val text = icy?.let { clean(it) }.orEmpty()
            if (text.isEmpty() || text == "-" || text.equals(name, ignoreCase = true))
                return Tags(name, APP, APP)
            val m = Regex("^(.+?)\\s+[-–—]\\s+(.+)$").find(text)
            val artist = m?.groupValues?.get(1)?.trim().orEmpty()
            val song = m?.groupValues?.get(2)?.trim().orEmpty()
            return if (artist.isNotEmpty() && song.isNotEmpty()) Tags(song, artist, name)
                else Tags(text, name, name)
        }

        /** Trims, drops wrapping quotes and squeezes runs of spaces. */
        private fun clean(s: String): String =
            s.trim().removeSurrounding("\"").removeSurrounding("'").replace(Regex("\\s+"), " ").trim()
    }
}
