package me.rerere.rikkahub.voiceagent.audio

import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbe
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbes

internal sealed interface VoiceCaptureSource : AutoCloseable {
    val diagnosticLabel: String

    data object Microphone : VoiceCaptureSource {
        override val diagnosticLabel = "microphone"
        override fun close() = Unit
    }
}

internal suspend fun <Setup> setupVoiceCaptureSource(
    source: VoiceCaptureSource,
    setupMicrophone: suspend () -> Setup,
    setupFixture: suspend (VoiceCaptureFixtureSource) -> Setup,
): Setup = when (source) {
    VoiceCaptureSource.Microphone -> setupMicrophone()
    is VoiceCaptureFixtureSource -> setupFixture(source)
}

internal data class VoiceCaptureFixture(
    val path: String,
    val pcm16: ByteArray,
    val chunkBytes: Int,
    val chunkDelayMs: Long,
) {
    init {
        require(path.isNotBlank()) { "Fixture path is blank" }
        require(pcm16.isNotEmpty()) { "Fixture PCM is empty" }
        require(chunkBytes > 0) { "Fixture chunk size must be positive" }
        require(chunkDelayMs >= 0) { "Fixture chunk delay must not be negative" }
    }

    fun snapshot(): VoiceCaptureFixture = copy(pcm16 = pcm16.copyOf())
}

internal data class VoiceCaptureFixtureTriggerResult(
    val accepted: Boolean,
    val message: String,
)

internal object VoiceCaptureFixtureArming {
    const val ACTION_ARM_FIXTURE = "me.rerere.rikkahub.debug.voiceagent.ARM_CAPTURE_FIXTURE"
    const val ACTION_STAGE_FIXTURE = "me.rerere.rikkahub.debug.voiceagent.STAGE_CAPTURE_FIXTURE"
    const val ACTION_TRIGGER_FIXTURE = "me.rerere.rikkahub.debug.voiceagent.TRIGGER_CAPTURE_FIXTURE"
    const val EXTRA_INITIAL_PATH = "initial_path"
    const val EXTRA_STAGED_PATH = "staged_path"
    const val EXTRA_PATH = "path"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_CHUNK_BYTES = "chunk_bytes"
    const val EXTRA_CHUNK_DELAY_MS = "chunk_delay_ms"
    const val EXTRA_EXPECTED_SIZE = "expected_size"
    const val EXTRA_EXPECTED_SHA256 = "expected_sha256"
    const val DEFAULT_CHUNK_BYTES = 3_200
    const val DEFAULT_CHUNK_DELAY_MS = 100L

    private val lock = Any()
    private var generation = 0L
    private var pending: ArmedFixture? = null
    private var active: ActiveFixture? = null

    fun arm(
        initial: VoiceCaptureFixture,
        staged: List<VoiceCaptureFixture>,
    ): String = synchronized(lock) {
        check(active == null) { "A fixture capture source is already active" }
        check(generation < Long.MAX_VALUE) { "Fixture capture generation exhausted" }
        val stagedByPath = staged.associateBy(VoiceCaptureFixture::path)
        require(stagedByPath.size == staged.size) { "Fixture paths must be unique" }
        val token = "fixture-${generation + 1}"
        generation += 1
        pending = ArmedFixture(
            token = token,
            initial = initial.snapshot(),
            staged = stagedByPath.mapValues { (_, fixture) -> fixture.snapshot() },
        )
        token
    }

    fun claim(
        token: String,
        delays: suspend (Long) -> Unit = { delay(it) },
    ): Result<VoiceCaptureFixtureSource> = runCatching {
        synchronized(lock) {
            val armed = pending?.takeIf { it.token == token }
                ?: error("Fixture capture token is not armed")
            check(active == null) { "A fixture capture source is already active" }
            VoiceCaptureFixtureSource(
                token = armed.token,
                initial = armed.initial.snapshot(),
                staged = armed.staged.mapValues { (_, fixture) -> fixture.snapshot() },
                delays = delays,
                releaseOwner = ::release,
            ).also { source ->
                pending = null
                active = ActiveFixture(token, source)
            }
        }
    }

