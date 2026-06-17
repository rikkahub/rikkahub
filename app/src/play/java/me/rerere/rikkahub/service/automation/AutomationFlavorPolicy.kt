package me.rerere.rikkahub.service.automation

/**
 * Play flavor: YOLO ("bypass all restriction") automation is NOT available. This is the RUNTIME
 * enforcement of the sideload-only boundary — the empty Play UI seam (AutomationYoloGate) is not
 * sufficient on its own, because backup-restore / settings import writes an arbitrary settings.json
 * straight into Settings (yolo + acknowledged can arrive from a sideload-origin backup). The lease
 * derivation reads this const and strips YOLO on Play regardless of any persisted/imported flag, so a
 * Play build can never mint an Unbounded/host-inclusive automation capability.
 */
internal const val AUTOMATION_YOLO_SUPPORTED: Boolean = false
