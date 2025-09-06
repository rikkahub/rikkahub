package me.rerere.tts.controller

import android.content.Context
import android.util.Log
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "TtsController"

/**
 * TTS 控制器
 * - 负责文本分片、预合成、排队播放与状态上报
 * - 对外暴露简单的控制接口与 StateFlow 用于 UI 订阅
 */
class TtsController(
    private val context: Context,
    private val ttsManager: TTSManager
) {
    // 协程与播放
    private val scope = CoroutineScope(Dispatchers.Main)
    private val player = ExoPlayer.Builder(context).build()

    // Provider & 作业
    private var currentProvider: TTSProviderSetting? = null
    private var workerJob: Job? = null
    private var synthesizerJob: Job? = null
    private var isPaused = false

    // 队列与缓存
    private val chunkQueue: java.util.concurrent.ConcurrentLinkedQueue<String> =
        java.util.concurrent.ConcurrentLinkedQueue()
    private val currentChunks: MutableList<String> = mutableListOf()
    private val preSynthesisCache = java.util.concurrent.ConcurrentHashMap<Int, TTSResponse>()
    private val synthesisPromises = java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<TTSResponse>>()
    private var nextChunkToSynthesize = 0

    // 行为参数
    private val maxChunkLength = 160
    private val chunkDelayMs = 120L
    private val prefetchCount = 4

    // 状态流
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentChunk = MutableStateFlow(0)
    val currentChunk: StateFlow<Int> = _currentChunk.asStateFlow()

    private val _totalChunks = MutableStateFlow(0)
    val totalChunks: StateFlow<Int> = _totalChunks.asStateFlow()

    /** 选择/取消选择 Provider */
    fun setProvider(provider: TTSProviderSetting?) {
        currentProvider = provider
        _isAvailable.update { provider != null }
        if (provider == null) stop()
    }

    /**
     * 朗读文本
     * - flush=true: 清空当前进度并重新开始
     * - flush=false: 继续队列，追加朗读
     */
    fun speak(text: String, flush: Boolean = true) {
        if (text.isBlank()) return
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        val chunks = chunkText(text)

        if (flush) {
            resetForNewSession(chunks)
        } else {
            currentChunks.addAll(chunks)
            chunkQueue.addAll(chunks)
            _totalChunks.update { chunkQueue.size }
        }

        if (workerJob?.isActive != true) startWorker()
        startSynthesizer(nextChunkToSynthesize)
    }

    /** 暂停播放（保留进度） */
    fun pause() {
        isPaused = true
        player.pause()
    }

    /** 恢复播放 */
    fun resume() {
        isPaused = false
        player.play()
    }

    /** 快进当前音频 */
    fun fastForward(ms: Long = 5_000) {
        player.seekTo(player.currentPosition + ms)
    }

    /** 设置播放速度 */
    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    /** 跳过下一段（不打断当前正在播放） */
    fun skipNext() {
        if (chunkQueue.isNotEmpty()) {
            chunkQueue.poll()
            _totalChunks.update { chunkQueue.size }
        }
    }

    /** 停止并清空状态 */
    fun stop() {
        workerJob?.cancel()
        synthesizerJob?.cancel()
        player.stop()
        player.clearMediaItems()
        isPaused = false
        chunkQueue.clear()
        preSynthesisCache.clear()
        synthesisPromises.values.forEach { it.cancel(CancellationException("Stopped")) }
        synthesisPromises.clear()
        nextChunkToSynthesize = 0
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
    }

    /** 释放资源 */
    fun dispose() {
        stop()
        player.release()
    }

    // region 内部：播放调度
    private fun startWorker() {
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        workerJob = scope.launch {
            _isSpeaking.update { true }
            var processedIndex = _currentChunk.value.takeIf { it > 0 }?.minus(1) ?: 0

            try {
                while (isActive && (chunkQueue.isNotEmpty())) {
                    if (isPaused) {
                        delay(80)
                        continue
                    }

                    val text = chunkQueue.poll() ?: break

                    // 更新状态：第几个 chunk（1-based）与剩余总数
                    _currentChunk.update { processedIndex + 1 }
                    _totalChunks.update { chunkQueue.size + 1 }

                    // 获取或生成音频
                    val response = tryGetOrSynthesize(provider, processedIndex, text)
                        ?: run {
                            processedIndex++
                            continue
                        }

                    // 播放
                    try {
                        playAudio(response.audioData, response.format, response.sampleRate)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Playback error", e)
                        _error.update { e.message ?: "Audio playback error" }
                    }

                    if (chunkQueue.isNotEmpty()) delay(chunkDelayMs)

                    // 触发更多预合成
                    triggerMoreSynthesis(processedIndex + 1)
                    processedIndex++
                }
            } finally {
                _isSpeaking.update { false }
            }
        }
    }

    private suspend fun tryGetOrSynthesize(
        provider: TTSProviderSetting,
        index: Int,
        text: String
    ): TTSResponse? {
        return try {
            // 1) 先查缓存（可能来自预合成）
            preSynthesisCache.remove(index) ?: run {
                // 2) 若存在共享 Promise，则等待其完成
                synthesisPromises[index]?.let { existing ->
                    withTimeoutOrNull(5_000) { existing.await() }
                } ?: run {
                    // 3) 无人在合成，则尝试成为首个合成者
                    val myPromise = CompletableDeferred<TTSResponse>()
                    val prev = synthesisPromises.putIfAbsent(index, myPromise)
                    if (prev != null) {
                        // 有人刚刚占坑了，等待其结果
                        withTimeoutOrNull(5_000) { prev.await() }
                    } else {
                        // 我来合成
                        try {
                            val resp = withContext(Dispatchers.IO) {
                                collectToResponse(ttsManager.generateSpeech(provider, TTSRequest(text = text)))
                            }
                            // 播放路径直接返回，但也让他人可见
                            preSynthesisCache.putIfAbsent(index, resp)
                            myPromise.complete(resp)
                            resp
                        } catch (e: Exception) {
                            myPromise.completeExceptionally(e)
                            throw e
                        } finally {
                            synthesisPromises.remove(index, myPromise)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Synthesis error", e)
            _error.update { e.message ?: "TTS synthesis error" }
            null
        }
    }
    // endregion

    // region 内部：预合成调度
    private fun startSynthesizer(startIndex: Int) {
        if (synthesizerJob?.isActive == true) return
        val provider = currentProvider ?: return
        synthesizerJob = scope.launch(Dispatchers.IO) {
            try {
                val begin = kotlin.math.max(startIndex, nextChunkToSynthesize)
                val endExclusive = kotlin.math.min(begin + prefetchCount, currentChunks.size)
                for (i in begin until endExclusive) {
                    if (!isActive) break
                    if (preSynthesisCache.containsKey(i)) continue
                    // 保证同一 index 只有一个生产者
                    val myPromise = CompletableDeferred<TTSResponse>()
                    val prev = synthesisPromises.putIfAbsent(i, myPromise)
                    if (prev != null) continue
                    val text = currentChunks.getOrNull(i) ?: run {
                        synthesisPromises.remove(i, myPromise)
                        continue
                    }
                    try {
                        val resp = collectToResponse(ttsManager.generateSpeech(provider, TTSRequest(text = text)))
                        preSynthesisCache[i] = resp
                        nextChunkToSynthesize = i + 1
                        myPromise.complete(resp)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Pre-synthesis error at $i", e)
                        _error.update { e.message ?: "TTS pre-synthesis error" }
                        myPromise.completeExceptionally(e)
                        break
                    } finally {
                        synthesisPromises.remove(i, myPromise)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Synthesizer job error", e)
            }
        }
    }

    private fun triggerMoreSynthesis(currentIndex: Int) {
        if (synthesizerJob?.isActive == true) return
        startSynthesizer(currentIndex)
    }
    // endregion

    // region 内部：会话与切分
    private fun resetForNewSession(newChunks: List<String>) {
        stop()
        currentChunks.clear()
        currentChunks.addAll(newChunks)
        chunkQueue.addAll(newChunks)
        _currentChunk.update { 0 }
        _totalChunks.update { chunkQueue.size }
        _error.update { null }
        preSynthesisCache.clear()
        synthesisPromises.values.forEach { it.cancel(CancellationException("Reset")) }
        synthesisPromises.clear()
        nextChunkToSynthesize = 0
    }

    private fun chunkText(text: String): List<String> {
        val paragraphs = text.split("\n\n")
        val punctuationRegex = "(?<=[。！？，、：;.!?:,\n])".toRegex()
        return paragraphs.flatMap { paragraph ->
            if (paragraph.isBlank()) emptyList() else {
                paragraph
                    .split(punctuationRegex)
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .fold(mutableListOf<StringBuilder>()) { acc, seg ->
                        if (acc.isEmpty() || acc.last().length + seg.length > maxChunkLength) {
                            acc.add(StringBuilder(seg))
                        } else {
                            acc.last().append(seg)
                        }
                        acc
                    }
                    .map { it.toString() }
            }
        }
    }
    // endregion

    private suspend fun collectToResponse(flow: Flow<AudioChunk>): TTSResponse {
        var format: AudioFormat? = null
        var sampleRate: Int? = null
        val output = ByteArrayOutputStream()
        flow.collect { chunk ->
            if (format == null) format = chunk.format
            if (sampleRate == null) sampleRate = chunk.sampleRate
            output.write(chunk.data)
        }
        return TTSResponse(
            audioData = output.toByteArray(),
            format = format ?: AudioFormat.MP3,
            sampleRate = sampleRate
        )
    }

    @OptIn(UnstableApi::class)
    private suspend fun playAudio(
        data: ByteArray,
        format: AudioFormat,
        sampleRate: Int? = null
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val bytes = if (format == AudioFormat.PCM) {
            pcmToWav(data, sampleRate ?: 24000)
        } else data
        val dataSourceFactory = DataSource.Factory { ByteArrayDataSource(bytes) }
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY))
        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    player.removeListener(this)
                    if (cont.isActive) cont.resume(Unit)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                player.removeListener(this)
                if (cont.isActive) cont.resumeWithException(error)
            }
        }
        player.addListener(listener)
        cont.invokeOnCancellation {
            player.removeListener(listener)
            player.stop()
        }
    }

    private fun pcmToWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val out = ByteArrayOutputStream()
        with(out) {
            write("RIFF".toByteArray())
            write(intToBytes(36 + pcm.size))
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            write(intToBytes(16))
            write(shortToBytes(1))
            write(shortToBytes(channels.toShort()))
            write(intToBytes(sampleRate))
            write(intToBytes(byteRate))
            write(shortToBytes((channels * bitsPerSample / 8).toShort()))
            write(shortToBytes(bitsPerSample.toShort()))
            write("data".toByteArray())
            write(intToBytes(pcm.size))
            write(pcm)
        }
        return out.toByteArray()
    }

    private fun intToBytes(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToBytes(value: Short) = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )
}
