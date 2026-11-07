package dev.anay.quill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupTest {
    private val multi = Shape(singleLine = false, stops = emptyList())
    private val single = Shape(singleLine = true, stops = emptyList())

    @Test
    fun stripsCodeFences() {
        assertEquals("val a = 1\n", Cleanup.clean("```kotlin\nval a = 1\n```", "", multi))
    }

    @Test
    fun keepsTextBeforeAClosingFenceWithoutAnOpeningOne() {
        assertEquals("val a = 1\n", Cleanup.clean("val a = 1\n```\nnotes", "", multi))
    }

    @Test
    fun trimsTheTailThatRepeatsTextAfterTheCaret() {
        assertEquals("foo(1, 2", Cleanup.clean("foo(1, 2)", ")", single))
        assertEquals("x\n", Cleanup.clean("x\n}", "}\n", multi))
    }

    @Test
    fun keepsWhitespaceOnlyOverlap() {
        assertEquals("a ", Cleanup.clean("a ", " b", multi))
    }

    @Test
    fun cutsAtTheEarliestStopSequence() {
        val shape = Shape(false, listOf("\nfun ", "<|endoftext|>"))
        assertEquals("val a = 1", Cleanup.clean("val a = 1<|endoftext|>x\nfun y()", "", shape))
    }

    @Test
    fun singleLineKeepsTheFirstLineOnly() {
        assertEquals("a + b", Cleanup.clean("a + b\nmore()", "", single))
    }

    @Test
    fun blankResultBecomesEmpty() {
        assertEquals("", Cleanup.clean("  \n ", "", multi))
        assertEquals("", Cleanup.clean(")", ")", single))
    }

    @Test
    fun stopsAreLanguageAware() {
        assertTrue("\ndef " in Cleanup.stopsFor("Python"))
        assertTrue("\nfun " in Cleanup.stopsFor("kotlin"))
        assertFalse("\ndef " in Cleanup.stopsFor("kotlin"))
        assertTrue("<|endoftext|>" in Cleanup.stopsFor("unknown"))
    }
}
