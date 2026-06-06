package me.rerere.rikkahub.service.generation

import android.content.Context
import me.rerere.rikkahub.service.GenerationForegroundService

/**
 * Seam that hides the Android service calls behind a pure interface so the foreground-lifecycle state
 * machine ([GenerationForegroundCoordinator]) can be unit-tested on the JVM without a real service.
 */
interface GenerationForegroundController {
    fun start()
    fun stop()
    fun renew()
}

/**
 * Production [GenerationForegroundController]: pure delegation to [GenerationForegroundService] with the
 * captured [Context]. Holds no logic beyond forwarding, so ChatService keeps zero direct service-start
 * code.
 */
class AndroidGenerationForegroundController(
    private val context: Context,
) : GenerationForegroundController {
    override fun start() = GenerationForegroundService.start(context)
    override fun stop() = GenerationForegroundService.stop(context)
    override fun renew() = GenerationForegroundService.renew(context)
}
