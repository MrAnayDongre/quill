package dev.anay.quill

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
import java.time.Duration
import javax.swing.JPasswordField

/** One click setup for the common servers. Choosing "Custom" leaves the fields as they are. */
private enum class Preset(val label: String, val endpoint: String?, val model: String?) {
    OLLAMA("Ollama (local)", "http://localhost:11434/v1/completions", "qwen2.5-coder:1.5b-base"),
    CORALBRICKS("CoralBricks", "https://inference.coralbricks.ai/v1/chat/completions", "deepseek-v4.1-flash-fast"),
    CUSTOM("Custom", null, null);

    override fun toString() = label

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
            row { checkBox("Enable inline completion").bindSelected(state::enabled) }
            group("Server") {
                row("Preset:") {
                    comboBox(Preset.entries).applyToComponent { selectedItem = Preset.forEndpoint(state.endpoint) }.onChanged { box ->
                        val preset = box.selectedItem as Preset
                        preset.endpoint?.let { endpointField.component.text = it }
                        preset.model?.let { modelField.component.text = it }
                    }
                }
                row("Endpoint:") {
                    endpointField = textField().columns(COLUMNS_LARGE).bindText(state::endpoint)
                        .comment("An OpenAI compatible /v1/completions or /v1/chat/completions URL")
                        .validationOnApply { field -> EndpointPolicy.validate(field.text)?.let { error(it) } }
                }
                row("Model:") { modelField = textField().columns(COLUMNS_LARGE).bindText(state::model) }
                row("API key:") {
                    keyField = passwordField().columns(COLUMNS_LARGE).bindText(::apiKey)
                        .comment("Kept in the IDE password safe. Leave empty to keep the stored key.")
                    button("Remove Stored Key") { ApiKeyStore.getInstance().save(null) }
                }
                row {
                    button("Test Connection") { testConnection() }
                    cell(result)
                }
            }
            group("Behaviour") {
                row("Max tokens:") { intTextField(QuillSettings.TOKENS).bindIntText(state::maxTokens) }
                row("Debounce (ms):") { intTextField(QuillSettings.DEBOUNCE_MS).bindIntText(state::debounceMs) }
                row("Excluded files:") {
                    textField().columns(COLUMNS_LARGE).bindText(state::excludedPatterns)
                        .comment("Comma separated globs matched against file names. Environment files are always excluded.")
                }
            }
        }
    }

    private fun testConnection() {
        result.text = "Testing..."
        val url = endpointField.component.text.trim()
        EndpointPolicy.validate(url)?.let { result.text = it; return }
        val typedKey = String(keyField.component.password)
        QuillService.getInstance().testConnection(url, modelField.component.text.trim(), typedKey.ifEmpty { null }) { message ->
            ApplicationManager.getApplication().invokeLater({ result.text = message }, ModalityState.any())
        }
    }

    override fun apply() {
        super.apply()
        if (apiKey.isNotEmpty()) ApiKeyStore.getInstance().save(apiKey)
        apiKey = ""
        QuillService.getInstance().keepWarm()
        QuillService.getInstance().refreshStatusBar()
    }
}
