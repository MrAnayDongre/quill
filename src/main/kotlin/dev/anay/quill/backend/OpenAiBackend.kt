package dev.anay.quill.backend

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.anay.quill.completion.Cleanup
import dev.anay.quill.completion.FimPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletionException

/**
 * Streams from any server that implements the OpenAI API. An endpoint ending in `/chat/completions` gets a chat
 * request with a cursor marker; any other endpoint gets a completions request with the `suffix` field for fill in
 * the middle.
 */
class OpenAiBackend(private val endpoint: suspend () -> Endpoint) : CompletionBackend, AutoCloseable {

    // Building a client loads the TLS stack, so it happens on first use and never on the UI thread.
    private val clientHolder = lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build() }
    private val client get() = clientHolder.value

    override fun stream(request: CompletionRequest): Flow<String> = callbackFlow {
        val target = endpoint()
        val httpRequest = HttpRequest.newBuilder(URI(target.url))
            .timeout(target.timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply { target.apiKey?.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(body(target, request)))
            .build()
        val lines = SseLines(target.isChat, { trySend(it) }, { close(it) })
        val future = client.sendAsync(httpRequest) { info ->
            if (info.statusCode() in 200..299) {
                HttpResponse.BodySubscribers.fromLineSubscriber(lines)
            } else {
                close(BackendException("The server answered HTTP ${info.statusCode()}"))
                HttpResponse.BodySubscribers.discarding()
            }
        }
        future.whenComplete { _, error -> if (error != null) close(describe(error)) }
        awaitClose { future.cancel(true) }
    }.buffer(Channel.UNLIMITED)

    /** Opens the connection to the endpoint's host ahead of the first request. Sends no credentials. */
    suspend fun warmUp() {
        try {
            val request = HttpRequest.newBuilder(URI(endpoint().url))
                .timeout(Duration.ofSeconds(5))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed warm-up only means the first real request pays for the connection; real requests report problems.
        }
    }

    private fun body(target: Endpoint, request: CompletionRequest): String = JsonObject().apply {
        addProperty("model", target.model)
        if (target.isChat) {
            add("messages", chatMessages(request.prompt))
        } else {
            addProperty("prompt", request.prompt.prefix)
            addProperty("suffix", request.prompt.suffix)
        }
        addProperty("max_tokens", request.maxTokens)
        addProperty("temperature", 0.0)
        addProperty("stream", true)
        // ponytail: OpenAI accepts at most four stop sequences; the rest are enforced locally by Cleanup.
        add("stop", JsonArray().also { array -> request.stop.take(MAX_STOPS).forEach(array::add) })
    }.toString()

    private fun chatMessages(prompt: FimPrompt) = JsonArray().apply {
        add(JsonObject().apply { addProperty("role", "system"); addProperty("content", CHAT_INSTRUCTION) })
        add(JsonObject().apply { addProperty("role", "user"); addProperty("content", prompt.prefix + CURSOR + prompt.suffix) })
    }

    private fun describe(error: Throwable): BackendException {
        val cause = if (error is CompletionException && error.cause != null) error.cause!! else error
        return when (cause) {
            is BackendException -> cause
            is HttpTimeoutException -> BackendException("The completion server did not answer in time", cause)
            is IOException -> BackendException("Cannot reach the completion server: ${cause.javaClass.simpleName}", cause)
            else -> BackendException("The completion request failed: ${cause.javaClass.simpleName}", cause)
        }
    }

    /** Drops in-flight requests at once instead of waiting for them. */
    override fun close() {
        if (clientHolder.isInitialized()) client.shutdownNow()
    }

    private companion object {
        const val CURSOR = "<CURSOR>"
        const val CHAT_INSTRUCTION = "You are a code completion engine. The user message holds a file with a $CURSOR marker. " +
            "Reply with only the exact text to insert at the marker: no explanation, no code fences, " +
            "and do not repeat the text around the marker."
        const val MAX_STOPS = 4
    }
}
