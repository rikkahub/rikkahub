package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.utils.launchEmitting
import java.io.File

data class SkillFile(
    val file: File,
    val relativePath: String,
)

sealed class SkillFileNode {
    data class FileNode(val skillFile: SkillFile) : SkillFileNode()
    data class DirNode(
        val name: String,
        val relativePath: String,
        val children: List<SkillFileNode>,
    ) : SkillFileNode()
}

/** Which dialog category started a save — selects which dialog-token the collector compares against. */
enum class SkillSaveOrigin { EDIT, ADD }

/**
 * Identity of a single save INVOCATION, captured at the call site (the dialog's confirm handler) and
 * carried on the completion event. [origin] selects the dialog category; [token] is an opaque per-confirm
 * id. The collector dismisses a dialog only when BOTH match its currently-open instance, so a late
 * completion whose dialog was already dismissed-and-reopened (same category, new token) never closes the
 * fresh dialog. Without the token, routing collapsed to category alone: an in-flight edit-save of file A
 * could dismiss the edit dialog the user had since reopened for file B.
 */
data class SkillSaveTarget(val origin: SkillSaveOrigin, val token: Long)

/**
 * Mints monotonically increasing, never-reused save-invocation tokens. The counter MUST live on the
 * ViewModel, not the page: the save runs on [viewModelScope] and its completion is delivered across a
 * config change (the VM survives, the Channel retains the event). A page-held counter resets to 0 on
 * recreation while [rememberSaveable] dialog state survives — so the restored dialog's recorded token
 * could no longer match the echoed completion (dialog stuck open), and a re-confirm would re-mint a
 * value an in-flight save already carries (wrong-dialog dismissal). Co-locating the counter with the
 * job that carries the token keeps tokens unique for exactly as long as a completion can arrive.
 */
class SkillSaveTokens {
    private var next = 0L
    fun next(): Long = next++
}

sealed interface SkillDetailEvent {
    data class SaveDone(val target: SkillSaveTarget) : SkillDetailEvent
    data class SaveFailed(val message: String) : SkillDetailEvent
    object DeleteDone : SkillDetailEvent
    object DeleteFailed : SkillDetailEvent
}

class SkillDetailVM(
    private val skillManager: SkillManager,
) : ViewModel() {

    private val _tree = MutableStateFlow<List<SkillFileNode>>(emptyList())
    val tree = _tree.asStateFlow()

    private val _events = Channel<SkillDetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val saveTokens = SkillSaveTokens()

    private var skillName = ""

    /** Mint a save-invocation token. VM-owned so it survives config change with the in-flight save. */
    fun nextSaveToken(): Long = saveTokens.next()

    fun init(name: String) {
        if (skillName == name) return
        skillName = name
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = skillManager.getSkillDir(skillName) ?: return@launch
            _tree.value = buildTree(dir, dir)
        }
    }

    private fun buildTree(root: File, dir: File): List<SkillFileNode> {
        val items = dir.listFiles()?.toList() ?: return emptyList()
        val files = items
            .filter { it.isFile }
            .sortedWith(compareBy({ it.name != "SKILL.md" }, { it.name }))
            .map { f -> SkillFileNode.FileNode(SkillFile(f, f.relativeTo(root).path)) }
        val dirs = items
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .map { d -> SkillFileNode.DirNode(d.name, d.relativeTo(root).path, buildTree(root, d)) }
        return dirs + files
    }

    fun readFile(skillFile: SkillFile): String = skillFile.file.readText()

    fun saveFile(relativePath: String, content: String, target: SkillSaveTarget) {
        launchEmitting(
            events = _events,
            context = Dispatchers.IO,
            onError = { SkillDetailEvent.SaveFailed(it.message ?: "保存失败") },
        ) {
            if (relativePath == "SKILL.md") {
                val name = SkillFrontmatterParser.parse(content)["name"]
                if (name != skillName) {
                    _events.send(
                        SkillDetailEvent.SaveFailed("不允许修改技能名称（name 字段必须为 \"$skillName\"）")
                    )
                    return@launchEmitting
                }
            }
            val success = skillManager.saveSkillFile(skillName, relativePath, content)
            loadFiles()
            _events.send(
                if (success) SkillDetailEvent.SaveDone(target) else SkillDetailEvent.SaveFailed("保存失败")
            )
        }
    }

    fun deleteFile(skillFile: SkillFile) {
        launchEmitting(
            events = _events,
            context = Dispatchers.IO,
            onError = { SkillDetailEvent.DeleteFailed },
        ) {
            val success = skillManager.deleteSkillFile(skillName, skillFile.relativePath)
            if (success) loadFiles()
            _events.send(if (success) SkillDetailEvent.DeleteDone else SkillDetailEvent.DeleteFailed)
        }
    }
}