    fun trigger(token: String, path: String): VoiceCaptureFixtureTriggerResult {
        val source = synchronized(lock) {
            active?.takeIf { it.token == token }?.source
        } ?: return VoiceCaptureFixtureTriggerResult(
            accepted = false,
            message = "Fixture capture owner is not active",
        )
        return source.trigger(path)
    }

    fun stage(token: String, fixture: VoiceCaptureFixture): VoiceCaptureFixtureTriggerResult {
        val source = synchronized(lock) {
            active?.takeIf { it.token == token }?.source
        } ?: return VoiceCaptureFixtureTriggerResult(
            accepted = false,
            message = "Fixture capture owner is not active",
        )
        return source.stage(fixture)
    }

    fun claimSource(token: String?): Result<VoiceCaptureSource> =
        if (token == null) {
            Result.success(VoiceCaptureSource.Microphone)
        } else {
            claim(token)
        }

    private fun release(token: String, source: VoiceCaptureFixtureSource) {
        synchronized(lock) {
            val current = active
            if (current?.token == token && current.source === source) {
                active = null
            }
        }
    }

    internal fun clearForTest() {
        synchronized(lock) {
            pending = null
            active = null
            generation = 0
        }
    }

    private data class ArmedFixture(
        val token: String,
        val initial: VoiceCaptureFixture,
        val staged: Map<String, VoiceCaptureFixture>,
    )

    private data class ActiveFixture(
        val token: String,
        val source: VoiceCaptureFixtureSource,
    )
}

