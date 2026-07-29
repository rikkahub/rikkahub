package me.rerere.rikkahub.voiceagent

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

internal fun CancellationException.canonicalVoiceAgentCancellation(): CancellationException {
    var canonical = this
    val visited = Collections.newSetFromMap(
        IdentityHashMap<CancellationException, Boolean>(),
    )
    visited += canonical
    while (true) {
        val original = canonical.cause as? CancellationException ?: return canonical
        if (original.message != canonical.message || !visited.add(original)) return canonical
        canonical = original
    }
}

internal fun Throwable.addVoiceAgentSuppressedDistinct(error: Throwable) {
    if (error !== this && error !in suppressed) addSuppressed(error)
}
