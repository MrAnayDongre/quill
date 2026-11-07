package dev.anay.quill.completion

/** Text that is sent to the model: everything before the caret (with context on top) and everything after it. */
data class FimPrompt(val prefix: String, val suffix: String)

/** A few lines taken from another file of the project. */
data class ContextSnippet(val path: String, val text: String)

object PromptBuilder {
    // ponytail: four characters per token is a rough rule that holds for code across common tokenizers;
    // swap in the model's tokenizer if budgets need to be exact.
    const val CHARS_PER_TOKEN = 4

    private const val SUFFIX_SHARE = 0.25
    private const val CONTEXT_SHARE = 0.2

    fun build(
        before: String,
        after: String,
        context: List<ContextSnippet>,
        commentPrefix: String,
        budgetTokens: Int,
    ): FimPrompt {
        require(budgetTokens > 0) { "budgetTokens must be positive" }
        val budget = budgetTokens * CHARS_PER_TOKEN
        val suffix = keepHead(after, (budget * SUFFIX_SHARE).toInt())
        val contextBudget = (budget * CONTEXT_SHARE).toInt()
        val header = renderContext(context, commentPrefix, contextBudget)
        val prefix = keepTail(before, budget - suffix.length - header.length)
        return FimPrompt(header + prefix, suffix)
    }

    private fun renderContext(context: List<ContextSnippet>, commentPrefix: String, budget: Int): String {
        val out = StringBuilder()
        for (snippet in context) {
            val block = "$commentPrefix ${snippet.path}\n" +
                snippet.text.lines().joinToString("\n") { "$commentPrefix $it".trimEnd() } + "\n"
            if (out.length + block.length > budget) break
            out.append(block)
        }
        return if (out.isEmpty()) "" else out.append('\n').toString()
    }

    /** Keeps the end of [text], cutting at a line boundary so the model never sees half a line. */
    internal fun keepTail(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.length - max.coerceAtLeast(0)
        val newline = text.indexOf('\n', cut - 1)
        return if (newline < 0) text.substring(cut) else text.substring(newline + 1)
    }

    /** Keeps the start of [text], cutting at a line boundary. */
    internal fun keepHead(text: String, max: Int): String {
        if (text.length <= max) return text
        val newline = text.lastIndexOf('\n', max.coerceAtLeast(0))
        return if (newline < 0) text.substring(0, max.coerceAtLeast(0)) else text.substring(0, newline + 1)
    }
}
