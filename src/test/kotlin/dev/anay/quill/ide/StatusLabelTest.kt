package dev.anay.quill.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.anay.quill.completion.CompletionStats

class StatusLabelTest : BasePlatformTestCase() {
    fun testLabelFollowsTheState() {
        val stats = CompletionStats()
        assertEquals("Quill", statusLabel(enabled = true, stats = stats))
        assertEquals("Quill off", statusLabel(enabled = false, stats = stats))
        stats.recordFirstText(310)
        assertEquals("Quill 310 ms", statusLabel(true, stats))
        stats.markNotConfigured()
        assertEquals("Quill: add key", statusLabel(true, stats))
        stats.markFailing()
        assertEquals("Quill: offline", statusLabel(true, stats))
    }

    fun testTooltipCarriesTheNumbersAndTheCounters() {
        val stats = CompletionStats()
        listOf(100L, 200L, 300L).forEach(stats::recordFirstText)
        stats.caught(3)
        stats.autoFixed()
        stats.repaired()
        val tooltip = statusTooltip(stats)
        assertTrue(tooltip, tooltip.contains("Median 200 ms, 95th percentile 300 ms over the last 3 suggestions."))
        assertTrue(tooltip, tooltip.contains("Kept 3 invented names off the screen: 1 fixed on the spot, 1 repaired by asking again."))
    }
}
