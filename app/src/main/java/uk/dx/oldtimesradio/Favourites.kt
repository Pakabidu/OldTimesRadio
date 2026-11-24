package uk.dx.oldtimesradio

import java.net.URLEncoder

/** A song saved with the heart button: who, what, and where it was heard. */
data class FavouriteSong(val artist: String, val title: String, val station: String, val savedAt: Long)

/**
 * The favourite-songs list's rules, and the links that find a song on YouTube or
 * Spotify. Pure Kotlin (no Android), so it can be tested.
 */
object Favourites {
    /**
     * The song that's on, from the station's name and its "StreamTitle" ([icy]); null when
     * the station isn't sending one. Text without an "Artist - Title" split is kept whole
     * as the title (often a programme's name).
     */
    fun fromStream(station: String, icy: String?, now: Long): FavouriteSong? {
        val t = Tags.of(station, icy)
        if (t.artist == Tags.APP) return null   // no song, just the station
        val artist = if (t.artist == t.album) "" else t.artist
        return FavouriteSong(artist, t.title, station, now)
    }

    /** Same song whatever the capitals or spacing. */
    fun key(f: FavouriteSong) = norm(f.artist) + "|" + norm(f.title)
    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    fun contains(list: List<FavouriteSong>, f: FavouriteSong) = list.any { key(it) == key(f) }

    /** The heart: saves the song (newest first), or takes it off if it's already saved. */
    fun toggle(list: List<FavouriteSong>, f: FavouriteSong): Pair<List<FavouriteSong>, Boolean> =
        if (contains(list, f)) list.filterNot { key(it) == key(f) } to false
        else (listOf(f) + list) to true

    /** "Artist - Title" (or just the title). */
    fun line(f: FavouriteSong) = if (f.artist.isEmpty()) f.title else "${f.artist} - ${f.title}"

    // ---- Opening one song ----

    fun youtubeSearch(f: FavouriteSong) = "https://www.youtube.com/results?search_query=" + enc(line(f).replace(" - ", " "))
    fun spotifySearch(f: FavouriteSong) = "https://open.spotify.com/search/" + enc(line(f).replace(" - ", " ")).replace("+", "%20")

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
