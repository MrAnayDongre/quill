package dev.anay.quill.backend

import java.net.URI
import java.time.Duration

internal data class Endpoint(val url: String, val model: String, val apiKey: String?, val timeout: Duration) {
    val isChat: Boolean get() = URI(url).path.orEmpty().trimEnd('/').endsWith("/chat/completions")

    init {
        EndpointPolicy.validate(url)?.let { throw BackendException(it) }
        if (!apiKey.isNullOrEmpty() && !EndpointPolicy.isLoopback(URI(url).host) && URI(url).scheme != "https") {
            throw BackendException("The API key is only sent over https or to a local server")
        }
    }
}

internal object EndpointPolicy {
    /** Returns a message describing why [url] cannot be used, or null if it is fine. */
    fun validate(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "The endpoint is not a valid URL"
        if (uri.scheme != "http" && uri.scheme != "https") return "The endpoint must start with http:// or https://"
        if (uri.host.isNullOrEmpty()) return "The endpoint has no host"
        return null
    }

    fun isLoopback(host: String?): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
}
