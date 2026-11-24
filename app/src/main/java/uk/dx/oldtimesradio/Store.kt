package uk.dx.oldtimesradio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Settings and the station log, in shared preferences. */
object Store {
    private const val PREFS = "oldtimesradio"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun stations(ctx: Context): List<RadioStation> {
        val raw = p(ctx).getString("stations", null) ?: return Stations.DEFAULTS
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                RadioStation(o.getString("name"), o.optString("label"), o.getString("url"))
            }
        }.getOrDefault(Stations.DEFAULTS)
    }

    fun saveStations(ctx: Context, list: List<RadioStation>) {
        val a = JSONArray()
        for (s in list) a.put(JSONObject().put("name", s.name).put("label", s.label).put("url", s.url))
        p(ctx).edit().putString("stations", a.toString()).apply()
    }

    /** The favourite songs, newest first. */
    fun favourites(ctx: Context): List<FavouriteSong> {
        val raw = p(ctx).getString("favourites", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                FavouriteSong(o.optString("artist"), o.getString("title"), o.optString("station"), o.optLong("at"))
            }
        }.getOrDefault(emptyList())
    }

    fun saveFavourites(ctx: Context, list: List<FavouriteSong>) {
        val a = JSONArray()
        for (f in list) a.put(JSONObject().put("artist", f.artist).put("title", f.title).put("station", f.station).put("at", f.savedAt))
        p(ctx).edit().putString("favourites", a.toString()).apply()
    }

    /** SOUND rocker: the set's own clicks and the stream together. */
    fun sound(ctx: Context) = p(ctx).getBoolean("sound", true)
    fun saveSound(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("sound", on).apply()

    /** SCREEN ON rocker: keep the phone's screen awake while the set is open. */
    fun keepScreenOn(ctx: Context) = p(ctx).getBoolean("keep_screen_on", false)
    fun saveKeepScreenOn(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("keep_screen_on", on).apply()

    fun moodLight(ctx: Context) = p(ctx).getBoolean("mood_light", false)
    fun saveMoodLight(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("mood_light", on).apply()

    // In-app updates.
    fun updateDownloadId(ctx: Context) = p(ctx).getLong("update_dl_id", -1L)
    fun updateBuild(ctx: Context) = p(ctx).getLong("update_dl_build", -1L)
    fun saveUpdateDownload(ctx: Context, id: Long, build: Long) =
        p(ctx).edit().putLong("update_dl_id", id).putLong("update_dl_build", build).apply()
}
