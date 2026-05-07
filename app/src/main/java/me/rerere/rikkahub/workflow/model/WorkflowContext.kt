package me.rerere.rikkahub.workflow.model

/**
 * Snapshot of device state at condition-evaluation time. Lazy fields (location, foreground app,
 * recent notifications) are filled only when at least one condition needs them — see
 * [me.rerere.rikkahub.workflow.condition.ContextProvider].
 *
 * Time zone for [time_between] / day-of-week / sunrise-sunset condition evaluation is device-local.
 * Daily-cap rollover also keys off the device-local "yyyy-MM-dd" date.
 */
data class WorkflowContext(
    val nowMs: Long,
    val batteryLevel: Int?,        // 0..100 or null if unknown
    val isCharging: Boolean,
    val wifiSsid: String?,
    val foregroundPackage: String?,
    val screenOn: Boolean,
    val latitude: Double?,
    val longitude: Double?,
)
