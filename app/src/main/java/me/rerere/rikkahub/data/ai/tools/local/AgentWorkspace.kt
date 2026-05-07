package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import java.io.File

/**
 * Termux-style sandbox for the on-device agent.
 *
 * The agent's "home" lives in `${context.filesDir}/workspace/`. Tools that take a path
 * argument expand `~` and `~/foo` to that directory before validation, so the LLM can
 * write to a stable, OS-blessed location without knowing the absolute path:
 *
 *   write_text_file(path = "~/learnings/ERRORS.md", content = "...")
 *   list_files(path = "~/skill-cache/")
 *
 * Why this directory:
 *  - `/data/data/<own-package>/files/` has stayed open for app-private writes since
 *    Android 1; immune to scoped-storage tightening across releases.
 *  - PathSafetyGuard's OWN_APP_PREFIXES already allows it; no policy change needed.
 *  - Excluded from cloud backup via the existing `exclude domain="file" path=""` (no, we
 *    DON'T want that — workspace state should restore on device migration, not be wiped).
 *    Backup default is "include this dir"; explicit exclude only for skill secrets.
 *
 * This is the agent equivalent of Termux's `$HOME = /data/data/com.termux/files/home/`:
 * private to the agent, no shared-storage permissions required, doesn't pollute
 * `/sdcard/Documents/` with internal scratch files. Files the user should *see* (saved
 * screenshots, exported reports) still belong on `/sdcard/...`.
 */
object AgentWorkspace {
    private const val DIR_NAME = "workspace"

    @Volatile
    private var workspaceDir: File? = null

    /** Wire up at app start (RikkaHubApp.onCreate) — idempotent. */
    fun init(context: Context) {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        workspaceDir = dir
    }

    /** Absolute path of `~`. Throws if [init] hasn't been called yet. */
    fun rootPath(): String = workspaceDir?.absolutePath
        ?: error("AgentWorkspace not initialised — call AgentWorkspace.init(context) first")

    /**
     * Expand a tilde-prefixed path to the workspace dir. No-op for everything else.
     *   "~"            → "/data/data/<pkg>/files/workspace"
     *   "~/learnings/" → "/data/data/<pkg>/files/workspace/learnings/"
     *   "/sdcard/x"    → "/sdcard/x"   (unchanged)
     */
    fun expand(raw: String): String {
        if (raw.isEmpty()) return raw
        if (raw == "~") return rootPath()
        if (raw.startsWith("~/")) return rootPath() + raw.removePrefix("~")
        return raw
    }
}
