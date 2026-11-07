package dev.anay.quill.ide

import dev.anay.quill.QuillBundle
import dev.anay.quill.completion.CompletionStats

/** The status bar text: short, and led by the number a user cares about. */
internal fun statusLabel(enabled: Boolean, stats: CompletionStats): String = when {
    !enabled -> QuillBundle.message("status.off")
    stats.state == CompletionStats.State.NOT_CONFIGURED -> QuillBundle.message("status.add.key")
    stats.state == CompletionStats.State.FAILING -> QuillBundle.message("status.offline")
    else -> stats.median()?.let { QuillBundle.message("status.latency", it) } ?: QuillBundle.message("status.idle")
}

internal fun statusTooltip(stats: CompletionStats): String = buildString {
    stats.median()?.let { append(QuillBundle.message("status.tooltip.speed", it, stats.p95() ?: it, stats.count())).append(' ') }
    if (stats.namesCaught > 0) append(QuillBundle.message("status.tooltip.checked", stats.namesCaught, stats.autoFixes, stats.repairs)).append(' ')
    append(QuillBundle.message("status.tooltip.click"))
}
