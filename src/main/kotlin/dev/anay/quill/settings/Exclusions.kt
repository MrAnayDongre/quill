package dev.anay.quill.settings

import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.regex.PatternSyntaxException

internal object Exclusions {
    // Environment files hold secrets by convention, so they stay excluded whatever the user configures.
    private const val ALWAYS = ".env, .env.*, *.env"

    fun isExcluded(fileName: String, userPatterns: String): Boolean {
        val name = Path.of(fileName)
        return "$ALWAYS,$userPatterns".split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { matches(it, name) }
    }

    private fun matches(glob: String, name: Path): Boolean = try {
        FileSystems.getDefault().getPathMatcher("glob:$glob").matches(name)
    } catch (_: PatternSyntaxException) {
        false
    }
}
