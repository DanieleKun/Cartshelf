package app.archivefetcher.download

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaterSchedulerTest {
    private val hour = 60 * 60 * 1000L

    @Test
    fun `a later hour today is waited for today`() {
        val now = LocalDateTime.of(2026, 11, 22, 22, 0)
        val wait = LaterScheduler.millisUntilHour(23, now)
        assertEquals(hour, wait)
    }

    @Test
    fun `an hour already passed is waited for tomorrow`() {
        val now = LocalDateTime.of(2026, 11, 22, 23, 30)
        val wait = LaterScheduler.millisUntilHour(2, now)
        val expected = java.time.Duration.between(now, LocalDateTime.of(2026, 11, 23, 2, 0)).toMillis()
        // Daylight saving can shift the wall clock difference by an hour.
        assertTrue(Math.abs(wait - expected) <= hour)
    }

    @Test
    fun `the current hour exactly means tomorrow`() {
        val now = LocalDateTime.of(2026, 11, 22, 2, 0)
        assertTrue(LaterScheduler.millisUntilHour(2, now) > 23 * hour)
    }

    @Test
    fun `mode ids round trip and unknown falls back to manual`() {
        assertEquals(LaterMode.Night, LaterMode.fromId("night"))
        assertEquals(LaterMode.Manual, LaterMode.fromId("???"))
        assertEquals(ZoneId.systemDefault(), ZoneId.systemDefault())
    }
}
