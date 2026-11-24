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
        assertEquals("Ella Fitzgerald - Mack the Knife\nThe Jack Benny Program", Favourites.text(listOf(ella, prog)))
        assertEquals("https://www.youtube.com/results?search_query=Ella+Fitzgerald+Mack+the+Knife", Favourites.youtubeSearch(ella))
        assertEquals("https://open.spotify.com/search/Ella%20Fitzgerald%20Mack%20the%20Knife", Favourites.spotifySearch(ella))
        assertEquals("track:\"Mack the Knife\" artist:\"Ella Fitzgerald\"", Favourites.spotifyQuery(ella))
        assertEquals("The Jack Benny Program", Favourites.spotifyQuery(prog))
        assertEquals("Ella Fitzgerald Mack the Knife", Favourites.youtubeQuery(ella))
    }

    /** RFC 7636, appendix B. */
    @Test fun pkceChallenge() =
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Favourites.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))

    @Test fun verifierShape() {
        val v = Favourites.verifier()
        assertTrue(v.length in 43..128)
        assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(v))
    }

    @Test fun spotifyUrls() {
        val u = Favourites.spotifyAuthUrl("0123456789abcdef0123456789abcdef", "CH", "st8")
        assertTrue(u.startsWith("https://accounts.spotify.com/authorize?response_type=code&client_id=0123456789abcdef0123456789abcdef"))
        assertTrue(u.contains("&redirect_uri=oldtimesradio%3A%2F%2Fspotify-callback"))
        assertTrue(u.contains("&scope=playlist-modify-private%20playlist-modify-public"))
        assertTrue(u.contains("&code_challenge_method=S256&code_challenge=CH&state=st8"))
        assertEquals("grant_type=authorization_code&code=a%2Bb&redirect_uri=oldtimesradio%3A%2F%2Fspotify-callback&client_id=id&code_verifier=v",
            Favourites.spotifyTokenForm("id", "a+b", "v"))
        assertTrue(Favourites.validClientId(" 0123456789ABCDEF0123456789abcdef "))
        assertFalse(Favourites.validClientId("not-an-id"))
    }
}
