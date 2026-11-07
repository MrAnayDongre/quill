package dev.anay.quill

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class QuillConfigurable : BoundSearchableConfigurable("Quill", "dev.anay.quill.settings") {
    private var apiKey = ""

    override fun createPanel(): DialogPanel {
        val state = QuillSettings.getInstance().state
        return panel {
            row { checkBox("Enable inline completion").bindSelected(state::enabled) }
            row("Endpoint:") {
                textField().columns(COLUMNS_LARGE).bindText(state::endpoint)
                    .comment("Full URL of an OpenAI compatible completions endpoint")
                    .validationOnApply { field -> EndpointPolicy.validate(field.text)?.let { error(it) } }
            }
            row("Model:") { textField().columns(COLUMNS_LARGE).bindText(state::model) }
            row("API key:") {
                passwordField().columns(COLUMNS_LARGE).bindText(::apiKey)
                    .comment("Kept in the IDE password safe. Leave empty to keep the stored key.")
                button("Remove Stored Key") { ApiKeyStore.getInstance().save(null) }
            }
            row("Max tokens:") { intTextField(QuillSettings.TOKENS).bindIntText(state::maxTokens) }
            row("Debounce (ms):") { intTextField(QuillSettings.DEBOUNCE_MS).bindIntText(state::debounceMs) }
            row("Excluded files:") {
                textField().columns(COLUMNS_LARGE).bindText(state::excludedPatterns)
                    .comment("Comma separated globs matched against file names. Environment files are always excluded.")
            }
        }
    }

    override fun apply() {
        super.apply()
        if (apiKey.isNotEmpty()) ApiKeyStore.getInstance().save(apiKey)
        apiKey = ""
    }
}
