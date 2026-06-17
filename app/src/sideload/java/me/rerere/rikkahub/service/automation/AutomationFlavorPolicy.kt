package me.rerere.rikkahub.service.automation

/**
 * Sideload flavor: YOLO ("bypass all restriction") automation IS available. This is the runtime half
 * of the sideload-only boundary — the UI seam (AutomationYoloGate) gates the toggle, and this const
 * gates the lease DERIVATION so the boundary holds even against persisted/imported state that an empty
 * UI seam alone cannot stop (e.g. a backup-restored settings.json carrying yolo=true + acknowledged).
 */
internal const val AUTOMATION_YOLO_SUPPORTED: Boolean = true
