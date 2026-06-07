package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * Which dialog started a save. Captured at the call site (the dialog's confirm handler) and carried on
 * the completion event, so routing never depends on live UI state that may have changed between launch
 * and completion. Without this identity, an in-flight edit's completion could dismiss an unrelated
 * add-file dialog the user had just opened.
 */
enum class SkillSaveOrigin { EDIT, ADD }

sealed interface SkillDetailEvent {
    data class SaveDone(val origin: SkillSaveOrigin) : SkillDetailEvent
    data class SaveFailed(val message: String) : SkillDetailEvent
    object DeleteDone : SkillDetailEvent
    object DeleteFailed : SkillDetailEvent
}

class SkillDetailVM(
    private val skillManager: SkillManager,
) : ViewModel() {

    private val _tree = MutableStateFlow<List<SkillFileNode>>(emptyList())
    val tree = _tree.asStateFlow()

    private val _events = MutableSharedFlow<SkillDetailEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    private var skillName = ""

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

    fun saveFile(relativePath: String, content: String, origin: SkillSaveOrigin) {
        launchEmitting(
            events = _events,
            context = Dispatchers.IO,
            onError = { SkillDetailEvent.SaveFailed(it.message ?: "保存失败") },
        ) {
            if (relativePath == "SKILL.md") {
                val name = SkillFrontmatterParser.parse(content)["name"]
                if (name != skillName) {
                    _events.emit(
                        SkillDetailEvent.SaveFailed("不允许修改技能名称（name 字段必须为 \"$skillName\"）")
                    )
                    return@launchEmitting
                }
            }
            val success = skillManager.saveSkillFile(skillName, relativePath, content)
            loadFiles()
            _events.emit(
                if (success) SkillDetailEvent.SaveDone(origin) else SkillDetailEvent.SaveFailed("保存失败")
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
            _events.emit(if (success) SkillDetailEvent.DeleteDone else SkillDetailEvent.DeleteFailed)
        }
    }
}
