package me.rerere.rikkahub.service.generation

import android.util.Log
import me.rerere.rikkahub.service.GenerationActivityTracker
import me.rerere.rikkahub.service.shouldRenewWakeLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "GenerationFGCoord"

/**
 * 把活跃生成 -> 前台服务的生命周期状态机从 ChatService 抽出，使其只依赖纯 [GenerationForegroundController]
 * 接口，从而可在 JVM 单测验证边沿/节流不变量。状态由本协调器独占持有。
 */
class GenerationForegroundCoordinator(
    private val controller: GenerationForegroundController,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    // 活跃生成引用计数：跨会话计数，只有 0->1 边启动前台服务、1->0 边停止。
    private val generationTracker = GenerationActivityTracker()

    // 前台服务是否被认为已成功启动。与 generationTracker（纯 job 生命周期计数）解耦：计数的
    // acquire/release 严格由 job.invokeOnCompletion 配对，不能因 FGS 启动失败而回滚（否则会与
    // 完成时的 release 形成对单次 acquire 的双重 release）。是否真正向服务发 ACTION_STOP 由这个
    // 独立标志决定，避免在从未启动的服务上发停止意图。
    private val foregroundServiceRunning = AtomicBoolean(false)

    // 上次向前台服务发送 WakeLock 续期的时刻（跨会话共享同一个锁，故单一时间戳即可）。
    private val lastWakeLockRenewAt = AtomicLong(0L)

    // 由 ConversationSession.setJob 在设置非空 job 时触发。仅 0->1 边启动前台服务（其中获取
    // wake/wifi 锁）。锁不在 ChatService 持有，避免泄漏面。
    //
    // startForegroundService 在 Android 12+ 的纯后台进程（如 Web 服务器在息屏时收到发消息请求）
    // 会抛 ForegroundServiceStartNotAllowedException。setJob 在协程 try/catch 之外调用，未捕获时
    // 异常会逃逸并崩溃进程——恰好是本 PR 要修的后台发消息场景。runCatching 兜住启动失败：生成本身
    // 仍在 appScope 继续，只是失去前台保活/锁（降级而非崩溃）。启动成功才置位标志，使后续停止只对
    // 真正启动过的服务发 ACTION_STOP。
    // @Synchronized so the running-flag check, controller.start(), and the flag update are atomic
    // across concurrent starts (multiple conversations share this process-global coordinator). Without
    // it, a non-STARTED retry could observe running==false, enter start() concurrently with the
    // STARTED edge's start(), and its onFailure set(false) could clobber the other's success.
    @Synchronized
    fun onGenerationStart() {
        val transition = generationTracker.acquire()
        // Attempt the start on the 0->1 edge, OR whenever the service is not yet running. A transient
        // start failure (ForegroundServiceStartNotAllowedException) used to latch: the active count
        // stays >0 so every later generation returned here at the non-STARTED edge and never retried,
        // leaving the whole active window unprotected. Suppress a retry only when the service IS
        // already running; otherwise re-attempt so a later generation can recover the keepalive.
        if (transition != GenerationActivityTracker.Transition.STARTED && foregroundServiceRunning.get()) return
        runCatching { controller.start() }
            .onSuccess { foregroundServiceRunning.set(true) }
            .onFailure {
                foregroundServiceRunning.set(false)
                Log.w(TAG, "onGenerationStart: foreground service start failed, degraded", it)
            }
    }

    // 由 job.invokeOnCompletion 触发（成功/失败/取消三条终止路径都会经过）。仅 1->0 边停止前台
    // 服务，且仅当启动确实成功过时才发 ACTION_STOP（startService 在后台对未启动的服务也可能抛
    // IllegalStateException）。服务在 ACTION_STOP / onDestroy 释放锁。
    @Synchronized
    fun onGenerationStop() {
        if (generationTracker.release() != GenerationActivityTracker.Transition.STOPPED) return
        if (foregroundServiceRunning.compareAndSet(true, false)) {
            runCatching { controller.stop() }
                .onFailure { Log.w(TAG, "onGenerationStop: foreground service stop failed", it) }
        }
    }

    // 流式进展时按时间节流地续期前台服务 WakeLock。节流判定抽成纯函数 [shouldRenewWakeLock] 以便
    // JVM 单测；仅当服务确实在运行且距上次续期超过间隔时才发 IPC。
    fun onStreamingProgress() {
        if (!foregroundServiceRunning.get()) return
        val now = clock()
        val last = lastWakeLockRenewAt.get()
        if (!shouldRenewWakeLock(last, now)) return
        if (lastWakeLockRenewAt.compareAndSet(last, now)) {
            runCatching { controller.renew() }
        }
    }
}
