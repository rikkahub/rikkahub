package me.rerere.rikkahub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.service.alarm.TaMessageHandler
import kotlin.uuid.Uuid

/**
 * 到点唤醒接收器。
 *
 * 闹钟由系统 [android.app.AlarmManager] 托管, 到点后系统重建进程并投递本广播。
 * 本接收器仅负责: goAsync() + 自持 PARTIAL_WAKE_LOCK + 协程内调用「同一路径」
 * [TaMessageHandler.handle]。
 *
 * 本期 handle 仅做「读助手 + 渲染头像 + 发通知」, 耗时远低于 10s 广播窗口;
 * 未来接入 I/III 的长时 LLM 调用时, 改为 enqueue WorkManager 加急作业 (见计划书第十一章)。
 */
class TaMessageAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "me.rerere.rikkahub.action.TA_MESSAGE_FIRE"
        private const val TAG = "TaMessageAlarmReceiver"
        private const val EXTRA_ASSISTANT_ID = "assistant_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val idStr = intent.getStringExtra(EXTRA_ASSISTANT_ID) ?: return
        val assistantId = runCatching { Uuid.parse(idStr) }.getOrNull() ?: return

        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // 自持唤醒锁, 防止异步协程期间 CPU 休眠 (系统仅保证 onReceive 期间的唤醒)
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RikkaHub:TaMessageAlarm:$idStr")
            .apply { acquire(30_000L) } // 上限 30s 兜底

        scope.launch {
            try {
                TaMessageHandler.handle(context, assistantId) // 「同一路径」
            } catch (e: Exception) {
                Log.e(TAG, "handle failed", e)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pending.finish()
                scope.cancel()
            }
        }
    }
}
