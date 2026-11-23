package uk.dx.oldtimesradio

import org.junit.Assert.assertEquals
import org.junit.Test

class TagsTest {
    private val st = "Radio Swiss Jazz"

    @Test fun artistAndSong() =
        assertEquals(Tags("Mack the Knife", "Ella Fitzgerald", st), Tags.of(st, "Ella Fitzgerald - Mack the Knife"))

    @Test fun onlyTheFirstDashSplits() =
        assertEquals(Tags("Take Five - Live", "Dave Brubeck", st), Tags.of(st, "Dave Brubeck - Take Five - Live"))

    @Test fun enDashAndExtraSpaces() =
        assertEquals(Tags("In the Mood", "Glenn Miller", st), Tags.of(st, "  Glenn   Miller – In the Mood "))

    @Test fun noSongMeansStationAsTitle() {
        assertEquals(Tags(st, Tags.APP, Tags.APP), Tags.of(st, null))
        assertEquals(Tags(st, Tags.APP, Tags.APP), Tags.of(st, "  "))
        assertEquals(Tags(st, Tags.APP, Tags.APP), Tags.of(st, "-"))
        assertEquals(Tags(st, Tags.APP, Tags.APP), Tags.of(st, "radio swiss jazz"))
    }

    @Test fun textWithoutArtist() =
        assertEquals(Tags("The Jack Benny Program", st, st), Tags.of(st, "The Jack Benny Program"))

    @Test fun hyphenatedWordsDontSplit() =
        assertEquals(Tags("Be-Bop-A-Lula", st, st), Tags.of(st, "Be-Bop-A-Lula"))

    @Test fun quotesStripped() =
        assertEquals(Tags("Sing Sing Sing", "Benny Goodman", st), Tags.of(st, "\"Benny Goodman - Sing Sing Sing\""))

    @Test fun blankStationName() =
        assertEquals(Tags(Tags.APP, Tags.APP, Tags.APP), Tags.of("", null))
}
