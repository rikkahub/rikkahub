package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioRouteControllerTest {
    @Test
    fun `cancellation after active publication retires exact capture before dispatcher return`() {
        VoiceAudioDebugInjector.clearForTest()
        val cancellation = CancellationException("capture dispatcher return cancelled")
        val taskCancellationFailure = IllegalArgumentException("task cancellation failed")
        val recorderStopFailure = UnsupportedOperationException("recorder stop failed")
        val recorderReleaseFailure = AssertionError("recorder release failed")
        val routeFailure = IllegalStateException("route retirement failed")
        val registrationCloseFailure = IllegalStateException("debug registration close failed")
        val events = CopyOnWriteArrayList<String>()
        val ownership = VoiceAudioCaptureOwnership<Any, Job>(
            startRecorder = {},
            isRecorderRecording = { true },
            stopRecorder = {
                events += "stop-recorder"
                throw recorderStopFailure
            },
            releaseRecorder = {
                events += "release-recorder"
                throw recorderReleaseFailure
            },
            startTask = Job::start,
            cancelTask = {
                it.cancel()
                events += "cancel-task"
                throw taskCancellationFailure
            },
        )
        val lease = FakeCaptureRouteLease(
            onRetire = {
                events += "retire-route"
                throw routeFailure
            },
        )
        val token = ownership.reserve()
        assertTrue(ownership.publishRoute(token, lease))
        val setup = VoiceAudioCaptureSetup(token, 64, Any())
        val callerDispatcher = QueuedCoroutineDispatcher()
        val captureTaskDispatcher = QueuedCoroutineDispatcher()
        val workerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val callerOwner = SupervisorJob()
        val captureTaskOwner = SupervisorJob()
        val captureTaskBodyEntered = AtomicBoolean(false)
        val debugCaptureRegistrations =
            VoiceAudioDebugCaptureRegistrationOwner<
                VoiceAudioCaptureToken,
                Any,
                FailingDebugRegistration,
            >(FailingDebugRegistration::close)
        lateinit var staleRegistration: FailingDebugRegistration
        val observedFailure = AtomicReference<Throwable?>()
        val callerCompleted = CountDownLatch(1)
        val caller = CoroutineScope(callerOwner + callerDispatcher).launch {
            try {
                runVoiceAudioCaptureStartOnDispatcher(
                    dispatcher = workerDispatcher,
                    startCapture = { onStarted ->
                        val task = CoroutineScope(captureTaskOwner + captureTaskDispatcher).launch(
                            start = CoroutineStart.LAZY,
                        ) {
                            captureTaskBodyEntered.set(true)
                            try {
                                CompletableDeferred<Unit>().await()
                            } finally {
                                debugCaptureRegistrations.unregister(token, setup.recorder)
                            }
                        }
                        assertEquals(
                            VoiceAudioCaptureStartOutcome.Started,
                            publishVoiceAudioCapture(
                                ownership = ownership,
                                setup = setup,
                                task = task,
                                cancelTask = { events += "local-cancel-task" },
                                releaseRecorder = { events += "local-release-recorder" },
                            ),
                        )
                        onStarted(VoiceAudioCaptureAdmission(token, setup.recorder))
                        val injectorRegistration = requireNotNull(
                            VoiceAudioDebugInjector.registerCaptureIfCurrent(
                                onPcm16 = {},
                                onInjectionComplete = {},
                                isCurrent = { ownership.isCurrent(token, setup.recorder) },
                            ),
                        )
                        staleRegistration = FailingDebugRegistration(
                            delegate = injectorRegistration,
                            onClose = {
                                events += "close-debug-registration"
                                throw registrationCloseFailure
                            },
                        )
                        assertTrue(
                            debugCaptureRegistrations.publish(token, setup.recorder, staleRegistration) {
                                ownership.isCurrent(token, setup.recorder)
                            },
                        )
                    },
                    retireCapture = { admission -> ownership.abort(admission.token) },
                    unregisterDebugCapture = { admission ->
                        debugCaptureRegistrations.unregister(admission.token, admission.recorder)
                    },
                )
            } catch (failure: Throwable) {
                observedFailure.set(failure)
            } finally {
                callerCompleted.countDown()
            }
        }

        try {
            callerDispatcher.takeNext().run()
            val dispatcherReturn = callerDispatcher.takeNext()
            assertTrue(ownership.isCurrent(token, setup.recorder))

            caller.cancel(cancellation)
            dispatcherReturn.run()
            assertTrue(callerCompleted.await(5, TimeUnit.SECONDS))

            assertFalse(captureTaskBodyEntered.get())
            val staleInjection = VoiceAudioDebugInjector.injectPcm16(
                pcm16 = byteArrayOf(1, 2),
                chunkBytes = 2,
                chunkDelayMs = 0L,
            )
            assertFalse("stale debug injection was reported delivered", staleInjection.delivered)
            assertEquals(
                listOf(
                    "cancel-task",
                    "stop-recorder",
                    "release-recorder",
                    "retire-route",
                    "close-debug-registration",
                ),
                events,
            )
            assertEquals(1, staleRegistration.closeCalls)
            assertFalse(ownership.isCurrent(token, setup.recorder))
            assertSame(cancellation, observedFailure.get())
            assertEquals(
                listOf(
                    taskCancellationFailure,
                    recorderStopFailure,
                    recorderReleaseFailure,
                    routeFailure,
                    registrationCloseFailure,
                ),
                cancellation.suppressed.toList(),
            )
        } finally {
            callerOwner.cancel()
            captureTaskOwner.cancel()
            workerDispatcher.close()
            VoiceAudioDebugInjector.clearForTest()
        }
    }

    @Test
    fun `cancellation after prior stop aborts exact reserved owner before route acquisition`() {
        val cancellation = CancellationException("capture start cancelled")
        val ownership = fakeSetupOwnership()
        var acquireCalls = 0
        var bufferLookupCalls = 0
        var recorderCreationCalls = 0

        val thrown = runCatching {
            runBlocking {
                currentCoroutineContext()[Job]!!.cancel(cancellation)
                ownership.stop()
                setupVoiceAudioCapture(
                    ownership = ownership,
                    acquireRoute = {
                        acquireCalls += 1
                        FakeCaptureRouteLease()
                    },
                    lookupBufferSize = {
                        bufferLookupCalls += 1
                        64
                    },
                    createRecorder = {
                        recorderCreationCalls += 1
                        Any()
                    },
                    configureRecorder = { _, _ -> },
                    isRecorderInitialized = { true },
                    releaseRecorder = {},
                )
            }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(0, acquireCalls)
        assertEquals(0, bufferLookupCalls)
        assertEquals(0, recorderCreationCalls)
        assertTrue(ownership.abort(ownership.reserve()))
    }

    @Test
    fun `cancellation after setup checkpoint cleans locals before activation`() = runBlocking {
        val cancellation = CancellationException("late capture start cancellation")
        val recorderReleaseFailure = IllegalStateException("recorder release failed")
        val routeRetirementFailure = IllegalArgumentException("route retirement failed")
        val configureEntered = CountDownLatch(1)
        val releaseConfiguration = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val lease = FakeCaptureRouteLease(
            onRetire = {
                events += "routeRetired"
                throw routeRetirementFailure
            },
        )
        val ownership = VoiceAudioCaptureOwnership<Any, Any>(
            startRecorder = { events += "startRecorder" },
            isRecorderRecording = { true },
            stopRecorder = { events += "stopRecorder" },
            releaseRecorder = { events += "releaseRecorder" },
            startTask = { events += "startTask"; true },
            cancelTask = { events += "cancelTask" },
        )
        val observedFailure = AtomicReference<Throwable?>()
        val setup = launch(Dispatchers.Default) {
            try {
                val captureSetup = requireNotNull(
                    setupVoiceAudioCapture(
                        ownership = ownership,
                        acquireRoute = { lease },
                        lookupBufferSize = { 64 },
                        createRecorder = { Any() },
                        configureRecorder = { _, _ ->
                            configureEntered.countDown()
                            releaseConfiguration.await(5, TimeUnit.SECONDS)
                        },
                        isRecorderInitialized = { true },
                        releaseRecorder = { events += "releaseRecorder" },
                    )
                )
                publishVoiceAudioCapture(
                    ownership = ownership,
                    setup = captureSetup,
                    task = Any(),
                    cancelTask = { events += "cancelTask" },
                    releaseRecorder = {
                        events += "releaseRecorder"
                        throw recorderReleaseFailure
                    },
                )
            } catch (failure: Throwable) {
                observedFailure.set(failure)
                throw failure
            }
        }
        assertTrue(configureEntered.await(5, TimeUnit.SECONDS))

        setup.cancel(cancellation)
        releaseConfiguration.countDown()
        withTimeout(TEST_TIMEOUT_MS) { setup.join() }

        assertSame(cancellation, observedFailure.get())
        assertEquals(listOf(recorderReleaseFailure, routeRetirementFailure), cancellation.suppressed.toList())
        assertEquals(listOf("cancelTask", "releaseRecorder", "routeRetired"), events)
        assertEquals(1, lease.retireCalls)
        assertTrue(ownership.abort(ownership.reserve()))
    }

    @Test
    fun `cancellation during route preparation keeps primary and retires exact route once`() = runBlocking {
        val cancellation = CancellationException("route preparation cancelled")
        val retirementFailure = IllegalStateException("route retirement failed")
        val prepareEntered = CompletableDeferred<Unit>()
        val prepareRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease(
            onPrepare = {
                events += "prepare"
                prepareEntered.complete(Unit)
                prepareRelease.await()
                throw cancellation
            },
            onRetire = {
                events += "routeRetired"
                throw retirementFailure
            },
        )
        val ownership = fakeSetupOwnership()
        var bufferLookupCalls = 0
        var recorderCreationCalls = 0
        val observedFailure = CompletableDeferred<Throwable>()
        val setup = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                setupVoiceAudioCapture(
                    ownership = ownership,
                    acquireRoute = { lease },
                    lookupBufferSize = {
                        bufferLookupCalls += 1
                        64
                    },
                    createRecorder = {
                        recorderCreationCalls += 1
                        Any()
                    },
                    configureRecorder = { _, _ -> events += "configure" },
                    isRecorderInitialized = { true },
                    releaseRecorder = { events += "recorderReleased" },
                )
            } catch (failure: Throwable) {
                observedFailure.complete(failure)
                throw failure
            }
        }
        withTimeout(TEST_TIMEOUT_MS) { prepareEntered.await() }

        prepareRelease.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { setup.join() }
        val thrown = withTimeout(TEST_TIMEOUT_MS) { observedFailure.await() }

        assertSame(cancellation, thrown)
        assertEquals(listOf(retirementFailure), cancellation.suppressed.toList())
        assertEquals(listOf("prepare", "routeRetired"), events)
        assertEquals(1, lease.retireCalls)
        assertEquals(0, bufferLookupCalls)
        assertEquals(0, recorderCreationCalls)
        assertTrue(ownership.abort(ownership.reserve()))
    }

    @Test
    fun `route preparation failure stays primary and suppresses exact retirement failure`() = runBlocking {
        val preparationFailure = IllegalArgumentException("route preparation failed")
        val retirementFailure = IllegalStateException("route retirement failed")
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease(
            onPrepare = {
                events += "prepare"
                throw preparationFailure
            },
            onRetire = {
                events += "routeRetired"
                throw retirementFailure
            },
        )
        val ownership = fakeSetupOwnership()
        var bufferLookupCalls = 0
        var recorderCreationCalls = 0

        val thrown = runCatching {
            setupVoiceAudioCapture(
                ownership = ownership,
                acquireRoute = { lease },
                lookupBufferSize = {
                    bufferLookupCalls += 1
                    64
                },
                createRecorder = {
                    recorderCreationCalls += 1
                    Any()
                },
                configureRecorder = { _, _ -> events += "configure" },
                isRecorderInitialized = { true },
                releaseRecorder = { events += "recorderReleased" },
            )
        }.exceptionOrNull()

        assertSame(preparationFailure, thrown)
        assertEquals(listOf(retirementFailure), preparationFailure.suppressed.toList())
        assertEquals(listOf("prepare", "routeRetired"), events)
        assertEquals(1, lease.retireCalls)
        assertEquals(0, bufferLookupCalls)
        assertEquals(0, recorderCreationCalls)
        assertTrue(ownership.abort(ownership.reserve()))
    }

    @Test
    fun `stop winning blocked route acquisition retires late route and skips remaining setup`() {
        val ownership = fakeSetupOwnership()
        val acquireEntered = CountDownLatch(1)
        val allowAcquireReturn = CountDownLatch(1)
        val setupReturned = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        val setupResult = AtomicReference<VoiceAudioCaptureSetup<Any>?>()
        val setup = thread(name = "blocked-route-stop-setup") {
            setupResult.set(
                runBlocking { setupVoiceAudioCapture(
                    ownership = ownership,
                    acquireRoute = {
                        acquireEntered.countDown()
                        allowAcquireReturn.await(5, TimeUnit.SECONDS)
                        lease
                    },
                    lookupBufferSize = { events += "buffer"; 64 },
                    createRecorder = { events += "recorder"; Any() },
                    configureRecorder = { _, _ -> events += "configure" },
                    isRecorderInitialized = { true },
                    releaseRecorder = { events += "recorderReleased" },
                ) },
            )
            setupReturned.countDown()
        }
        assertTrue(acquireEntered.await(5, TimeUnit.SECONDS))

        ownership.stop()
        assertFalse(setupReturned.await(100, TimeUnit.MILLISECONDS))
        allowAcquireReturn.countDown()
        setup.join(5_000)

        assertFalse(setup.isAlive)
        assertEquals(null, setupResult.get())
        assertEquals(listOf("routeRetired"), events)
        assertEquals(1, lease.retireCalls)
        assertTrue(ownership.abort(ownership.reserve()))
    }

    @Test
    fun `release winning blocked route acquisition retires late route and stays released`() {
        val ownership = fakeSetupOwnership()
        val acquireEntered = CountDownLatch(1)
        val allowAcquireReturn = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        val setupResult = AtomicReference<VoiceAudioCaptureSetup<Any>?>()
        val setup = thread(name = "blocked-route-release-setup") {
            setupResult.set(
                runBlocking { setupVoiceAudioCapture(
                    ownership = ownership,
                    acquireRoute = {
                        acquireEntered.countDown()
                        allowAcquireReturn.await(5, TimeUnit.SECONDS)
                        lease
                    },
                    lookupBufferSize = { events += "buffer"; 64 },
                    createRecorder = { events += "recorder"; Any() },
                    configureRecorder = { _, _ -> events += "configure" },
                    isRecorderInitialized = { true },
                    releaseRecorder = { events += "recorderReleased" },
                ) },
            )
        }
        assertTrue(acquireEntered.await(5, TimeUnit.SECONDS))

        assertTrue(ownership.release())
        allowAcquireReturn.countDown()
        setup.join(5_000)

        assertFalse(setup.isAlive)
        assertEquals(null, setupResult.get())
        assertEquals(listOf("routeRetired"), events)
        assertEquals(1, lease.retireCalls)
        assertEquals(
            "Voice audio engine is released",
            runCatching { ownership.reserve() }.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `route acquisition failure keeps primary and aborts reserved owner for reuse`() = runBlocking {
        val acquireFailure = IllegalStateException("route unavailable")
        val ownership = fakeSetupOwnership()
        val events = mutableListOf<String>()

        val thrown = runCatching {
            setupVoiceAudioCapture(
                ownership = ownership,
                acquireRoute = {
                    events += "acquire"
                    throw acquireFailure
                },
                lookupBufferSize = { events += "buffer"; 64 },
                createRecorder = { events += "recorder"; Any() },
                configureRecorder = { _, _ -> events += "configure" },
                isRecorderInitialized = { true },
                releaseRecorder = { events += "recorderReleased" },
            )
        }.exceptionOrNull()

        assertSame(acquireFailure, thrown)
        assertEquals(emptyList<Throwable>(), acquireFailure.suppressed.toList())
        assertEquals(listOf("acquire"), events)
        assertTrue(ownership.abort(ownership.reserve()))
    }

    @Test
    fun `buffer size failure aborts published route before recorder creation`() = runBlocking {
        val failure = IllegalStateException("minimum buffer unavailable")
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        val ownership = fakeSetupOwnership()
        var recorderCreationCalls = 0

        val thrown = runCatching {
            setupVoiceAudioCapture(
                ownership = ownership,
                acquireRoute = { lease },
                lookupBufferSize = {
                    events += "bufferLookup"
                    throw failure
                },
                createRecorder = {
                    recorderCreationCalls += 1
                    Any()
                },
                configureRecorder = { _, _ -> events += "configure" },
                isRecorderInitialized = { true },
                releaseRecorder = { events += "recorderReleased" },
            )
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(0, recorderCreationCalls)
        assertEquals(listOf("bufferLookup", "routeRetired"), events)
        assertEquals(1, lease.retireCalls)
        assertTrue(ownership.abort(ownership.reserve()))
    }

    @Test
    fun `recorder creation failure wraps cause and aborts published route`() = runBlocking {
        val cause = IllegalArgumentException("factory failed")
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        val ownership = fakeSetupOwnership()

        val thrown = runCatching {
            setupVoiceAudioCapture(
                ownership = ownership,
                acquireRoute = { lease },
                lookupBufferSize = { 64 },
                createRecorder = { throw cause },
                configureRecorder = { _, _ -> events += "configure" },
                isRecorderInitialized = { true },
                releaseRecorder = { events += "recorderReleased" },
            )
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals("AudioRecord creation failed", thrown?.message)
        assertSame(cause, thrown?.cause)
        assertEquals(listOf("routeRetired"), events)
        assertEquals(1, lease.retireCalls)
    }

    @Test
    fun `configuration failure keeps primary and suppresses recorder then route cleanup failures`() = runBlocking {
        val configureFailure = IllegalStateException("configuration failed")
        val releaseFailure = IllegalArgumentException("release failed")
        val routeFailure = UnsupportedOperationException("route retirement failed")
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease {
            events += "routeRetired"
            throw routeFailure
        }
        val ownership = fakeSetupOwnership()

        val thrown = runCatching {
            setupVoiceAudioCapture(
                ownership = ownership,
                acquireRoute = { lease },
                lookupBufferSize = { 64 },
                createRecorder = { Any() },
                configureRecorder = { _, _ ->
                    events += "configure"
                    throw configureFailure
                },
                isRecorderInitialized = { true },
                releaseRecorder = {
                    events += "recorderReleased"
                    throw releaseFailure
                },
            )
        }.exceptionOrNull()

        assertSame(configureFailure, thrown)
        assertEquals(listOf(releaseFailure, routeFailure), configureFailure.suppressed.toList())
        assertEquals(listOf("configure", "recorderReleased", "routeRetired"), events)
        assertEquals(1, lease.retireCalls)
    }

    @Test
    fun `uninitialized recorder releases recorder then aborts published route`() = runBlocking {
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        val ownership = fakeSetupOwnership()

        val thrown = runCatching {
            setupVoiceAudioCapture(
                ownership = ownership,
                acquireRoute = { lease },
                lookupBufferSize = { 64 },
                createRecorder = { Any() },
                configureRecorder = { _, _ -> events += "configure" },
                isRecorderInitialized = { false },
                releaseRecorder = { events += "recorderReleased" },
            )
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals("AudioRecord initialization failed", thrown?.message)
        assertEquals(listOf("configure", "recorderReleased", "routeRetired"), events)
        assertEquals(1, lease.retireCalls)
    }

    @Test
    fun `read exception autonomously retires exact capture route once`() {
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        var reads = 0

        runVoiceAudioCaptureLoop(
            bufferSize = 4,
            shouldContinue = { true },
            read = {
                reads += 1
                throw IllegalStateException("read failed")
            },
            onPcm16 = { events += "pcm" },
            onReadException = { events += "readException:${it.message}" },
            onNegativeRead = { events += "negative:$it" },
            onPcmCallbackException = { events += "callbackException:${it.message}" },
            onTerminated = { events += "recorderRetired"; lease.retire() },
        )
        lease.retire()

        assertEquals(1, reads)
        assertEquals(
            listOf("readException:read failed", "recorderRetired", "routeRetired"),
            events,
        )
    }

    @Test
    fun `negative read autonomously retires exact capture route once`() {
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        var reads = 0

        runVoiceAudioCaptureLoop(
            bufferSize = 4,
            shouldContinue = { true },
            read = {
                reads += 1
                -3
            },
            onPcm16 = { events += "pcm" },
            onReadException = { events += "readException" },
            onNegativeRead = { events += "negative:$it" },
            onPcmCallbackException = { events += "callbackException" },
            onTerminated = { events += "recorderRetired"; lease.retire() },
        )
        lease.retire()

        assertEquals(1, reads)
        assertEquals(listOf("negative:-3", "recorderRetired", "routeRetired"), events)
    }

    @Test
    fun `PCM callback exception autonomously retires exact capture route once with no later read`() {
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        var reads = 0

        runVoiceAudioCaptureLoop(
            bufferSize = 4,
            shouldContinue = { true },
            read = {
                reads += 1
                2
            },
            onPcm16 = { throw IllegalArgumentException("callback failed") },
            onReadException = { events += "readException" },
            onNegativeRead = { events += "negative" },
            onPcmCallbackException = { events += "callbackException:${it.message}" },
            onTerminated = { events += "recorderRetired"; lease.retire() },
        )
        lease.retire()

        assertEquals(1, reads)
        assertEquals(
            listOf("callbackException:callback failed", "recorderRetired", "routeRetired"),
            events,
        )
    }

    @Test
    fun `capture route lease is exact once across autonomous stop and release race`() {
        val retireCalls = AtomicInteger()
        val lease = FakeCaptureRouteLease { retireCalls.incrementAndGet() }
        val racers = List(3) {
            thread(name = "capture-retirement-$it") { lease.retire() }
        }

        racers.forEach { it.join(5_000) }

        assertTrue(racers.none(Thread::isAlive))
        assertEquals(1, retireCalls.get())
    }

    @Test
    fun `stop or release waits for autonomous route retirement before later mutation`() {
        val retirementEntered = CountDownLatch(1)
        val releaseRetirement = CountDownLatch(1)
        val competingRetirementAttempted = CountDownLatch(1)
        val competingRetirementCompleted = CountDownLatch(1)
        val lease = FakeCaptureRouteLease {
            retirementEntered.countDown()
            releaseRetirement.await(5, TimeUnit.SECONDS)
        }
        val autonomous = thread(name = "autonomous-capture-retirement") { lease.retire() }
        assertTrue(retirementEntered.await(5, TimeUnit.SECONDS))
        val stopOrRelease = thread(name = "explicit-capture-retirement") {
            competingRetirementAttempted.countDown()
            lease.retire()
            competingRetirementCompleted.countDown()
        }

        try {
            assertTrue(competingRetirementAttempted.await(5, TimeUnit.SECONDS))
            assertTrue(awaitThreadState(stopOrRelease, Thread.State.WAITING))
            assertFalse(competingRetirementCompleted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseRetirement.countDown()
            autonomous.join(5_000)
            stopOrRelease.join(5_000)
        }

        assertFalse(autonomous.isAlive)
        assertFalse(stopOrRelease.isAlive)
        assertTrue(competingRetirementCompleted.await(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `Telecom owner never constructs or calls direct controller`() {
        var directCreated = 0
        val selected = selectVoiceAudioRouteController(VoiceAudioRouteOwner.Telecom) {
            directCreated += 1
            RecordingRouteController()
        }

        val lease = selected.acquireCapture()
        runBlocking { lease.prepare() }
        lease.retire()
        lease.retire()
        selected.close()

        assertEquals(0, directCreated)
    }

    @Test
    fun `direct fallback delegates lifecycle once`() {
        val direct = RecordingRouteController()
        val selected = selectVoiceAudioRouteController(VoiceAudioRouteOwner.DirectFallback) { direct }

        val lease = selected.acquireCapture()
        lease.retire()
        lease.retire()
        selected.close()

        assertEquals(listOf("acquire", "retire", "close"), direct.calls)
    }

    private class RecordingRouteController : VoiceAudioRouteController {
        val calls = mutableListOf<String>()

        override fun acquireCapture(): VoiceAudioCaptureRouteLease {
            calls += "acquire"
            return FakeCaptureRouteLease { calls += "retire" }
        }

        override fun close() {
            calls += "close"
        }
    }

    private fun fakeSetupOwnership() = VoiceAudioCaptureOwnership<Any, Any>(
        startRecorder = {},
        isRecorderRecording = { true },
        stopRecorder = {},
        releaseRecorder = {},
        startTask = { true },
        cancelTask = {},
    )

    private class FakeCaptureRouteLease(
        private val onPrepare: suspend () -> Unit = {},
        private val onRetire: () -> Unit = {},
    ) : VoiceAudioCaptureRouteLease {
        private val retirement = me.rerere.rikkahub.voiceagent.RetirementBarrier()

        var configureCalls = 0
            private set

        var retireCalls = 0
            private set

        override suspend fun prepare() = onPrepare()

        override fun configureRecorder(recorder: AudioRecord) {
            configureCalls += 1
        }

        override fun retire() {
            retirement.retire {
                retireCalls += 1
                onRetire()
            }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 500L
    }

}

private class FailingDebugRegistration(
    private val delegate: VoiceAudioDebugInjector.Registration,
    private val onClose: () -> Unit,
) {
    var closeCalls = 0
        private set

    fun close() {
        closeCalls += 1
        delegate.close()
        onClose()
    }
}

private class QueuedCoroutineDispatcher : CoroutineDispatcher() {
    private val tasks = LinkedBlockingQueue<Runnable>()

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        tasks.add(block)
    }

    fun takeNext(): Runnable = checkNotNull(tasks.poll(5, TimeUnit.SECONDS)) {
        "Timed out waiting for queued coroutine dispatch"
    }
}

private fun awaitThreadState(
    thread: Thread,
    expectedState: Thread.State,
): Boolean {
    val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
    while (System.nanoTime() < deadlineNanos) {
        if (thread.state == expectedState) return true
        Thread.yield()
    }
    return thread.state == expectedState
}
