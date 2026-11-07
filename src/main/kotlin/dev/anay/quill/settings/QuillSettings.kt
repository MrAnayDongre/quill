package dev.anay.quill.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

/** Plain settings. The API key is not here: it lives in the password safe, see [ApiKeyStore]. */
@Service(Service.Level.APP)
@State(name = "QuillSettings", storages = [Storage("quill.xml")])
class QuillSettings : PersistentStateComponent<QuillSettings.State> {

    class State {
        var enabled: Boolean = true
        var verify: Boolean = true
        var endpoint: String = "http://localhost:11434/v1/completions"
        var model: String = "qwen2.5-coder:1.5b-base"
        var maxTokens: Int = 128
        var debounceMs: Int = 150
        var excludedPatterns: String = "*.pem, *.key, *.p12, *.jks, id_rsa*, secrets.*"
    }

    private val state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
        state.maxTokens = state.maxTokens.coerceIn(TOKENS)
        state.debounceMs = state.debounceMs.coerceIn(DEBOUNCE_MS)
    }

    companion object {
        val TOKENS = 16..1024
        val DEBOUNCE_MS = 0..2000

        fun getInstance(): QuillSettings = service()
    }
}
