package uk.dx.oldtimesradio

/** The sleep timer's rules (no Android), so they can be unit tested. */
object Sleep {
    /** Marks engraved round the SLEEP knob, in minutes; 0 is OFF. */
    val MARKS = listOf(0, 15, 30, 60, 90)

    /** How long the set takes to fade out at the end, in ms. */
    const val FADE_MS = 30_000L

    /**
     * A tap turns the knob on to the next mark above the minutes left, skipping a mark
     * that's less than a minute away (so a tap always adds real time); from the top mark
     * (or close to it) it goes back to OFF.
     */
    fun next(minutesLeft: Float): Int =
        MARKS.firstOrNull { it > minutesLeft + 1f } ?: 0

    /** Volume (0..1) during the fade: full until the last [FADE_MS], then down to silence. */
    fun fade(msLeft: Long): Float = (msLeft.toFloat() / FADE_MS).coerceIn(0f, 1f)
}
