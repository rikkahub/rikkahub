package me.rerere.rikkahub.service.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.receiver.TaMessageAlarmReceiver
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.math.abs
import kotlin.uuid.Uuid

/**
 * 「Ta 的来信」闹钟调度层。
 *
 * 封装 [AlarmManager] 的调度/取消, 闹钟由系统托管, 与进程生命周期无关:
 * 即使应用被杀, 到点系统也会重建进程并投递广播给 [TaMessageAlarmReceiver]。
 *
 * 调度 API: [AlarmManager.setExactAndAllowWhileIdle] (RTC_WAKEUP, allowWhileIdle),
 * 配合 [AlarmManager.setAlarmClock] 作为无精确闹钟权限时的降级方案。
 */
object TaMessageAlarmScheduler : KoinComponent {
    private const val TAG = "TaMessageAlarmScheduler"
    private const val EXTRA_ASSISTANT_ID = "assistant_id"

    // 持久化已注册闹钟的 assistantId 集合, 用于跨进程恢复后取消已删除助手的残留闹钟 (§15.3)
    private const val PREFS_NAME = "ta_message_alarm_registry"
    private const val PREF_KEY_REGISTERED = "registered_ids"

    // 过期宽限: nextTime 早于 now - 60s 视为「已触发」, 跳过调度并清空; now-60s < nextTime <= now 允许立即触发 (联调) (§15.2)
    private const val EXPIRY_GRACE_MS = 60_000L

    // 时间漂移容忍: 同一助手 1s 内的时间变化视为未变, 跳过 cancel+reschedule (§15.7)
    private const val TIME_TOLERANCE_MS = 1_000L

    data class TaAlarmItem(
        val assistantId: Uuid,
        val enabled: Boolean,
        val nextTime: Long?
    )

    // 内存缓存: assistantId.toString() -> 已调度触发时间, 用于幂等跳过
    private val scheduled = mutableMapOf<String, Long>()

    /**
     * 将目标助手闹钟注册到系统。
     *
     * §15.1: API 31+ 需精确闹钟权限; [AlarmManager.canScheduleExactAlarms] 为 false 或抛
     * [SecurityException] 时, 降级为 [AlarmManager.setAlarmClock] (无需权限, Doze 下最可靠)。
     * §15.7: 时间漂移容忍 (1s 内视为未变, 跳过)。
     */
    fun schedule(context: Context, assistantId: Uuid, triggerAtMillis: Long) {
        val idStr = assistantId.toString()
        val existing = scheduled[idStr]
        if (existing != null && abs(existing - triggerAtMillis) < TIME_TOLERANCE_MS) {
            return // 时间未变 (容忍 1s 漂移), 跳过
        }
        val pi = buildPendingIntent(context, assistantId)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // 无精确闹钟权限 -> 降级 setAlarmClock
                setAlarmClockCompat(am, triggerAtMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "setExactAndAllowWhileIdle failed, fallback to setAlarmClock", e)
            runCatching { setAlarmClockCompat(am, triggerAtMillis, pi) }
                .onFailure { Log.e(TAG, "setAlarmClock fallback also failed", it) }
        }
        scheduled[idStr] = triggerAtMillis
    }

    /** 取消指定助手的闹钟 */
    fun cancel(context: Context, assistantId: Uuid) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildPendingIntent(context, assistantId))
        scheduled.remove(assistantId.toString())
    }

    /**
     * 幂等同步: 依据当前助手列表对齐闹钟 (设置变化 / 应用启动 / 重启均调用)。
     *
     * - §15.2: nextTime 已过期 (> 60s) 视为已触发, 跳过调度并清空 taMessageNextTime;
     *           now - 60s < nextTime <= now 允许立即触发 (便于联调)。
     * - §15.3: 被删除 / 关闭 / 清空时间的助手, 其残留闹钟 (含跨进程死亡场景) 会被 cancel。
     */
    suspend fun syncAlarms(context: Context, items: List<TaAlarmItem>) {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val previouslyRegistered =
            prefs.getStringSet(PREF_KEY_REGISTERED, emptySet()).orEmpty().toMutableSet()

        val validIds = mutableSetOf<String>()
        val expiredToClear = mutableListOf<Uuid>()

        for (item in items) {
            val idStr = item.assistantId.toString()
            val shouldRun = item.enabled && item.nextTime != null
            if (!shouldRun) continue
            val nextTime = item.nextTime!!
            if (nextTime < now - EXPIRY_GRACE_MS) {
                // 已过期 (超过宽限) -> 视为已触发: 跳过调度 + 清空
                expiredToClear.add(item.assistantId)
            } else {
                validIds.add(idStr)
                schedule(context, item.assistantId, nextTime)
            }
        }

        // 取消不再有效的闹钟 (跨进程 previouslyRegistered + 本进程 scheduled)
        val toCancel = (previouslyRegistered + scheduled.keys) - validIds
        for (idStr in toCancel) {
            cancelByRequestId(context, idStr)
        }

        // 持久化当前已注册集合 (跨进程恢复用)
        prefs.edit { putStringSet(PREF_KEY_REGISTERED, validIds) }

        // §15.2: 清空已过期的 taMessageNextTime (体现一次性语义)
        if (expiredToClear.isNotEmpty()) {
            clearExpiredNextTimes(expiredToClear)
        }
    }

    /** 按 id 字符串取消闹钟 (用于已删除的助手, 无法从 items 取得 Uuid 时) */
    private fun cancelByRequestId(context: Context, idStr: String) {
        runCatching { Uuid.parse(idStr) }.getOrNull()?.let { uid ->
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(buildPendingIntent(context, uid))
        }
        scheduled.remove(idStr)
    }

    private fun setAlarmClockCompat(am: AlarmManager, triggerAtMillis: Long, pi: PendingIntent) {
        val info = AlarmManager.AlarmClockInfo(triggerAtMillis, null)
        am.setAlarmClock(info, pi)
    }

    private fun buildPendingIntent(context: Context, assistantId: Uuid): PendingIntent {
        val intent = Intent(context, TaMessageAlarmReceiver::class.java).apply {
            action = TaMessageAlarmReceiver.ACTION_FIRE
            putExtra(EXTRA_ASSISTANT_ID, assistantId.toString())
        }
        return PendingIntent.getBroadcast(
            context,
            assistantId.hashCode(), // 每助手独立 requestCode, 保证可独立取消
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** §15.2: 清空已过期助手的 taMessageNextTime (一次性语义) */
    private suspend fun clearExpiredNextTimes(assistantIds: List<Uuid>) {
        runCatching {
            val store = get<SettingsStore>()
            store.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { a ->
                        if (a.id in assistantIds) a.copy(taMessageNextTime = null) else a
                    }
                )
            }
        }.onFailure {
            Log.e(TAG, "clearExpiredNextTimes failed", it)
        }
    }
}
