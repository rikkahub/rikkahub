package me.rerere.rikkahub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.alarm.TaMessageAlarmScheduler
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 重启恢复层。
 *
 * 设备重启后, AlarmManager 中所有闹钟被清空。本接收器在 BOOT_COMPLETED 后从 DataStore
 * 重新读取助手列表, 调用 [TaMessageAlarmScheduler.syncAlarms] 重新注册 (与全局观察同一函数, 幂等)。
 *
 * Application.onCreate (含 startKoin) 必先于任何 receiver 的 onReceive 执行, 故 Koin 可用。
 */
class RebootAlarmReceiver : BroadcastReceiver(), KoinComponent {
    companion object {
        private const val TAG = "RebootAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val store = get<SettingsStore>()
                val settings = store.settingsFlowRaw.first()
                val items = settings.assistants.map {
                    TaMessageAlarmScheduler.TaAlarmItem(
                        assistantId = it.id,
                        enabled = it.taMessageEnabled,
                        nextTime = it.taMessageNextTime
                    )
                }
                TaMessageAlarmScheduler.syncAlarms(context, items)
            } catch (e: Exception) {
                Log.e(TAG, "restore alarms failed", e)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}
