package uk.dx.oldtimesradio

/**
 * When the set should try a station again by itself: after the internet drops out, the
 * stream ends or stalls, or a car pauses it for a while. Pure Kotlin, so it can be tested;
 * the error codes are Media3's PlaybackException codes.
 */
object Reconnect {
    /** Keep trying this long after things first went wrong, then give up. */
    const val GIVE_UP_MS = 10 * 60_000L
    /** Buffering this long with nothing coming in counts as a dropped stream. */
    const val STALL_MS = 25_000L
    /** Paused longer than this, Play tunes in afresh (the live stream has moved on). */
    const val STALE_PAUSE_MS = 30_000L
    /** Paused (by a car or headphones) and left this long, the set switches itself off. */
    const val STANDBY_MS = 30 * 60_000L

    /** Wait before each try: quick at first, then every 30 seconds. */
    fun delay(attempt: Int): Long = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L).getOrElse(attempt) { 30_000L }

    fun keepTrying(failingSince: Long, now: Long) = now - failingSince < GIVE_UP_MS

    // Media3 PlaybackException codes.
    private const val UNSPECIFIED = 1000
    private const val REMOTE_ERROR = 1001
    private const val BEHIND_LIVE_WINDOW = 1002
    private const val TIMEOUT = 1003
    private const val IO_UNSPECIFIED = 2000
    private const val IO_NETWORK_CONNECTION_FAILED = 2001
    private const val IO_NETWORK_CONNECTION_TIMEOUT = 2002
    private const val IO_BAD_HTTP_STATUS = 2004
    private const val IO_READ_POSITION_OUT_OF_RANGE = 2008

    /**
     * Is this worth trying again? Network trouble always is. Errors that also come from a
     * stream cut off half-way (a bad HTTP status from a server that's briefly down, a
     * broken frame) are only if the station had been playing; on a first tune-in they
     * mean a bad address, so the set says NO SIGNAL instead.
     */
    fun retryable(code: Int, everPlayed: Boolean): Boolean = when (code) {
        IO_NETWORK_CONNECTION_FAILED, IO_NETWORK_CONNECTION_TIMEOUT, IO_UNSPECIFIED, TIMEOUT,
        BEHIND_LIVE_WINDOW, IO_READ_POSITION_OUT_OF_RANGE -> true
        UNSPECIFIED, REMOTE_ERROR, IO_BAD_HTTP_STATUS -> everPlayed
        in 3001..3004 -> everPlayed   // the stream's audio broken mid-frame
        else -> false                 // not found, not allowed, can't decode: a bad station
    }
}
