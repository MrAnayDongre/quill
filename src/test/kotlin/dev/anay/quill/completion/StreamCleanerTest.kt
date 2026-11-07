package dev.anay.quill.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCleanerTest {
    private val multi = Shape(false, listOf("\nfun ", "<|endoftext|>", "\n\n\n"))
    private val single = Shape(true, emptyList())

    private fun streamed(raw: String, after: String, shape: Shape, chunk: Int): String {
        val cleaner = StreamCleaner(after, shape)
        val out = StringBuilder()
        raw.chunked(chunk).forEach { out.append(cleaner.feed(it)) }
        return out.append(cleaner.finish()).toString()
    }

    private val cases = listOf(
        Triple("```kotlin\nval a = 1\n```", "", multi),
        Triple("val a = 1\n```\nnotes", "", multi),
        Triple("foo(1, 2)", ")", single),
        Triple("x\n}", "}\n", multi),
        Triple("val a = 1<|endoftext|>x\nfun y()", "", multi),
        Triple("val a = 1\nfun y() {}", "", multi),
        Triple("a + b\nmore()", "", single),
        Triple("  \n ", "", multi),
        Triple(")", ")", single),
        Triple("\n    return a * b\n", "\n}", multi),
        Triple("if (x) {\n    run()\n}\n\n\nunrelated", "", multi),
        Triple("a`b``c", "", multi),
        Triple("call(a, b)) + 1", ")) + 1", multi),
    )

    @Test
    fun anyChunkingGivesTheSameTextAsCleaningTheWholeAnswer() {
        for ((raw, after, shape) in cases) {
            val expected = Cleanup.clean(raw, after, shape)
            for (chunk in 1..raw.length.coerceAtLeast(1)) {
                assertEquals("raw=[$raw] chunk=$chunk", expected, streamed(raw, after, shape, chunk))
            }
        }
    }

    @Test
    fun showsTextBeforeTheAnswerIsComplete() {
        val cleaner = StreamCleaner("", multi)
        assertEquals("return a", cleaner.feed("return a * b".substring(0, 8)))
        assertFalse(cleaner.done)
    }

    @Test
    fun holdsBackATailThatMayBecomeAStopSequence() {
        val cleaner = StreamCleaner("", multi)
        assertEquals("val a = 1", cleaner.feed("val a = 1\nfu"))
        assertEquals("", cleaner.feed("n "))
        assertTrue(cleaner.done)
    }

    @Test
    fun holdsBackATailThatMayRepeatTheTextAfterTheCaret() {
        val cleaner = StreamCleaner("))", single)
        assertEquals("foo(1", cleaner.feed("foo(1)"))
        assertEquals("", cleaner.feed(")"))
        assertEquals("", cleaner.finish())
    }

    @Test
    fun singleLineAnswerIsDoneAtTheFirstNewline() {
        val cleaner = StreamCleaner("", single)
        cleaner.feed("a + b\nsecond")
        assertTrue(cleaner.done)
    }
}
