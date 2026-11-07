package dev.anay.quill.completion

/** What the model's raw text has to become before it is shown as ghost text. */
data class Shape(val singleLine: Boolean, val stops: List<String>)

object Cleanup {
    private val fenceOpen = Regex("^\\s*```[\\w+-]*[ \\t]*\\r?\\n?")
    private const val FENCE = "```"

    /** Text after fences and stop sequences are applied; [closed] means the model's answer can not grow any more. */
    internal class Prepared(val text: String, val closed: Boolean)

    fun clean(raw: String, after: String, shape: Shape): String {
        val text = trimOverlap(prepare(raw, shape).text, after)
        return if (text.isBlank()) "" else text
    }

    internal fun prepare(raw: String, shape: Shape): Prepared {
        var text = fenceOpen.replaceFirst(raw, "")
        var closed = false
        // Models often wrap the answer in a Markdown fence; anything from the closing fence on is not code.
        val fence = text.indexOf(FENCE)
        if (fence >= 0) { text = text.substring(0, fence); closed = true }
        val stop = shape.stops.filter { it.isNotEmpty() }.minOfOrNull { text.indexOf(it).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
        if (stop != null && stop != Int.MAX_VALUE) { text = text.substring(0, stop); closed = true }
        val newline = text.indexOf('\n')
        if (shape.singleLine && newline >= 0) { text = text.substring(0, newline); closed = true }
        return Prepared(text, closed)
    }

    /** Removes the tail of the completion that merely repeats what already follows the caret, such as a closing bracket. */
    internal fun trimOverlap(text: String, after: String): String {
        val limit = minOf(text.length, after.length)
        for (len in limit downTo 1) {
            val repeated = after.substring(0, len)
            if (repeated.isNotBlank() && text.endsWith(repeated)) return text.substring(0, text.length - len)
        }
        return text
    }

    /** Stop sequences that mark the start of the next top level declaration for a language. */
    fun stopsFor(languageId: String?): List<String> = COMMON + when (languageId) {
        "kotlin" -> listOf("\nfun ", "\nclass ", "\nobject ", "\ninterface ")
        "JAVA" -> listOf("\npublic class ", "\nclass ", "\nimport ")
        "Python" -> listOf("\ndef ", "\nclass ", "\nif __name__")
        "JavaScript", "TypeScript", "ECMAScript 6" -> listOf("\nfunction ", "\nclass ", "\nexport ")
        "go" -> listOf("\nfunc ", "\ntype ")
        "Rust" -> listOf("\nfn ", "\nimpl ", "\nstruct ")
        else -> emptyList()
    }

    private val COMMON = listOf("<|endoftext|>", "<|file_separator|>", "\n\n\n")
}
