package uk.dx.oldtimesradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectTest {
    @Test fun backsOffToEveryThirtySeconds() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 30_000L), (0..6).map { Reconnect.delay(it) })
    }

    @Test fun networkTroubleIsAlwaysRetried() {
        for (code in listOf(2001, 2002, 2000, 1003, 1002, 2008)) {
            assertTrue("$code", Reconnect.retryable(code, everPlayed = false))
            assertTrue("$code", Reconnect.retryable(code, everPlayed = true))
        }
    }

    @Test fun ambiguousErrorsOnlyAfterPlaying() {
        for (code in listOf(1000, 1001, 2004, 3001, 3004)) {
            assertFalse("$code", Reconnect.retryable(code, everPlayed = false))
            assertTrue("$code", Reconnect.retryable(code, everPlayed = true))
        }
    }

    @Test fun badStationsAreNot() {
        for (code in listOf(2003, 2005, 2006, 2007, 4001, 4003)) assertFalse("$code", Reconnect.retryable(code, everPlayed = true))
    }

    @Test fun givesUpAfterTenMinutes() {
        assertTrue(Reconnect.keepTrying(0L, 9 * 60_000L))
        assertFalse(Reconnect.keepTrying(0L, 10 * 60_000L))
    }
}
