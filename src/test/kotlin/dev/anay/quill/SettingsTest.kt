package dev.anay.quill

import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun stateSurvivesASerializationRoundTrip() {
        val original = QuillSettings.State().apply {
            endpoint = "https://api.example.com/v1/completions"
            model = "m"
            maxTokens = 64
            debounceMs = 250
        }
        val xml = XmlSerializer.serialize(original)
        val restored = XmlSerializer.deserialize(xml, QuillSettings.State::class.java)
        assertEquals(original.endpoint, restored.endpoint)
        assertEquals(64, restored.maxTokens)
        assertEquals(250, restored.debounceMs)
    }

    @Test
    fun loadingClampsOutOfRangeValues() {
        val settings = QuillSettings()
        settings.loadState(QuillSettings.State().apply { maxTokens = 1_000_000; debounceMs = -5 })
        assertEquals(1024, settings.state.maxTokens)
        assertEquals(0, settings.state.debounceMs)
    }

    @Test
    fun noFieldCanHoldACredential() {
        val fields = QuillSettings.State::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fields.none { "key" in it || "password" in it || "secret" in it })
    }

    @Test
    fun environmentFilesAreAlwaysExcluded() {
        assertTrue(Exclusions.isExcluded(".env", ""))
        assertTrue(Exclusions.isExcluded(".env.local", ""))
        assertTrue(Exclusions.isExcluded("prod.env", ""))
    }

    @Test
    fun userPatternsAreGlobsOnTheFileName() {
        assertTrue(Exclusions.isExcluded("server.pem", "*.pem, secrets.*"))
        assertTrue(Exclusions.isExcluded("secrets.yaml", "*.pem, secrets.*"))
        assertFalse(Exclusions.isExcluded("Main.kt", "*.pem, secrets.*"))
    }

    @Test
    fun brokenPatternsAreIgnored() {
        assertFalse(Exclusions.isExcluded("Main.kt", "[unclosed, "))
    }
}
