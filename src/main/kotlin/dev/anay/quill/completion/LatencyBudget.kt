package dev.anay.quill.completion

/**
 * Shrinks the token allowance when the server is slower than the latency budget, so suggestions
 * keep arriving while the user is still looking at the line.
 */
class LatencyBudget {
    private var averageMs = 0.0

    @Synchronized
    fun record(ms: Long) {
        averageMs = if (averageMs == 0.0) ms.toDouble() else averageMs + SMOOTHING * (ms - averageMs)
    }

    @Synchronized
    fun cap(maxTokens: Int, budgetMs: Long): Int {
        if (averageMs <= budgetMs) return maxTokens
        return (maxTokens * budgetMs / averageMs).toInt().coerceIn(minOf(MIN_TOKENS, maxTokens), maxTokens)
    }

    private companion object {
        const val SMOOTHING = 0.3
        const val MIN_TOKENS = 16
    }
}
