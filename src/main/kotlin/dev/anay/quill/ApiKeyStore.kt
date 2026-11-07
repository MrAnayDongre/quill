package dev.anay.quill

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Optional

/** The only place the API key is kept. Reads are cached because the password safe can be slow. */
@Service(Service.Level.APP)
class ApiKeyStore(private val scope: CoroutineScope) {
    private val attributes = CredentialAttributes(generateServiceName("Quill", "apiKey"))

    @Volatile
    private var cached: Optional<String>? = null

    suspend fun get(): String? {
        cached?.let { return it.orElse(null) }
        val key = withContext(Dispatchers.IO) { PasswordSafe.instance.getPassword(attributes) }
        cached = Optional.ofNullable(key)
        return key
    }

    fun save(key: String?) {
        val value = key?.takeIf { it.isNotEmpty() }
        cached = Optional.ofNullable(value)
        scope.launch(Dispatchers.IO) { PasswordSafe.instance.setPassword(attributes, value) }
    }

    companion object {
        fun getInstance(): ApiKeyStore = service()
    }
}
