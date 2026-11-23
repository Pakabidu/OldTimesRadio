package uk.dx.wireless

/** A station in the log: its name, what's engraved on its button, and where it streams from. */
data class RadioStation(val name: String, val label: String, val url: String)

/** Pure station rules (no Android), so they can be unit tested. */
object Stations {
    /** Push-buttons on the set: the first five stations in the log sit on them. */
    const val PRESETS = 5

    /** What a new set comes with: old-time and period-friendly stations. */
    val DEFAULTS = listOf(
        RadioStation("The 1920s Radio Network", "1920s", "http://65.108.124.70:8181/stream"),
        RadioStation("Old Time Radio Service", "OLD TIME", "http://65.108.105.26:8516/stream"),
        RadioStation("Radio Swiss Jazz", "JAZZ", "https://stream.srg-ssr.ch/srgssr/rsj/mp3/128"),
        RadioStation("SomaFM Illinois Street Lounge", "LOUNGE", "https://ice1.somafm.com/illstreet-128-mp3"),
        RadioStation("SomaFM Secret Agent", "AGENT", "https://ice1.somafm.com/secretagent-128-mp3"),
    )

    /** A stream address worth trying: http or https with a host. */
    fun valid(url: String): Boolean =
        Regex("^https?://[^\\s/:]+(:\\d+)?(/\\S*)?$", RegexOption.IGNORE_CASE).matches(url.trim())

    /** The button engraving: what the user typed, else the name's first word, kept short. */
    fun label(name: String, typed: String = ""): String {
        val t = typed.trim().uppercase()
        if (t.isNotEmpty()) return t.take(10)
        val words = name.trim().uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val skip = setOf("THE", "RADIO", "SOMAFM", "FM", "AM")
        return (words.firstOrNull { it !in skip } ?: words.firstOrNull() ?: "STATION").take(10)
    }

    /** Moves the station at [i] to the top of the log (onto the first button). */
    fun <T> toTop(list: List<T>, i: Int): List<T> =
        if (i !in list.indices || i == 0) list else listOf(list[i]) + list.filterIndexed { k, _ -> k != i }

    /** Moves the station at [i] one place up. */
    fun <T> up(list: List<T>, i: Int): List<T> =
        if (i !in list.indices || i == 0) list else list.toMutableList().also { val x = it[i]; it[i] = it[i - 1]; it[i - 1] = x }
}
