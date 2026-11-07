package dev.anay.quill

/** What the model's raw text has to become before it is shown as ghost text. */
data class Shape(val singleLine: Boolean, val stops: List<String>)

object Cleanup {
    private val fenceOpen = Regex("^\\s*```[\\w+-]*[ \\t]*\\r?\\n?")

    fun clean(raw: String, after: String, shape: Shape): String {
        var text = stripFences(raw)
        text = cutAtStop(text, shape.stops)
        if (shape.singleLine) text = text.substringBefore('\n')
        text = trimOverlap(text, after)
        return if (text.isBlank()) "" else text
    }

    /** Models often wrap the answer in a Markdown fence; drop the opening line and anything from the closing fence on. */
    private fun stripFences(raw: String): String {
        val body = fenceOpen.replaceFirst(raw, "")
        val close = body.indexOf("```")
        return if (close >= 0) body.substring(0, close) else body
    }

    private fun cutAtStop(text: String, stops: List<String>): String {
        val cut = stops.filter { it.isNotEmpty() }.minOfOrNull { text.indexOf(it).takeIf { i -> i >= 0 } ?: text.length }
        return if (cut == null) text else text.substring(0, cut)
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
