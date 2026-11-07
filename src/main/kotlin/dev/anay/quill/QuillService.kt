package dev.anay.quill

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/** Wires settings, the key store and the HTTP backend into one engine for the whole IDE. */
@Service(Service.Level.APP)
class QuillService(private val scope: CoroutineScope) : Disposable {
    private val backend = OpenAiBackend(::currentEndpoint)
    private val lastWarmUp = AtomicLong(0)

    val stats = Stats(onChange = ::refreshStatusBar)
    val engine = CompletionEngine(backend, ::currentConfig, FailureReporter(::notifyFailure), stats)

    /** Called on editor activity. Keeps the connection to the server open so the first suggestion is not slower. */
    fun keepWarm() {
        val now = System.currentTimeMillis()
        val last = lastWarmUp.get()
        if (now - last < WARM_INTERVAL_MS || !lastWarmUp.compareAndSet(last, now)) return
        scope.launch {
            try {
                backend.warmUp()
            } catch (_: NotConfiguredException) {
                // Nothing to warm up until the user adds a key.
            }
        }
    }

    /** Runs the settings page "Test connection" with the values currently typed into it. */
    fun testConnection(url: String, model: String, typedKey: String?, onResult: (String) -> Unit) {
        scope.launch {
            val key = typedKey ?: ApiKeyStore.getInstance().get()
            OpenAiBackend({ endpointFor(url, model, key) }).use { onResult(Probe.run(it)) }
        }
    }

    private suspend fun currentEndpoint(): Endpoint {
        val state = QuillSettings.getInstance().state
        return endpointFor(state.endpoint.trim(), state.model, ApiKeyStore.getInstance().get())
    }

    private fun endpointFor(url: String, model: String, key: String?): Endpoint {
        val endpoint = Endpoint(url, model, key, Duration.ofMillis(REQUEST_TIMEOUT_MS))
        // ponytail: hosted servers are assumed to need a key; an open remote server needs any placeholder key.
        if (key.isNullOrEmpty() && !EndpointPolicy.isLoopback(URI(url).host)) throw NotConfiguredException()
        return endpoint
    }

    private fun currentConfig(): EngineConfig {
        val state = QuillSettings.getInstance().state
        return EngineConfig(state.maxTokens, state.debounceMs.toLong(), REQUEST_TIMEOUT_MS)
    }

    private fun refreshStatusBar() = ApplicationManager.getApplication().invokeLater {
        for (project in ProjectManager.getInstance().openProjects) {
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(QuillStatusBarFactory.ID)
        }
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
        // ponytail: fixed limits; promote to settings if users need to tune them.
        private const val REQUEST_TIMEOUT_MS = 10_000L
        private const val WARM_INTERVAL_MS = 20_000L

        fun getInstance(): QuillService = service()
    }
}
