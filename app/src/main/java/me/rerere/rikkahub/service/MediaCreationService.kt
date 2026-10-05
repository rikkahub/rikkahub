package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.http.await
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import me.rerere.mediagen.provider.providers.MediaGenerationApiException
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.MEDIA_CREATION_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.MediaCreationFiles
import me.rerere.rikkahub.data.files.RemoteFileStore
import me.rerere.rikkahub.data.model.MediaCreationAsset
import me.rerere.rikkahub.data.model.MediaCreationOutput
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaCreationStatus
import me.rerere.rikkahub.data.model.buildMediaGenerationRequest
import me.rerere.rikkahub.data.model.deriveMediaCreationTitle
import me.rerere.rikkahub.data.model.supportedBy
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.utils.sendNotification
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid

private const val TAG = "MediaCreationService"

/**
 * 一次提交的内容。[assets] 可以指向任何已有的本地文件（草稿里的素材、别的记录的输入或输出），
 * 提交时会复制一份到新记录自己的目录下。
 *
 * [nodeId] 非空时新记录成为时间线上那一项的新版本，否则另起一项。
 */
data class MediaCreationSubmission(
    val sessionId: Uuid,
    val nodeId: Uuid? = null,
    val provider: MediaGenerationProviderSetting,
    val model: MediaGenerationModel,
    val prompt: String,
    val params: MediaCreationParams,
    val assets: List<MediaCreationAsset>,
)

/**
 * 媒体创作的生成任务。每条记录一个协程，互不阻塞；任务挂在应用级作用域上，离开页面后继续运行，
 * 并由 [MediaCreationForegroundService] 把进程留在前台。
 *
 * 一条记录的流程：上传素材（只有要求公网地址的接口需要）→ 提交 → 轮询到终态 → 把结果下载到本地。
 * 进度全部写进数据库，界面只需要观察记录。
 */
