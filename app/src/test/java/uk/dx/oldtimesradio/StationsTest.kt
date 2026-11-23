package uk.dx.oldtimesradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationsTest {
    @Test fun validAddresses() {
        assertTrue(Stations.valid("http://65.108.124.70:8181/stream"))
        assertTrue(Stations.valid(" https://ice1.somafm.com/illstreet-128-mp3 "))
        assertFalse(Stations.valid("ftp://example.com/x"))
        assertFalse(Stations.valid("example.com/stream"))
        assertFalse(Stations.valid("http://bad host/"))
    }

    @Test fun defaultsAreValidAndFillTheButtons() {
        assertEquals(Stations.PRESETS, Stations.DEFAULTS.size)
        Stations.DEFAULTS.forEach { assertTrue(it.url, Stations.valid(it.url)) }
    }

    @Test fun labels() {
        assertEquals("JAZZ", Stations.label("Radio Swiss Jazz", "jazz"))
        assertEquals("SWISS", Stations.label("Radio Swiss Jazz"))
        assertEquals("1920S", Stations.label("The 1920s Radio Network"))
        assertEquals("RADIO", Stations.label("Radio"))
        assertEquals("STATION", Stations.label("  "))
        assertEquals(10, Stations.label("Supercalifragilistic").length)
    }

    @Test fun reorder() {
        assertEquals(listOf("c", "a", "b", "d"), Stations.toTop(listOf("a", "b", "c", "d"), 2))
        assertEquals(listOf("a", "c", "b"), Stations.up(listOf("a", "b", "c"), 2))
        assertEquals(listOf("a", "b"), Stations.up(listOf("a", "b"), 0))
        assertEquals(listOf("a", "b"), Stations.toTop(listOf("a", "b"), 5))
    }
}