internal class VoiceCaptureFixtureSource(
    private val token: String,
    initial: VoiceCaptureFixture,
    staged: Map<String, VoiceCaptureFixture>,
    private val delays: suspend (Long) -> Unit,
    private val releaseOwner: (String, VoiceCaptureFixtureSource) -> Unit,
    private val automationProbeProvider: () -> VoiceAutomationAudioProbe? =
        VoiceAutomationAudioProbes::activeSharedOrNull,
) : VoiceCaptureSource {
    override val diagnosticLabel = "fixture"
    private val lock = Any()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private val queued = ArrayDeque<VoiceCaptureFixture>()
    private val staged = staged.toMutableMap()
    private var initial: VoiceCaptureFixture? = initial
    private var nextPumpGeneration = 0L
    private var activePumpGeneration: Long? = null
    private var pumpingGeneration: Long? = null
    private var idle = CompletableDeferred(Unit)
    private var closed = false

    fun startInitial(): Boolean {
        val fixture = synchronized(lock) {
            if (closed) return false
            initial?.also {
                initial = null
                enqueueLocked(it)
            }
        } ?: return false
        wakeup.trySend(Unit)
        return fixture.pcm16.isNotEmpty()
    }

    fun trigger(path: String): VoiceCaptureFixtureTriggerResult {
        val fixture = synchronized(lock) {
            if (closed) {
                return VoiceCaptureFixtureTriggerResult(false, "Fixture capture source is closed")
            }
            if (pumpingGeneration != null || queued.isNotEmpty()) {
                return VoiceCaptureFixtureTriggerResult(false, "Fixture capture source is busy")
            }
            staged.remove(path)?.also(::enqueueLocked)
        } ?: return VoiceCaptureFixtureTriggerResult(false, "Fixture is not staged")
        wakeup.trySend(Unit)
        return VoiceCaptureFixtureTriggerResult(
            accepted = fixture.pcm16.isNotEmpty(),
            message = "Fixture accepted",
        )
    }

    fun stage(fixture: VoiceCaptureFixture): VoiceCaptureFixtureTriggerResult = synchronized(lock) {
        if (closed) return VoiceCaptureFixtureTriggerResult(false, "Fixture capture source is closed")
        if (fixture.path in staged || queued.any { it.path == fixture.path }) {
            return VoiceCaptureFixtureTriggerResult(false, "Fixture path is already owned")
        }
        staged[fixture.path] = fixture.snapshot()
        VoiceCaptureFixtureTriggerResult(true, "Fixture staged")
    }

    suspend fun pump(
        onPcm16: (ByteArray) -> Unit,
        onFixtureComplete: () -> Unit,
    ) {
        val pumpGeneration = synchronized(lock) {
            check(!closed) { "Fixture capture source is closed" }
            check(nextPumpGeneration < Long.MAX_VALUE) { "Fixture pump generation exhausted" }
            nextPumpGeneration += 1
            activePumpGeneration = nextPumpGeneration
            nextPumpGeneration
        }
        try {
            while (true) {
                val fixture = synchronized(lock) {
                    if (closed || activePumpGeneration != pumpGeneration) return
                    queued.pollFirst()?.also {
                        pumpingGeneration = pumpGeneration
                    }
                }
                if (fixture == null) {
                    if (wakeup.receiveCatching().getOrNull() == null) return
                    continue
                }
                pumpFixture(pumpGeneration, fixture, onPcm16, onFixtureComplete)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            runCatching(::close).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            synchronized(lock) {
                if (activePumpGeneration == pumpGeneration) {
                    activePumpGeneration = null
                }
                if (pumpingGeneration == pumpGeneration) {
                    pumpingGeneration = null
                    completeIdleLocked()
                }
            }
        }
    }

    suspend fun awaitIdle() {
        val completion = synchronized(lock) { idle }
        completion.await()
    }

    override fun close() {
        val firstClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                initial = null
                staged.clear()
                queued.clear()
                activePumpGeneration = null
                pumpingGeneration = null
                completeIdleLocked()
                true
            }
        }
        if (!firstClose) return
        wakeup.close()
        releaseOwner(token, this)
    }

    private suspend fun pumpFixture(
        pumpGeneration: Long,
        fixture: VoiceCaptureFixture,
        onPcm16: (ByteArray) -> Unit,
        onFixtureComplete: () -> Unit,
    ) {
        var offset = 0
        val probe = automationProbeProvider()
        probe?.onInjectionStarted(fixture.pcm16.size.toLong())
        try {
            while (offset < fixture.pcm16.size) {
                if (!isCurrent(pumpGeneration)) return
                val end = (offset + fixture.chunkBytes).coerceAtMost(fixture.pcm16.size)
                onPcm16(fixture.pcm16.copyOfRange(offset, end))
                probe?.onInjectionChunk(end - offset)
                offset = end
                if (offset < fixture.pcm16.size && fixture.chunkDelayMs > 0) {
                    delays(fixture.chunkDelayMs)
                }
            }
            if (isCurrent(pumpGeneration)) {
                probe?.onInjectionCompleted()
                probe?.onCaptureAttested(
                    source = diagnosticLabel,
                    micBytes = 0,
                    fixtureBytes = fixture.pcm16.size.toLong(),
                )
                onFixtureComplete()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            synchronized(lock) {
                if (pumpingGeneration == pumpGeneration) {
                    pumpingGeneration = null
                    completeIdleLocked()
                }
            }
        }
    }

    private fun enqueueLocked(fixture: VoiceCaptureFixture) {
        queued.addLast(fixture)
        if (idle.isCompleted) {
            idle = CompletableDeferred()
        }
    }

    private fun completeIdleLocked() {
        if (queued.isEmpty() && pumpingGeneration == null) {
            idle.complete(Unit)
        }
    }

    private fun isCurrent(pumpGeneration: Long): Boolean = synchronized(lock) {
        !closed && activePumpGeneration == pumpGeneration && pumpingGeneration == pumpGeneration
    }
}
