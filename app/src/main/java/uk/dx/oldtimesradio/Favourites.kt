package uk.dx.oldtimesradio

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A song saved with the heart button: who, what, and where it was heard. */
data class FavouriteSong(val artist: String, val title: String, val station: String, val savedAt: Long)

/**
 * The favourite-songs list's rules, and the links and sign-in details for taking it to
 * YouTube and Spotify. Pure Kotlin (no Android), so it can be tested.
 */
object Favourites {
    const val PLAYLIST = "Old Times Radio favourites"

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

    /**
     * The list as plain text, one song a line: the format playlist-transfer services
     * (Soundiiz, TuneMyMusic and the like) import into Spotify or YouTube.
     */
    fun text(list: List<FavouriteSong>) = list.joinToString("\n") { line(it) }

    // ---- Opening one song ----

    fun youtubeSearch(f: FavouriteSong) = "https://www.youtube.com/results?search_query=" + enc(line(f).replace(" - ", " "))
    fun spotifySearch(f: FavouriteSong) = "https://open.spotify.com/search/" + enc(line(f).replace(" - ", " ")).replace("+", "%20")

    /** A Spotify track search: by artist and title when both are known. */
    fun spotifyQuery(f: FavouriteSong) =
        if (f.artist.isEmpty()) f.title else "track:\"${f.title.replace("\"", "")}\" artist:\"${f.artist.replace("\"", "")}\""
    /** The looser search tried when the exact one finds nothing. */
    fun spotifyLooseQuery(f: FavouriteSong) = line(f).replace(" - ", " ")
    fun youtubeQuery(f: FavouriteSong) = line(f).replace(" - ", " ")

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---- Spotify sign-in (authorization code with PKCE: no secret in the app) ----

    const val SPOTIFY_REDIRECT = "oldtimesradio://spotify-callback"
    const val SPOTIFY_SCOPES = "playlist-modify-private playlist-modify-public"

    /** A fresh random code verifier (43–128 URL-safe characters). */
    fun verifier(rnd: SecureRandom = SecureRandom()): String {
        val b = ByteArray(48); rnd.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** The S256 code challenge for [verifier]. */
    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun spotifyAuthUrl(clientId: String, challenge: String, state: String) =
        "https://accounts.spotify.com/authorize?response_type=code" +
        "&client_id=" + enc(clientId) +
        "&redirect_uri=" + enc(SPOTIFY_REDIRECT) +
        "&scope=" + enc(SPOTIFY_SCOPES).replace("+", "%20") +
        "&code_challenge_method=S256&code_challenge=" + challenge +
        "&state=" + enc(state)

    /** The form sent to swap the sign-in code for an access token. */
    fun spotifyTokenForm(clientId: String, code: String, verifier: String) =
        "grant_type=authorization_code&code=" + enc(code) +
        "&redirect_uri=" + enc(SPOTIFY_REDIRECT) +
        "&client_id=" + enc(clientId) +
        "&code_verifier=" + enc(verifier)

    /** A Spotify client ID looks like 32 hex digits. */
    fun validClientId(id: String) = Regex("^[0-9a-fA-F]{32}$").matches(id.trim())
}
