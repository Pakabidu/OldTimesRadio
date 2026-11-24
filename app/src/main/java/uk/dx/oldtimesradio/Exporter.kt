package uk.dx.oldtimesradio

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Takes the favourite songs to Spotify or YouTube: finds each song, makes a new private
 * playlist and puts the songs it found on it. The sign-in (an access token) happens in
 * [MainActivity]; everything here blocks, so run it off the main thread.
 */
object Exporter {
    /** What an export did: the playlist's address, how many songs went on, and which weren't found. */
    class Result(val playlistUrl: String?, val added: Int, val missing: List<FavouriteSong>)

    private const val SPOTIFY = "https://api.spotify.com/v1"
    private const val YOUTUBE = "https://www.googleapis.com/youtube/v3"

    // ---- Spotify ----

    /** Swaps the sign-in code for an access token (PKCE: no secret needed). */
    fun spotifyToken(clientId: String, code: String, verifier: String): String {
        val (status, body) = call("POST", "https://accounts.spotify.com/api/token", null,
            Favourites.spotifyTokenForm(clientId, code, verifier), "application/x-www-form-urlencoded")
        if (status !in 200..299) {
            val o = runCatching { JSONObject(body) }.getOrNull()
            val err = o?.optString("error").orEmpty()
            throw IOException(if (err == "invalid_client") "Spotify didn't recognise the Client ID."
                else o?.optString("error_description")?.ifEmpty { null } ?: "Spotify sign-in failed (HTTP $status)")
        }
        return JSONObject(body).getString("access_token")
    }

    fun toSpotify(token: String, songs: List<FavouriteSong>, progress: (Int) -> Unit): Result {
        val me = JSONObject(ok(call("GET", "$SPOTIFY/me", token), "Spotify")).getString("id")
        val uris = ArrayList<String>(); val missing = ArrayList<FavouriteSong>()
        for ((i, f) in songs.withIndex()) {
            progress(i)
            val uri = spotifyFind(token, Favourites.spotifyQuery(f))
                ?: if (f.artist.isNotEmpty()) spotifyFind(token, Favourites.spotifyLooseQuery(f)) else null
            if (uri != null && uri !in uris) uris += uri else if (uri == null) missing += f
        }
        if (uris.isEmpty()) return Result(null, 0, missing)
        val pl = JSONObject(ok(call("POST", "$SPOTIFY/users/${Favourites.enc(me)}/playlists", token,
            JSONObject().put("name", Favourites.PLAYLIST).put("public", false)
                .put("description", "Songs saved from the old wireless set").toString()), "Spotify"))
        val id = pl.getString("id")
        for (chunk in uris.chunked(100))
            ok(call("POST", "$SPOTIFY/playlists/$id/tracks", token, JSONObject().put("uris", JSONArray(chunk)).toString()), "Spotify")
        val url = pl.optJSONObject("external_urls")?.optString("spotify")?.ifEmpty { null } ?: "https://open.spotify.com/playlist/$id"
        return Result(url, uris.size, missing)
    }

    private fun spotifyFind(token: String, q: String): String? {
        val body = ok(call("GET", "$SPOTIFY/search?type=track&limit=1&q=${Favourites.enc(q)}", token), "Spotify")
        val items = JSONObject(body).optJSONObject("tracks")?.optJSONArray("items") ?: return null
        return if (items.length() > 0) items.getJSONObject(0).optString("uri").ifEmpty { null } else null
    }

    // ---- YouTube ----

    fun toYouTube(token: String, songs: List<FavouriteSong>, progress: (Int) -> Unit): Result {
        val ids = ArrayList<String>(); val missing = ArrayList<FavouriteSong>()
        for ((i, f) in songs.withIndex()) {
            progress(i)
            val body = ok(call("GET", "$YOUTUBE/search?part=snippet&type=video&maxResults=1&q=${Favourites.enc(Favourites.youtubeQuery(f))}", token), "YouTube")
            val items = JSONObject(body).optJSONArray("items")
            val vid = if (items != null && items.length() > 0) items.getJSONObject(0).optJSONObject("id")?.optString("videoId") else null
            if (!vid.isNullOrEmpty() && vid !in ids) ids += vid else if (vid.isNullOrEmpty()) missing += f
        }
        if (ids.isEmpty()) return Result(null, 0, missing)
        val pl = JSONObject(ok(call("POST", "$YOUTUBE/playlists?part=snippet,status", token, JSONObject()
            .put("snippet", JSONObject().put("title", Favourites.PLAYLIST).put("description", "Songs saved from the old wireless set"))
            .put("status", JSONObject().put("privacyStatus", "private")).toString()), "YouTube"))
        val id = pl.getString("id")
        var added = 0
        for (v in ids) {
            ok(call("POST", "$YOUTUBE/playlistItems?part=snippet", token, JSONObject().put("snippet", JSONObject()
                .put("playlistId", id).put("resourceId", JSONObject().put("kind", "youtube#video").put("videoId", v))).toString()), "YouTube")
            added++
        }
        return Result("https://www.youtube.com/playlist?list=$id", added, missing)
    }

    // ---- HTTP ----

    private fun call(method: String, url: String, token: String?, body: String? = null,
                     type: String = "application/json"): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.requestMethod = method
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        c.setRequestProperty("Accept", "application/json")
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "$type; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to text
        } finally { c.disconnect() }
    }

    /** The reply's body if it worked; otherwise a readable error from the service. */
    private fun ok(r: Pair<Int, String>, service: String): String {
        val (status, body) = r
        if (status in 200..299) return body
        val err = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        throw IOException(when {
            status == 401 -> "$service sign-in has expired. Please try again."
            reason == "quotaExceeded" -> "YouTube's daily allowance for your Google Cloud project is used up. Try again tomorrow."
            reason == "youtubeSignupRequired" -> "That Google account has no YouTube channel yet. Create one on YouTube, then try again."
            else -> err?.optString("message")?.ifEmpty { null }?.let { "$service: $it" } ?: "$service replied with HTTP $status"
        })
    }
}
