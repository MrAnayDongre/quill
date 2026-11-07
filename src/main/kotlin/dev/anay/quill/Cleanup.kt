package dev.anay.quill

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

/**
 * Cleans a completion while it is still arriving. Emits only text that later input can not change, holding back
 * a tail that might turn into a fence, a stop sequence or a repeat of the text after the caret. All pieces joined
 * equal [Cleanup.clean] of the whole answer, whatever the chunking.
 */
class StreamCleaner(private val after: String, private val shape: Shape) {
    private val raw = StringBuilder()
    private var emitted = 0

    /** True once the answer is complete as far as the caller is concerned, so reading can stop. */
    var done = false
        private set

    fun feed(delta: String): String {
        raw.append(delta)
        return advance(final = false)
    }

    fun finish(): String = advance(final = true)

    private fun advance(final: Boolean): String {
        val prepared = Cleanup.prepare(raw.toString(), shape)
        done = prepared.closed
        val complete = final || prepared.closed
        val text = if (complete) Cleanup.clean(raw.toString(), after, shape) else stable(prepared.text, raw.toString())
        if (text.length <= emitted) return ""
        return text.substring(emitted).also { emitted = text.length }
    }

    private fun stable(text: String, rawText: String): String {
        if (text.isBlank() || FENCE.startsWith(rawText.trimStart())) return ""
        return text.dropLast(held(text))
    }

    private fun held(text: String): Int {
        val partialStop = (shape.stops + FENCE).maxOf { stop -> (minOf(stop.length - 1, text.length) downTo 1).firstOrNull { text.endsWith(stop.take(it)) } ?: 0 }
        val from = maxOf(0, text.length - after.length)
        val repeat = (from until text.length).firstOrNull { after.startsWith(text.substring(it)) }?.let { text.length - it } ?: 0
        return maxOf(partialStop, repeat)
    }

    private companion object {
        const val FENCE = "```"
    }
}
