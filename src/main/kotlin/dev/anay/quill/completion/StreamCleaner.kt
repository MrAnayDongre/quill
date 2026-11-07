package dev.anay.quill.completion

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
