package dev.anay.quill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private fun build(before: String, after: String, context: List<ContextSnippet> = emptyList(), tokens: Int = 100) =
        PromptBuilder.build(before, after, context, "//", tokens)

    @Test
    fun smallInputPassesThrough() {
        val prompt = build("val a = ", "\nval b = 2")
        assertEquals(FimPrompt("val a = ", "\nval b = 2"), prompt)
    }

    @Test
    fun prefixKeepsItsEndAndCutsAtALineBoundary() {
        val before = (1..100).joinToString("\n") { "line $it" } + "\nval x = "
        val prompt = build(before, "", tokens = 20)
        assertTrue(prompt.prefix.endsWith("line 100\nval x = "))
        assertTrue(prompt.prefix.startsWith("line "))
        assertTrue(prompt.prefix.length <= 20 * PromptBuilder.CHARS_PER_TOKEN)
    }

    @Test
    fun suffixKeepsItsStartAndIsCapped() {
        val after = (1..100).joinToString("\n") { "line $it" }
        val prompt = build("x", after, tokens = 40)
        assertTrue(prompt.suffix.startsWith("line 1\n"))
        assertTrue(prompt.suffix.length <= 40 * PromptBuilder.CHARS_PER_TOKEN / 4)
    }

    @Test
    fun totalStaysWithinBudget() {
        val context = listOf(ContextSnippet("A.kt", "x\n".repeat(500)))
        val prompt = build("b\n".repeat(2000), "a\n".repeat(2000), context, tokens = 50)
        assertTrue(prompt.prefix.length + prompt.suffix.length <= 50 * PromptBuilder.CHARS_PER_TOKEN)
    }

    @Test
    fun contextIsRenderedAsCommentsAboveThePrefix() {
        val prompt = build("code", "", listOf(ContextSnippet("Util.kt", "fun a()\n\nfun b()")))
        assertEquals("// Util.kt\n// fun a()\n//\n// fun b()\n\ncode", prompt.prefix)
    }

    @Test
    fun contextThatDoesNotFitIsDropped() {
        val prompt = build("code", "", listOf(ContextSnippet("Big.kt", "x".repeat(10_000))), tokens = 100)
        assertEquals("code", prompt.prefix)
    }

    @Test
    fun budgetMustBePositive() {
        val failure = runCatching { build("a", "b", tokens = 0) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
