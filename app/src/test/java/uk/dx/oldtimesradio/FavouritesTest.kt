package uk.dx.oldtimesradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouritesTest {
    private val st = "Radio Swiss Jazz"
    private val ella = FavouriteSong("Ella Fitzgerald", "Mack the Knife", st, 1L)

    @Test fun songFromTheStream() {
        assertEquals(ella, Favourites.fromStream(st, "Ella Fitzgerald - Mack the Knife", 1L))
        assertEquals(FavouriteSong("", "The Jack Benny Program", st, 2L), Favourites.fromStream(st, "The Jack Benny Program", 2L))
        assertNull(Favourites.fromStream(st, null, 3L))
        assertNull(Favourites.fromStream(st, "Radio Swiss Jazz", 3L))
    }

    @Test fun heartToggles() {
        val (one, added) = Favourites.toggle(emptyList(), ella)
        assertTrue(added); assertEquals(listOf(ella), one)
        // The same song, written differently, comes off again.
        val (none, added2) = Favourites.toggle(one, ella.copy(artist = "ella  FITZGERALD", savedAt = 9L))
        assertFalse(added2); assertTrue(none.isEmpty())
        // Newest first.
        val other = FavouriteSong("Glenn Miller", "In the Mood", st, 5L)
        assertEquals(listOf(other, ella), Favourites.toggle(one, other).first)
    }

    @Test fun textAndLinks() {
        val prog = FavouriteSong("", "The Jack Benny Program", st, 2L)
        assertEquals("The Jack Benny Program", Favourites.line(prog))
        assertEquals("https://www.youtube.com/results?search_query=Ella+Fitzgerald+Mack+the+Knife", Favourites.youtubeSearch(ella))
        assertEquals("https://open.spotify.com/search/Ella%20Fitzgerald%20Mack%20the%20Knife", Favourites.spotifySearch(ella))
        assertEquals("https://www.youtube.com/results?search_query=The+Jack+Benny+Program", Favourites.youtubeSearch(prog))
    }
}
