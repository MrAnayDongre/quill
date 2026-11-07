package dev.anay.quill.settings

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.PatternSyntaxException

internal object Exclusions {
    // Environment files hold secrets by convention, so they stay excluded whatever the user configures.
    private const val ALWAYS = ".env, .env.*, *.env"

    private class Compiled(val patterns: String, val matchers: List<PathMatcher>)

    // Called on the UI thread for every edit, so the globs are compiled once per setting value, not once per call.
    private val last = AtomicReference<Compiled?>()

    fun isExcluded(fileName: String, userPatterns: String): Boolean {
        val name = Path.of(fileName)
        return matchersFor(userPatterns).any { it.matches(name) }
    }

    private fun matchersFor(userPatterns: String): List<PathMatcher> {
        last.get()?.takeIf { it.patterns == userPatterns }?.let { return it.matchers }
        val matchers = "$ALWAYS,$userPatterns".split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull(::compile)
        last.set(Compiled(userPatterns, matchers))
        return matchers
    }

    private fun compile(glob: String): PathMatcher? = try {
        FileSystems.getDefault().getPathMatcher("glob:$glob")
    } catch (_: PatternSyntaxException) {
        null
    }
}