class MediaCreationService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val repository: MediaCreationRepository,
    private val manager: MediaGenerationManager,
    private val remoteFileStore: RemoteFileStore,
    private val okHttpClient: OkHttpClient,
) {
    private val jobs = ConcurrentHashMap<Uuid, Job>()

    /**
     * 新建一条记录并开始生成。提交过程放在应用级作用域里，调用方中途被取消也不会留下半条记录。
     */
    suspend fun submit(submission: MediaCreationSubmission): MediaCreationRecord = inAppScope {
        val capabilities = submission.provider.capabilities(submission.model.kind)
            ?: error(context.getString(R.string.media_creation_error_kind_unsupported, submission.provider.name))
        val recordId = Uuid.random()
        val assets = submission.assets.supportedBy(capabilities)
        val record = MediaCreationRecord(
            id = recordId,
            sessionId = submission.sessionId,
            nodeId = submission.nodeId ?: Uuid.random(),
            providerId = submission.provider.id,
            providerName = submission.provider.name,
            modelId = submission.model.modelId,
            kind = submission.model.kind,
            prompt = submission.prompt.trim(),
            params = submission.params.supportedBy(capabilities),
            inputs = copyInputs(submission.sessionId, recordId, assets),
        )
        // 先登记任务再写入记录：恢复逻辑把「进行中但没有任务」的记录当作被中断，不能让它看到这个空档
        val job = register(record.id, record.sessionId)
            ?: error(context.getString(R.string.media_creation_error_task_exists))
        try {
            repository.insertRecord(record)
            repository.updateSession(record.sessionId) { session ->
                session.copy(
                    title = session.title.ifBlank { deriveMediaCreationTitle(record.prompt) },
                    updateAt = Instant.now(),
                )
            }
        } catch (e: Throwable) {
            jobs.remove(record.id, job)
            job.cancel()
            withContext(Dispatchers.IO) { repository.recordDir(record.sessionId, record.id).deleteRecursively() }
            throw e
        }
        job.start()
        record
    }

    /**
     * 重新执行失败或已取消的记录。服务端还有任务时接着查询，否则重新提交。
     */
    suspend fun retry(recordId: Uuid) = inAppScope {
        val sessionId = repository.getRecord(recordId)?.sessionId ?: return@inAppScope
        // 和 submit 一样先登记任务，再把记录改成进行中
        val job = register(recordId, sessionId) ?: return@inAppScope
        var started = false
        try {
            val record = repository.updateRecord(recordId) { record ->
                if (record.status == MediaCreationStatus.SUCCEEDED) {
                    record
                } else {
                    record.copy(
                        status = if (record.taskId != null) MediaCreationStatus.RUNNING else MediaCreationStatus.PREPARING,
                        error = null,
                        outputs = emptyList(),
                    )
                }
            }
            if (record != null && record.status != MediaCreationStatus.SUCCEEDED) {
                job.start()
                started = true
            }
        } finally {
            if (!started) {
                jobs.remove(recordId, job)
                job.cancel()
            }
        }
    }

    /**
     * 停止等待一条记录。已经提交给服务端的任务无法撤回，[MediaCreationRecord.taskId] 会保留，之后可以重试接着查询。
     */
    suspend fun cancel(recordId: Uuid) = inAppScope {
        jobs[recordId]?.cancelAndJoin()
        repository.updateRecord(recordId) { record ->
            if (record.status.isActive) record.copy(status = MediaCreationStatus.CANCELLED) else record
        }
    }

    suspend fun deleteRecord(recordId: Uuid) = inAppScope {
        jobs[recordId]?.cancelAndJoin()
        repository.getRecord(recordId)?.let { repository.deleteRecord(it) }
    }

    suspend fun deleteSession(sessionId: Uuid) = inAppScope {
        repository.getRecordsOfSession(sessionId).forEach { jobs[it.id]?.cancelAndJoin() }
        repository.deleteSession(sessionId)
    }

    /**
     * 进程启动后接管上次没跑完的记录：有任务 ID 的接着查询，其余的在提交前就被中断了，标记为失败。
     */
    fun resumePending() {
        appScope.launch {
            runCatching {
                repository.getActiveRecords().forEach { record ->
                    if (jobs.containsKey(record.id)) return@forEach
                    if (record.taskId != null) {
                        register(record.id, record.sessionId)?.start()
                    } else {
                        repository.updateRecord(record.id) {
                            it.copy(
                                status = MediaCreationStatus.FAILED,
                                error = context.getString(R.string.media_creation_error_interrupted),
                            )
                        }
                    }
                }
            }.onFailure {
                Log.e(TAG, "resumePending failed", it)
            }
        }
    }

    // 记录已有任务在跑时返回 null
    private fun register(recordId: Uuid, sessionId: Uuid): Job? {
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            val foreground = MediaCreationForegroundService.acquire(context, recordId, sessionId)
            try {
                run(recordId)
            } finally {
                jobs.remove(recordId, coroutineContext.job)
                if (foreground) MediaCreationForegroundService.release(context, recordId)
            }
        }
        if (jobs.putIfAbsent(recordId, job) != null) {
            job.cancel()
            return null
        }
        return job
    }

    private suspend fun run(recordId: Uuid) {
        try {
            generate(recordId)
        } catch (e: Throwable) {
            // 被取消时记录的状态由取消方负责
            currentCoroutineContext().ensureActive()
            Log.e(TAG, "Generation $recordId failed", e)
            repository.updateRecord(recordId) {
                it.copy(status = MediaCreationStatus.FAILED, error = e.toDisplayMessage())
            }
        }
        repository.getRecord(recordId)?.let(::notifyFinished)
    }

    private suspend fun generate(recordId: Uuid) {
        val record = repository.getRecord(recordId) ?: return
        val setting = settingsStore.settingsFlow.first { !it.init }
            .mediaGenerationProviders.find { it.id == record.providerId }
            ?: error(context.getString(R.string.media_creation_error_provider_deleted, record.providerName))
        val model = MediaGenerationModel(modelId = record.modelId, kind = record.kind)

        val task = if (record.taskId != null) {
            awaitTask(setting, model, record.id, record.taskId, since = record.updateAt)
        } else {
            val created = create(setting, model, record)
            if (created.isTerminal) {
                created
            } else {
                // 任务已经在服务端跑起来了：就算此刻被取消也要把任务 ID 记下来，否则再也找不回它
                withContext(NonCancellable) {
                    repository.updateRecord(record.id) {
                        it.copy(taskId = created.id, status = created.status.toCreationStatus(it.status))
                    }
                }
                delay(POLL_INTERVAL)
                awaitTask(setting, model, record.id, created.id, since = Instant.now())
            }
        }

        if (task.status != MediaGenerationStatus.SUCCEEDED) {
            // 服务端的任务已经结束，没有再查询的意义
            repository.updateRecord(record.id) {
                it.copy(status = MediaCreationStatus.FAILED, taskId = null, error = task.failureMessage(context))
            }
            return
        }

        repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.DOWNLOADING) }
        val outputs = downloadOutputs(setting, record, task.outputs)
        repository.updateRecord(record.id) {
            if (outputs.isEmpty()) {
                it.copy(
                    status = MediaCreationStatus.FAILED,
                    taskId = null,
                    error = context.getString(R.string.media_creation_error_no_output),
                )
            } else {
                it.copy(status = MediaCreationStatus.SUCCEEDED, outputs = outputs, error = null)
            }
        }
    }

    private suspend fun create(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        record: MediaCreationRecord,
    ): MediaGenerationTask {
        val capabilities = setting.capabilities(record.kind)
            ?: error(context.getString(R.string.media_creation_error_kind_unsupported, setting.name))
        val inputUrls = if (capabilities.requiresRemoteInputs && record.inputs.isNotEmpty()) {
            check(remoteFileStore.isConfigured) { context.getString(R.string.media_creation_error_upload_required) }
            repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.PREPARING) }
            record.inputs.map { remoteFileStore.upload(repository.resolve(it.path)).getOrThrow().url }
        } else {
            record.inputs.map { repository.resolve(it.path).absolutePath }
        }
        repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.RUNNING) }
        val request = buildMediaGenerationRequest(record.prompt, record.params, record.inputs, inputUrls, capabilities)
        return manager.create(setting, model, request).getOrElse {
            currentCoroutineContext().ensureActive()
            throw it
        }
    }

    /**
     * 轮询到终态。网络抖动和服务端的临时错误不代表任务失败，退避后继续查询；
     * 接口明确拒绝（4xx）、连续失败太多次或等待超时才放弃，此时记录仍保留任务 ID，可以重试。
     *
     * 时限从 [since] 算起。接着查询已有任务时传记录最后一次变化的时间，而不是现在：
     * 否则进程每重启一次时限就续一次，一个始终不结束的任务会被永远查下去。
     */
    private suspend fun awaitTask(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        recordId: Uuid,
        taskId: String,
        since: Instant,
    ): MediaGenerationTask {
        val deadline = since + MAX_WAIT.toJavaDuration()
        var failures = 0
        while (true) {
            val result = manager.query(setting, model, taskId)
            currentCoroutineContext().ensureActive()
            result.onSuccess { task ->
                failures = 0
                if (task.isTerminal) return task
                repository.updateRecord(recordId) { it.copy(status = task.status.toCreationStatus(it.status)) }
            }.onFailure { error ->
                failures++
                Log.w(TAG, "Query $taskId failed ($failures)", error)
                if (!error.isRetryable() || failures >= MAX_QUERY_FAILURES) throw error
            }
            check(Instant.now() < deadline) { context.getString(R.string.media_creation_error_timeout) }
            delay(pollDelay(failures))
        }
    }

    private suspend fun copyInputs(
        sessionId: Uuid,
        recordId: Uuid,
        assets: List<MediaCreationAsset>,
    ): List<MediaCreationAsset> = withContext(Dispatchers.IO) {
        if (assets.isEmpty()) return@withContext emptyList()
        val dir = repository.recordDir(sessionId, recordId)
        try {
            dir.mkdirs()
            assets.mapIndexed { index, asset ->
                val source = repository.resolve(asset.path)
                check(source.isFile) { context.getString(R.string.media_creation_error_asset_missing) }
                val target = File(dir, "in_$index.${source.extension.ifEmpty { "bin" }}")
                source.copyTo(target, overwrite = true)
                asset.copy(path = repository.relativePath(target))
            }
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    private suspend fun downloadOutputs(
        setting: MediaGenerationProviderSetting,
        record: MediaCreationRecord,
        outputs: List<MediaGenerationOutput>,
    ): List<MediaCreationOutput> = withContext(Dispatchers.IO) {
        val dir = repository.recordDir(record.sessionId, record.id).apply { mkdirs() }
        // 清掉上一次没下完的残留
        dir.listFiles { file -> file.name.startsWith(OUTPUT_PREFIX) }?.forEach { it.delete() }

        outputs.mapIndexedNotNull { index, output ->
            val isVideo = output.mimeType.startsWith("video/")
            val file = File(dir, "$OUTPUT_PREFIX$index.${output.fileExtension()}")
            val data = output.data
            val url = output.url
            when {
                data != null -> file.writeBytes(data)
                url != null -> download(setting, url, file)
                else -> return@mapIndexedNotNull null
            }

            if (isVideo) {
                val poster = File(dir, "$OUTPUT_PREFIX${index}_poster.jpg")
                    .takeIf { MediaCreationFiles.extractFrame(file, it, last = false) }
                // 尾帧只是「续写」时的捷径，下载不到就等用的时候再从视频里抽
                val lastFrame = output.lastFrameUrl?.let { lastFrameUrl ->
                    File(dir, "$OUTPUT_PREFIX${index}_last.${lastFrameUrl.urlExtension() ?: "jpg"}")
                        .takeIf { runCatching { download(setting, lastFrameUrl, it, attempts = 1) }.isSuccess }
                }
                val info = MediaCreationFiles.videoInfo(file)
                MediaCreationOutput(
                    path = repository.relativePath(file),
                    mimeType = output.mimeType,
                    width = info?.width,
                    height = info?.height,
                    durationSeconds = output.durationSeconds ?: info?.durationSeconds,
                    posterPath = poster?.let(repository::relativePath),
                    lastFramePath = lastFrame?.let(repository::relativePath),
                )
            } else {
                val info = MediaCreationFiles.imageInfo(file)
                MediaCreationOutput(
                    path = repository.relativePath(file),
                    mimeType = output.mimeType,
                    width = info?.width,
                    height = info?.height,
                )
            }
        }
    }

    private suspend fun download(
        setting: MediaGenerationProviderSetting,
        url: String,
        target: File,
        attempts: Int = DOWNLOAD_ATTEMPTS,
    ) {
        // 个别厂商的结果地址没有签名，要带着密钥去取
        val request = Request.Builder().url(url).headers(setting.downloadHeaders(url).toHeaders()).build()
        var attempt = 0
        while (true) {
            attempt++
            try {
                downloadOnce(request, target)
                return
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (attempt >= attempts) throw e
                Log.w(TAG, "Download failed ($attempt/$attempts)", e)
                delay(POLL_INTERVAL)
            }
        }
    }

    private suspend fun downloadOnce(request: Request, target: File) {
        val partial = File(target.parentFile, target.name + MediaCreationFiles.PARTIAL_SUFFIX)
        val call = okHttpClient.newCall(request)
        try {
            coroutineScope {
                // 断流时线程卡在 read() 里，走不到 ensureActive()：取消时关掉连接，让 read() 立刻抛出来
                val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                call.await().use { response ->
                    check(response.isSuccessful) {
                        context.getString(R.string.media_creation_error_download_failed, response.code)
                    }
                    response.body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                canceller.cancel()
            }
            check(partial.renameTo(target)) { context.getString(R.string.media_creation_error_save_failed) }
        } finally {
            partial.delete()
        }
    }

    private fun notifyFinished(record: MediaCreationRecord) {
        if (record.status != MediaCreationStatus.SUCCEEDED && record.status != MediaCreationStatus.FAILED) return
        // 应用在前台时时间线上已经能看到结果
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val isVideo = record.kind == MediaKind.VIDEO
        val succeeded = record.status == MediaCreationStatus.SUCCEEDED
        val titleRes = when {
            isVideo && succeeded -> R.string.media_creation_notification_video_succeeded
            isVideo -> R.string.media_creation_notification_video_failed
            succeeded -> R.string.media_creation_notification_image_succeeded
            else -> R.string.media_creation_notification_image_failed
        }
        context.sendNotification(
            channelId = MEDIA_CREATION_NOTIFICATION_CHANNEL_ID,
            notificationId = record.id.hashCode(),
        ) {
            title = context.getString(titleRes)
            content = record.error?.takeIf { record.status == MediaCreationStatus.FAILED }
                ?: record.prompt.ifBlank { record.modelId }
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_STATUS
            contentIntent = MediaCreationForegroundService.sessionPendingIntent(context, record.sessionId.toString())
        }
    }

    private suspend fun <T> inAppScope(block: suspend () -> T): T = appScope.async { block() }.await()

    companion object {
        private const val OUTPUT_PREFIX = "out_"
        private const val DOWNLOAD_ATTEMPTS = 3
        private const val MAX_QUERY_FAILURES = 20
        private val POLL_INTERVAL = 5.seconds
        private val MAX_POLL_INTERVAL = 60.seconds
        private val MAX_WAIT = 24.hours

        // 查询成功时按固定间隔轮询，连续失败时逐步放慢
        private fun pollDelay(failures: Int): Duration =
            (POLL_INTERVAL * (1 shl failures.coerceIn(0, 4))).coerceAtMost(MAX_POLL_INTERVAL)
    }
}

private fun MediaGenerationStatus.toCreationStatus(current: MediaCreationStatus): MediaCreationStatus = when (this) {
    MediaGenerationStatus.QUEUED -> MediaCreationStatus.QUEUED
    MediaGenerationStatus.RUNNING -> MediaCreationStatus.RUNNING
    // 认不出的状态当作还在进行，保持界面上原来的显示
    else -> current.takeIf { it == MediaCreationStatus.QUEUED } ?: MediaCreationStatus.RUNNING
}

private fun MediaGenerationTask.failureMessage(context: Context): String {
    val reason = error?.let { error -> listOfNotNull(error.code, error.message).joinToString("：") }
    return reason?.takeIf(String::isNotBlank) ?: when (status) {
        MediaGenerationStatus.CANCELLED -> context.getString(R.string.media_creation_error_cancelled_remotely)
        MediaGenerationStatus.EXPIRED -> context.getString(R.string.media_creation_error_expired)
        else -> context.getString(R.string.media_creation_status_failed)
    }
}

// 4xx 是接口明确拒绝（密钥错误、任务不存在等），重试没有意义；限流和超时除外
private fun Throwable.isRetryable(): Boolean =
    this !is MediaGenerationApiException || statusCode !in 400..499 || statusCode == 408 || statusCode == 429

private fun Throwable.toDisplayMessage(): String = when (this) {
    is MediaGenerationApiException -> listOfNotNull("HTTP $statusCode", code, message).joinToString(" · ")
    else -> message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
}

private fun MediaGenerationOutput.fileExtension(): String = when (mimeType.lowercase()) {
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/svg+xml" -> "svg"
    "video/mp4" -> "mp4"
    "video/webm" -> "webm"
    "video/quicktime" -> "mov"
    else -> url?.urlExtension() ?: if (mimeType.startsWith("video/")) "mp4" else "png"
}

// 结果地址通常带签名参数，扩展名在 path 末尾
private fun String.urlExtension(): String? =
    substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
        .lowercase()
        .takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
