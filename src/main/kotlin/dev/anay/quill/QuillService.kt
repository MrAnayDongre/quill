package dev.anay.quill

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import java.time.Duration

/** Wires settings, the key store and the HTTP backend into one engine for the whole IDE. */
@Service(Service.Level.APP)
class QuillService : Disposable {
    private val backend = OpenAiBackend(::currentEndpoint)

    val engine = CompletionEngine(backend, ::currentConfig, FailureReporter(::notifyFailure))

    private suspend fun currentEndpoint(): Endpoint {
        val state = QuillSettings.getInstance().state
        return Endpoint(state.endpoint.trim(), state.model, ApiKeyStore.getInstance().get(), Duration.ofMillis(REQUEST_TIMEOUT_MS))
    }

    private fun currentConfig(): EngineConfig {
        val state = QuillSettings.getInstance().state
        return EngineConfig(state.maxTokens, state.debounceMs.toLong(), REQUEST_TIMEOUT_MS)
    }

    private fun notifyFailure(message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Quill", message, NotificationType.WARNING)
            .addAction(NotificationAction.createSimple("Open settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(null, QuillConfigurable::class.java)
            })
            .notify(null)
    }

    override fun dispose() = backend.close()

    companion object {
        const val NOTIFICATION_GROUP = "Quill"
        // ponytail: fixed limit; promote to a setting if users need to tune it.
        private const val REQUEST_TIMEOUT_MS = 10_000L

        fun getInstance(): QuillService = service()
    }
}
