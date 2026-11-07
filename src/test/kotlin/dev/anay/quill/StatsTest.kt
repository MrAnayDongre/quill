package dev.anay.quill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {
    @Test
    fun percentilesOverTheWindow() {
        val stats = Stats(window = 3)
        listOf(900L, 100L, 200L, 300L).forEach(stats::recordFirstText)
        assertEquals(3, stats.count())
        assertEquals(200L, stats.median())
        assertEquals(300L, stats.p95())
    }

    @Test
    fun noSamplesMeansNoNumbers() {
        assertNull(Stats().median())
    }

    @Test
    fun labelFollowsTheState() {
        val stats = Stats()
        assertEquals("Quill", stats.label(enabled = true))
        assertEquals("Quill off", stats.label(enabled = false))
        stats.recordFirstText(310)
        assertEquals("Quill 310 ms", stats.label(true))
        stats.markNotConfigured()
        assertEquals("Quill: add key", stats.label(true))
        stats.markFailing()
        assertEquals("Quill: offline", stats.label(true))
    }

    @Test
    fun changesAreAnnounced() {
        var calls = 0
        val stats = Stats(onChange = { calls++ })
        stats.recordFirstText(100)
        stats.markFailing()
        stats.markFailing()
        assertEquals(2, calls)
    }
}
