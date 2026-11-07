package dev.anay.quill.backend

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.util.concurrent.Flow as JdkFlow

/** Turns server sent event lines into text deltas. The JDK calls it one line at a time, never concurrently. */
internal class SseLines(
    private val chat: Boolean,
    private val onText: (String) -> Unit,
    private val onEnd: (Throwable?) -> Unit,
) : JdkFlow.Subscriber<String> {
    private lateinit var subscription: JdkFlow.Subscription
    private var received = 0
    private var finished = false

    override fun onSubscribe(subscription: JdkFlow.Subscription) {
        this.subscription = subscription
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(line: String) {
        if (finished) return
        received += line.length + 1
        if (received > MAX_RESPONSE_BYTES) return fail(BackendException("The server response is too large"))
        if (!line.startsWith("data:")) return
        val payload = line.substring(DATA_PREFIX.length).trim()
        if (payload == "[DONE]") return stop(null)
        try {
            text(payload)?.let(onText)
        } catch (e: JsonSyntaxException) {
            fail(BackendException("The server response is not valid JSON", e))
        } catch (e: IllegalStateException) {
            fail(BackendException("The server response has an unexpected shape", e))
        }
    }

    override fun onError(error: Throwable) = stop(error)

    override fun onComplete() = stop(null)

    private fun fail(error: BackendException) {
        subscription.cancel()
        stop(error)
    }

    private fun stop(error: Throwable?) {
        if (finished) return
        finished = true
        onEnd(error)
    }

    private fun text(payload: String): String? {
        val choices = JsonParser.parseString(payload).asJsonObject.getAsJsonArray("choices") ?: return null
        if (choices.isEmpty) return null
        val choice = choices[0].asJsonObject
        val node = if (chat) choice.getAsJsonObject("delta")?.get("content") else choice.get("text")
        return node?.takeIf { it.isJsonPrimitive }?.asString?.ifEmpty { null }
    }

    private companion object {
        const val DATA_PREFIX = "data:"
        const val MAX_RESPONSE_BYTES = 1 shl 20
    }
}
