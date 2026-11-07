package dev.anay.quill

/** What the user sees about speed and health. Fed by the engine, read by the status bar. */
class Stats(private val window: Int = 50, private val onChange: () -> Unit = {}) {
    enum class State { IDLE, OK, NOT_CONFIGURED, FAILING }

    private val samples = ArrayDeque<Long>()

    @Volatile
    var state = State.IDLE
        private set

    /** Records the time from request start to the first character of a suggestion. */
    fun recordFirstText(ms: Long) {
        synchronized(samples) {
            samples.addLast(ms)
            if (samples.size > window) samples.removeFirst()
        }
        set(State.OK)
    }

    @Volatile
    var namesCaught = 0
        private set

    @Volatile
    var repairs = 0
        private set

    /** Invented names that were found in a candidate before it reached the screen. */
    @Synchronized
    fun caught(names: Int) {
        namesCaught += names
        onChange()
    }

    @Synchronized
    fun repaired() {
        repairs++
        onChange()
    }

    fun markOk() = set(State.OK)

    fun markNotConfigured() = set(State.NOT_CONFIGURED)

    fun markFailing() = set(State.FAILING)

    fun median(): Long? = percentile(50)

    fun p95(): Long? = percentile(95)

    fun count(): Int = synchronized(samples) { samples.size }

    private fun percentile(p: Int): Long? = synchronized(samples) {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        sorted[(kotlin.math.ceil(p / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
    }

    private fun set(next: State) {
        val changed = state != next
        state = next
        if (changed || next == State.OK) onChange()
    }

    /** The status bar label: short, with the number a user cares about. */
    fun label(enabled: Boolean): String = when {
        !enabled -> "Quill off"
        state == State.NOT_CONFIGURED -> "Quill: add key"
        state == State.FAILING -> "Quill: offline"
        else -> median()?.let { "Quill $it ms" } ?: "Quill"
    }
}
