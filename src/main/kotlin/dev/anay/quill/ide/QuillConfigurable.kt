package dev.anay.quill.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import dev.anay.quill.QuillBundle
import dev.anay.quill.backend.ProbeResult
import dev.anay.quill.backend.EndpointPolicy
import dev.anay.quill.settings.ApiKeyStore
import dev.anay.quill.settings.QuillSettings
import javax.swing.JPasswordField

/** One click setup for the common servers. Choosing "Custom" leaves the fields as they are. */
private enum class Preset(private val labelKey: String, val endpoint: String?, val model: String?) {
    OLLAMA("settings.preset.ollama", "http://localhost:11434/v1/completions", "qwen2.5-coder:1.5b-base"),
    CORALBRICKS("settings.preset.coralbricks", "https://inference.coralbricks.ai/v1/chat/completions", "deepseek-v4.1-flash-fast"),
    CUSTOM("settings.preset.custom", null, null);

    override fun toString(): String = QuillBundle.message(labelKey)

    companion object {
        fun forEndpoint(endpoint: String): Preset = entries.firstOrNull { it.endpoint == endpoint } ?: CUSTOM
    }
}

class QuillConfigurable : BoundSearchableConfigurable("Quill", "dev.anay.quill.settings") {
    private var apiKey = ""
    private lateinit var endpointField: Cell<JBTextField>
    private lateinit var modelField: Cell<JBTextField>
    private lateinit var keyField: Cell<JPasswordField>
    private val result = JBLabel()

    override fun createPanel(): DialogPanel {
        val state = QuillSettings.getInstance().state
        return panel {
            row { checkBox(QuillBundle.message("settings.enable")).bindSelected(state::enabled) }
            row {
                checkBox(QuillBundle.message("settings.verify")).bindSelected(state::verify)
                    .comment(QuillBundle.message("settings.verify.comment"))
            }
            group(QuillBundle.message("settings.group.server")) {
                row(QuillBundle.message("settings.preset")) {
                    comboBox(Preset.entries).applyToComponent { selectedItem = Preset.forEndpoint(state.endpoint) }.onChanged { box ->
                        val preset = box.selectedItem as Preset
                        preset.endpoint?.let { endpointField.component.text = it }
                        preset.model?.let { modelField.component.text = it }
                    }
                }
                row(QuillBundle.message("settings.endpoint")) {
                    endpointField = textField().columns(COLUMNS_LARGE).bindText(state::endpoint)
                        .comment(QuillBundle.message("settings.endpoint.comment"))
                        .validationOnApply { field -> EndpointPolicy.validate(field.text)?.let { error(it) } }
                }
                row(QuillBundle.message("settings.model")) { modelField = textField().columns(COLUMNS_LARGE).bindText(state::model) }
                row(QuillBundle.message("settings.key")) {
                    keyField = passwordField().columns(COLUMNS_LARGE).bindText(::apiKey)
                        .comment(QuillBundle.message("settings.key.comment"))
                    button(QuillBundle.message("settings.key.remove")) { ApiKeyStore.getInstance().save(null) }
                }
                row {
                    button(QuillBundle.message("settings.test")) { testConnection() }
                    cell(result)
                }
            }
            group(QuillBundle.message("settings.group.behaviour")) {
                row(QuillBundle.message("settings.tokens")) { intTextField(QuillSettings.TOKENS).bindIntText(state::maxTokens) }
                row(QuillBundle.message("settings.debounce")) { intTextField(QuillSettings.DEBOUNCE_MS).bindIntText(state::debounceMs) }
                row(QuillBundle.message("settings.excluded")) {
                    textField().columns(COLUMNS_LARGE).bindText(state::excludedPatterns)
                        .comment(QuillBundle.message("settings.excluded.comment"))
                }
            }
        }
    }

    private fun testConnection() {
        result.text = QuillBundle.message("settings.test.running")
        val url = endpointField.component.text.trim()
        EndpointPolicy.validate(url)?.let { result.text = it; return }
        val typedKey = String(keyField.component.password)
        QuillService.getInstance().testConnection(url, modelField.component.text.trim(), typedKey.ifEmpty { null }) { probe ->
            ApplicationManager.getApplication().invokeLater({ result.text = describe(probe) }, ModalityState.any())
        }
    }

    private fun describe(probe: ProbeResult): String = when (probe) {
        is ProbeResult.Connected -> QuillBundle.message("probe.connected", probe.firstTokenMs, probe.sample)
        ProbeResult.Empty -> QuillBundle.message("probe.empty")
        is ProbeResult.TimedOut -> QuillBundle.message("probe.timeout", probe.seconds)
        ProbeResult.NotConfigured -> QuillBundle.message("probe.no.key")
        is ProbeResult.Failed -> probe.message
    }

    override fun apply() {
        super.apply()
        if (apiKey.isNotEmpty()) ApiKeyStore.getInstance().save(apiKey)
        apiKey = ""
        QuillService.getInstance().keepWarm()
        QuillService.getInstance().refreshStatusBar()
    }
}
