package dev.anay.quill.completion

/** Reports a failure through [sink] once, and stays quiet until a request succeeds again. */
class FailureReporter(private val sink: (String) -> Unit) {
    private var reported = false

    @Synchronized
    fun failure(message: String) {
        if (reported) return
        reported = true
        sink(message)
    }

    @Synchronized
    fun success() {
        reported = false
    }
}
