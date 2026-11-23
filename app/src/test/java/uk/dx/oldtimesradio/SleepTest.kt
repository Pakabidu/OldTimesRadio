package uk.dx.oldtimesradio

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTest {
    @Test fun knobTurnsToTheNextMark() {
        assertEquals(15, Sleep.next(0f))
        assertEquals(30, Sleep.next(15f))
        assertEquals(30, Sleep.next(14.2f))   // part-way down from 15: back up to 30
        assertEquals(60, Sleep.next(30f))
        assertEquals(90, Sleep.next(60f))
        assertEquals(0, Sleep.next(90f))      // past the top mark: off
        assertEquals(0, Sleep.next(89.8f))
    }

    @Test fun fadesOutOverTheLastStretch() {
        assertEquals(1f, Sleep.fade(10 * 60_000L), 0f)
        assertEquals(0.5f, Sleep.fade(Sleep.FADE_MS / 2), 0.001f)
        assertEquals(0f, Sleep.fade(0), 0f)
        assertEquals(0f, Sleep.fade(-5), 0f)
    }
}
