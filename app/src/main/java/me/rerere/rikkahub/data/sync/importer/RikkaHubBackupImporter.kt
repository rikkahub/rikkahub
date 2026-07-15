package me.rerere.rikkahub.data.sync.importer

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.sync.cloud.CloudSyncRepository
import me.rerere.rikkahub.data.sync.cloud.SettingsDomainSync
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.uuid.Uuid

/**
 * Non-destructive import of official RikkaHub backup ZIP into the live Haruhome DB.
 * Never overwrites live `rikka_hub` files. Optional cloud outbox via normal repository paths.
 */
class RikkaHubBackupImporter(
    private val appContext: Context,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val filesRepository: FilesRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    private val settingsDomainSync: SettingsDomainSync? = null,
) {
    data class Options(
        /** When true, repository inserts enqueue outbox + file transfer. */
        val syncToCloud: Boolean = true,
        val importSettings: Boolean = true,
        val importChats: Boolean = true,
        val importMemories: Boolean = true,
        val importFiles: Boolean = true,
    )

    data class Report(
        val importBatchId: String,
        val schemaVersion: Int,
        val settingsMerged: Boolean,
        val assistantsAdded: Int,
        val providersAdded: Int,
        val conversationsImported: Int,
        val conversationsSkipped: Int,
        val messageNodesImported: Int,
        val memoriesImported: Int,
        val filesImported: Int,
        val filesSkipped: Int,
        val errors: List<String>,
        val warnings: List<String>,
    )

    suspend fun import(zipFile: File, options: Options = Options()): Report =
        withContext(Dispatchers.IO) {
            val batchId = UUID.randomUUID().toString()
            val extractDir = File(appContext.cacheDir, "rikka_import_$batchId")
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            try {
                if (!zipFile.exists() || zipFile.length() <= 0L) {
                    error("Backup zip is empty or missing")
                }
                extractZipSafe(zipFile, extractDir)

                val settingsFile = File(extractDir, "settings.json")
                var assistantsAdded = 0
                var providersAdded = 0
                var settingsMerged = false
                if (options.importSettings && settingsFile.exists()) {
                    val merge = mergeSettings(settingsFile)
                    assistantsAdded = merge.assistantsAdded
                    providersAdded = merge.providersAdded
                    settingsMerged = true
                }

                val dbFile = File(extractDir, "rikka_hub.db")
                if (!dbFile.exists()) {
                    warnings += "No rikka_hub.db in zip; only settings/files (if any) imported"
                }

                var schemaVersion = -1
                var conversationsImported = 0
                var conversationsSkipped = 0
                var messageNodesImported = 0
                var memoriesImported = 0
                var filesImported = 0
                var filesSkipped = 0

                val work = suspend {
                    if (dbFile.exists()) {
                        openBackupDb(extractDir).use { db ->
                            schemaVersion = db.version
                            Log.i(TAG, "import batch=$batchId schema=$schemaVersion")

                            if (options.importChats) {
                                val chat = importConversations(db)
                                conversationsImported = chat.imported
                                conversationsSkipped = chat.skipped
                                messageNodesImported = chat.nodes
                                errors += chat.errors
                            }
                            if (options.importMemories) {
                                val mem = importMemories(db)
                                memoriesImported = mem.imported
                                errors += mem.errors
                            }
                            if (options.importFiles) {
                                val fileResult = importManagedFiles(db, extractDir)
                                filesImported = fileResult.imported
                                filesSkipped = fileResult.skipped
                                errors += fileResult.errors
                            }
                        }
                    } else if (options.importFiles) {
                        val fileResult = importUploadFolderOnly(extractDir)
                        filesImported = fileResult.imported
                        filesSkipped = fileResult.skipped
                    }
                }

                // Skills live on disk (not settings.json); import before cloud enqueue.
                val skillsImported = importSkillsFromExtract(extractDir)
                if (skillsImported > 0) {
                    Log.i(TAG, "imported skills files=$skillsImported")
                }

                if (options.syncToCloud) {
                    // Defer requestSync until import finishes so conversation floods do not
                    // cancel/starve settings+assistant push mid-import.
                    cloudSyncRepository.withBulkEnqueue {
                        work()
                        // Register skill files into managed_files + outbox after disk copy.
                        runCatching {
                            cloudSyncRepository.fileDomainSync?.seedSkillsFromDisk()
                        }.onFailure {
                            Log.w(TAG, "seedSkillsFromDisk failed: ${it.message}")
                        }
                    }
                    // Force-push settings that already had a server revision (e.g. providers
                    // after first bootstrap) plus all assistants. seedLocalSnapshot alone
                    // skips keys with known revision and races with async SettingsDomainSync.
                    runCatching {
                        settingsDomainSync?.forceEnqueueSnapshot(
                            settings = settingsStore.settingsFlow.value,
                            requestSync = false,
                        )
                    }.onFailure {
                        Log.w(TAG, "forceEnqueueSnapshot after import failed: ${it.message}")
                    }
                    cloudSyncRepository.requestSync()
                    cloudSyncRepository.requestFileTransfer()
                } else {
                    cloudSyncRepository.withRemoteApply { work() }
                }

                Report(
                    importBatchId = batchId,
                    schemaVersion = schemaVersion,
                    settingsMerged = settingsMerged,
                    assistantsAdded = assistantsAdded,
                    providersAdded = providersAdded,
                    conversationsImported = conversationsImported,
                    conversationsSkipped = conversationsSkipped,
                    messageNodesImported = messageNodesImported,
                    memoriesImported = memoriesImported,
                    filesImported = filesImported,
                    filesSkipped = filesSkipped,
                    errors = errors,
                    warnings = warnings,
                )
            } finally {
                runCatching { extractDir.deleteRecursively() }
            }
        }

    private data class SettingsMerge(val assistantsAdded: Int, val providersAdded: Int)

    private suspend fun mergeSettings(settingsFile: File): SettingsMerge {
        val raw = settingsFile.readText(Charsets.UTF_8)
        val migrated = SettingsJsonMigrator.migrate(raw)
        val imported = JsonInstant.decodeFromString(Settings.serializer(), migrated)
        val current = settingsStore.settingsFlow.value
        val existingAssistantIds = current.assistants.map { it.id }.toSet()
        val existingProviderIds = current.providers.map { it.id }.toSet()
        Log.i(
            TAG,
            "merge settings: zipAssistants=${imported.assistants.size} " +
                "localAssistants=${current.assistants.size} " +
                "zipProviders=${imported.providers.size} " +
                "localProviders=${current.providers.size} " +
                "nickname=${imported.displaySetting.userNickname}",
        )

        // Prefer imported assistant when ids collide (ZIP is source of truth for import).
        val importedAssistantById = imported.assistants.associateBy { it.id }
        val mergedAssistants = buildList {
            for (a in current.assistants) {
                add(importedAssistantById[a.id] ?: a)
            }
            for (a in imported.assistants) {
                if (a.id !in existingAssistantIds) add(a)
            }
        }
        val newAssistants = imported.assistants.filter { it.id !in existingAssistantIds }

        // Prefer imported provider when ids collide so ZIP models win over empty shells.
        val importedProviderById = imported.providers.associateBy { it.id }
        val mergedProviders = buildList {
            for (p in imported.providers) add(p)
            for (p in current.providers) {
                if (p.id !in importedProviderById) add(p)
            }
        }
        val newProviders = imported.providers.filter { it.id !in existingProviderIds }
        Log.i(
            TAG,
            "merge result: assistants=${mergedAssistants.size} (new=${newAssistants.size}) " +
                "providers=${mergedProviders.size} (new=${newProviders.size})",
        )

        // Merge display profile (nickname always; keep local image avatar if import is Dummy).
        val mergedDisplay = current.displaySetting.copy(
            userNickname = imported.displaySetting.userNickname
                .ifBlank { current.displaySetting.userNickname },
            userAvatar = when {
                imported.displaySetting.userAvatar != me.rerere.rikkahub.data.model.Avatar.Dummy ->
                    imported.displaySetting.userAvatar
                else -> current.displaySetting.userAvatar
            },
            showUserAvatar = imported.displaySetting.showUserAvatar,
        )

        // Also pick up common non-destructive settings that should follow the backup.
        settingsStore.update(
            current.copy(
                assistants = mergedAssistants,
                providers = mergedProviders,
                displaySetting = mergedDisplay,
                chatModelId = imported.chatModelId,
                fastModelId = imported.fastModelId,
                titleModelId = imported.titleModelId ?: current.titleModelId,
                translateModeId = imported.translateModeId,
                imageGenerationModelId = imported.imageGenerationModelId,
                suggestionModelId = imported.suggestionModelId ?: current.suggestionModelId,
                ocrModelId = imported.ocrModelId,
                compressModelId = imported.compressModelId,
                favoriteModels = if (imported.favoriteModels.isNotEmpty()) {
                    imported.favoriteModels
                } else {
                    current.favoriteModels
                },
                enableWebSearch = imported.enableWebSearch,
                searchServices = if (imported.searchServices.isNotEmpty()) {
                    imported.searchServices
                } else {
                    current.searchServices
                },
                searchCommonOptions = imported.searchCommonOptions,
                searchServiceSelected = imported.searchServiceSelected,
                mcpServers = if (imported.mcpServers.isNotEmpty()) {
                    // Prefer imported server when ids collide.
                    val byId = imported.mcpServers.associateBy { it.id }
                    buildList {
                        for (s in imported.mcpServers) add(s)
                        for (s in current.mcpServers) {
                            if (s.id !in byId) add(s)
                        }
                    }
                } else {
                    current.mcpServers
                },
                assistantTags = if (imported.assistantTags.isNotEmpty()) {
                    (current.assistantTags + imported.assistantTags).distinctBy { it.id }
                } else {
                    current.assistantTags
                },
                quickMessages = if (imported.quickMessages.isNotEmpty()) {
                    (current.quickMessages + imported.quickMessages).distinctBy { it.id }
                } else {
                    current.quickMessages
                },
                modeInjections = if (imported.modeInjections.isNotEmpty()) {
                    // Prefer imported injection when ids collide (ZIP is source of truth).
                    val byId = imported.modeInjections.associateBy { it.id }
                    buildList {
                        for (m in imported.modeInjections) add(m)
                        for (m in current.modeInjections) {
                            if (m.id !in byId) add(m)
                        }
                    }
                } else {
                    current.modeInjections
                },
                lorebooks = if (imported.lorebooks.isNotEmpty()) {
                    val byId = imported.lorebooks.associateBy { it.id }
                    buildList {
                        for (l in imported.lorebooks) add(l)
                        for (l in current.lorebooks) {
                            if (l.id !in byId) add(l)
                        }
                    }
                } else {
                    current.lorebooks
                },
            ),
        )
        return SettingsMerge(newAssistants.size, newProviders.size)
    }

    /** Copy skills/ from backup extract into app filesDir/skills (non-destructive). */
    private fun importSkillsFromExtract(extractDir: File): Int {
        val srcRoot = File(extractDir, FileFolders.SKILLS)
        if (!srcRoot.isDirectory) return 0
        val destRoot = File(appContext.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        var count = 0
        srcRoot.walkTopDown().filter { it.isFile }.forEach { src ->
            val rel = src.relativeTo(srcRoot).invariantSeparatorsPath
            val skillName = rel.substringBefore('/', missingDelimiterValue = "")
            val skillRel = rel.substringAfter('/', missingDelimiterValue = "")
            if (skillName.isBlank() || skillRel.isBlank()) return@forEach
            val skillDir = SkillPaths.resolveSkillDir(destRoot, skillName) ?: return@forEach
            val target = SkillPaths.resolveSkillFile(skillDir, skillRel) ?: return@forEach
            if (target.exists()) return@forEach
            target.parentFile?.mkdirs()
            runCatching {
                src.copyTo(target, overwrite = false)
                count++
            }.onFailure {
                Log.w(TAG, "skill import failed $rel: ${it.message}")
            }
        }
        return count
    }

    private data class ChatImport(
        val imported: Int,
        val skipped: Int,
        val nodes: Int,
        val errors: List<String>,
    )

    private suspend fun importConversations(db: SQLiteDatabase): ChatImport {
        val errors = mutableListOf<String>()
        var imported = 0
        var skipped = 0
        var nodesTotal = 0
        val cols = tableColumns(db, "ConversationEntity")
        if (cols.isEmpty()) {
            return ChatImport(0, 0, 0, listOf("ConversationEntity table missing in backup"))
        }
        val hasMessageNode = tableExists(db, "message_node")
        db.rawQuery("SELECT * FROM ConversationEntity", null).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    val idStr = cursor.str("id") ?: continue
                    val id = runCatching { Uuid.parse(idStr) }.getOrNull() ?: continue
                    if (conversationRepository.existsConversationById(id)) {
                        skipped++
                        continue
                    }
                    val assistantId = runCatching {
                        Uuid.parse(cursor.str("assistant_id") ?: DEFAULT_ASSISTANT)
                    }.getOrElse { Uuid.parse(DEFAULT_ASSISTANT) }
                    val title = cursor.str("title").orEmpty()
                    val createAt = cursor.longOr("create_at", System.currentTimeMillis())
                    val updateAt = cursor.longOr("update_at", createAt)
                    val suggestions = cursor.str("suggestions") ?: "[]"
                    val isPinned = cursor.intOr("is_pinned", 0) != 0
                    val customSystem = cursor.str("custom_system_prompt").orEmpty()
                    val modeInjections = cursor.str("mode_injection_ids") ?: "[]"
                    val lorebooks = cursor.str("lorebook_ids") ?: "[]"
                    val workspaceCwd = cursor.str("workspace_cwd").orEmpty()
                    val folderIdRaw = cursor.str("folder_id").orEmpty()
                    val nodesJson = cursor.str("nodes") ?: "[]"

                    val messageNodes = if (hasMessageNode) {
                        loadNodesFromTable(db, idStr)
                    } else {
                        loadNodesFromEmbedded(nodesJson)
                    }
                    nodesTotal += messageNodes.size

                    val conversation = Conversation(
                        id = id,
                        assistantId = assistantId,
                        title = title,
                        messageNodes = messageNodes,
                        chatSuggestions = runCatching {
                            JsonInstant.decodeFromString<List<String>>(suggestions)
                        }.getOrDefault(emptyList()),
                        isPinned = isPinned,
                        createAt = Instant.ofEpochMilli(createAt),
                        updateAt = Instant.ofEpochMilli(updateAt),
                        customSystemPrompt = customSystem.ifEmpty { null },
                        modeInjectionIds = runCatching {
                            JsonInstant.decodeFromString<Set<Uuid>>(modeInjections)
                        }.getOrDefault(emptySet()),
                        lorebookIds = runCatching {
                            JsonInstant.decodeFromString<Set<Uuid>>(lorebooks)
                        }.getOrDefault(emptySet()),
                        workspaceCwd = workspaceCwd.ifEmpty { null },
                        folderId = folderIdRaw.ifEmpty { null }?.let {
                            runCatching { Uuid.parse(it) }.getOrNull()
                        },
                        syncEnabled = true,
                    )
                    conversationRepository.insertConversation(conversation)
                    imported++
                } catch (e: Exception) {
                    Log.w(TAG, "skip conversation", e)
                    errors += "conversation: ${e.message}"
                }
            }
        }
        return ChatImport(imported, skipped, nodesTotal, errors.take(20))
    }

    private fun loadNodesFromTable(db: SQLiteDatabase, conversationId: String): List<MessageNode> {
        val nodes = mutableListOf<MessageNode>()
        db.rawQuery(
            "SELECT id, node_index, messages, select_index FROM message_node WHERE conversation_id = ? ORDER BY node_index ASC",
            arrayOf(conversationId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val nodeId = runCatching { Uuid.parse(cursor.getString(0)) }.getOrNull() ?: continue
                val messagesRaw = cursor.getString(2) ?: "[]"
                val selectIndex = cursor.getInt(3)
                val messages = runCatching {
                    JsonInstant.decodeFromString<List<UIMessage>>(messagesRaw)
                }.getOrElse { emptyList() }
                if (messages.isEmpty()) continue
                nodes.add(
                    MessageNode(
                        id = nodeId,
                        messages = messages,
                        selectIndex = selectIndex.coerceIn(0, messages.lastIndex.coerceAtLeast(0)),
                    ),
                )
            }
        }
        return nodes
    }

    private fun loadNodesFromEmbedded(nodesJson: String): List<MessageNode> {
        // Pre schema-12 backups stored nodes JSON on the conversation row.
        return runCatching {
            JsonInstant.decodeFromString<List<MessageNode>>(nodesJson)
        }.getOrElse {
            runCatching {
                // Sometimes stored as List<UIMessage> only — wrap each as a node.
                val messages = JsonInstant.decodeFromString<List<UIMessage>>(nodesJson)
                messages.map { msg ->
                    MessageNode(messages = listOf(msg), selectIndex = 0)
                }
            }.getOrDefault(emptyList())
        }.filter { it.messages.isNotEmpty() }
    }

    private data class CountResult(val imported: Int, val errors: List<String>)

    private suspend fun importMemories(db: SQLiteDatabase): CountResult {
        val errors = mutableListOf<String>()
        var imported = 0
        val table = when {
            tableExists(db, "MemoryEntity") -> "MemoryEntity"
            tableExists(db, "memoryentity") -> "memoryentity"
            else -> return CountResult(0, listOf("Memory table missing"))
        }
        db.rawQuery("SELECT * FROM $table", null).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    val assistantId = cursor.str("assistant_id") ?: continue
                    val content = cursor.str("content").orEmpty()
                    if (content.isBlank()) continue
                    // Old Int PK or String UUID — always mint new UUID for cloud safety.
                    memoryRepository.addMemory(assistantId = assistantId, content = content)
                    imported++
                } catch (e: Exception) {
                    errors += "memory: ${e.message}"
                }
            }
        }
        return CountResult(imported, errors.take(10))
    }

    private data class FileImport(val imported: Int, val skipped: Int, val errors: List<String>)

    private suspend fun importManagedFiles(db: SQLiteDatabase, extractDir: File): FileImport {
        val errors = mutableListOf<String>()
        var imported = 0
        var skipped = 0
        val uploadSrc = File(extractDir, "upload")
        if (uploadSrc.isDirectory) {
            val r = copyUploadTree(uploadSrc)
            imported += r.imported
            skipped += r.skipped
        }
        if (!tableExists(db, "managed_files")) {
            return FileImport(imported, skipped, errors)
        }
        db.rawQuery("SELECT * FROM managed_files", null).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    val relativePath = cursor.str("relative_path") ?: continue
                    val displayName = cursor.str("display_name") ?: relativePath.substringAfterLast('/')
                    val mime = cursor.str("mime_type") ?: "application/octet-stream"
                    val folder = cursor.str("folder") ?: "upload"
                    val size = cursor.longOr("size_bytes", 0L)
                    val created = cursor.longOr("created_at", System.currentTimeMillis())
                    val updated = cursor.longOr("updated_at", created)
                    val idRaw = cursor.str("id")
                    val id = when {
                        !idRaw.isNullOrBlank() && runCatching { Uuid.parse(idRaw) }.isSuccess -> idRaw
                        else -> {
                            val stem = File(relativePath).nameWithoutExtension
                            runCatching { Uuid.parse(stem).toString() }.getOrElse {
                                UUID.nameUUIDFromBytes(relativePath.toByteArray()).toString()
                            }
                        }
                    }
                    if (filesRepository.getById(id) != null || filesRepository.getByPath(relativePath) != null) {
                        skipped++
                        continue
                    }
                    val target = File(appContext.filesDir, relativePath)
                    if (!target.exists()) {
                        // metadata without bytes — still register for completeness
                        warningsSafe(errors, "file missing on disk: $relativePath")
                    }
                    val entity = ManagedFileEntity(
                        id = id,
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mime,
                        sizeBytes = if (target.exists()) target.length() else size,
                        createdAt = created,
                        updatedAt = updated,
                        sha256 = if (target.exists()) sha256Hex(target) else "",
                        uploadStatus = ManagedFileEntity.UPLOAD_LOCAL_ONLY,
                    )
                    filesRepository.insert(entity)
                    imported++
                } catch (e: Exception) {
                    errors += "file meta: ${e.message}"
                }
            }
        }
        return FileImport(imported, skipped, errors.take(20))
    }

    private suspend fun importUploadFolderOnly(extractDir: File): FileImport {
        val uploadSrc = File(extractDir, "upload")
        if (!uploadSrc.isDirectory) return FileImport(0, 0, emptyList())
        return copyUploadTree(uploadSrc)
    }

    private suspend fun copyUploadTree(uploadSrc: File): FileImport {
        var imported = 0
        var skipped = 0
        val destRoot = File(appContext.filesDir, "upload")
        destRoot.mkdirs()
        uploadSrc.walkTopDown().filter { it.isFile }.forEach { src ->
            val rel = "upload/" + src.relativeTo(uploadSrc).invariantSeparatorsPath
            val dest = File(appContext.filesDir, rel)
            if (dest.exists()) {
                skipped++
                return@forEach
            }
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = false)
            val stem = dest.nameWithoutExtension
            val id = runCatching { Uuid.parse(stem).toString() }.getOrElse {
                UUID.nameUUIDFromBytes(rel.toByteArray()).toString()
            }
            if (filesRepository.getById(id) == null && filesRepository.getByPath(rel) == null) {
                filesRepository.insert(
                    ManagedFileEntity(
                        id = id,
                        folder = "upload",
                        relativePath = rel,
                        displayName = dest.name,
                        mimeType = guessMime(dest.name),
                        sizeBytes = dest.length(),
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        sha256 = sha256Hex(dest),
                        uploadStatus = ManagedFileEntity.UPLOAD_LOCAL_ONLY,
                    ),
                )
                imported++
            } else {
                skipped++
            }
        }
        return FileImport(imported, skipped, emptyList())
    }

    private fun warningsSafe(errors: MutableList<String>, msg: String) {
        if (errors.size < 30) errors += msg
    }

    private fun openBackupDb(extractDir: File): SQLiteDatabase {
        val dbFile = File(extractDir, "rikka_hub.db")
        // Ensure wal/shm sit beside the db for SQLite to apply.
        return SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
    }

    private fun extractZipSafe(zipFile: File, destDir: File) {
        destDir.mkdirs()
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            var count = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                if (count > MAX_ZIP_ENTRIES) error("Zip has too many entries")
                val name = entry.name.replace('\\', '/')
                if (name.contains("..")) error("Illegal zip path: $name")
                val out = File(destDir, name)
                val canonicalDest = destDir.canonicalFile
                val canonicalOut = out.canonicalFile
                if (!canonicalOut.path.startsWith(canonicalDest.path)) {
                    error("Zip slip blocked: $name")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean {
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(name),
        ).use { return it.moveToFirst() }
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> {
        if (!tableExists(db, table)) return emptySet()
        val cols = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) {
                cols += c.getString(1)
            }
        }
        return cols
    }

    private fun Cursor.str(column: String): String? {
        val idx = columnIndexSafe(column) ?: return null
        if (isNull(idx)) return null
        return getString(idx)
    }

    private fun Cursor.longOr(column: String, default: Long): Long {
        val idx = columnIndexSafe(column) ?: return default
        if (isNull(idx)) return default
        return getLong(idx)
    }

    private fun Cursor.intOr(column: String, default: Int): Int {
        val idx = columnIndexSafe(column) ?: return default
        if (isNull(idx)) return default
        return getInt(idx)
    }

    private fun Cursor.columnIndexSafe(column: String): Int? {
        val exact = getColumnIndex(column)
        if (exact >= 0) return exact
        // case-insensitive fallback
        for (i in 0 until columnCount) {
            if (getColumnName(i).equals(column, ignoreCase = true)) return i
        }
        return null
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val TAG = "RikkaHubBackupImporter"
        private const val MAX_ZIP_ENTRIES = 200_000
        private const val DEFAULT_ASSISTANT = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"
    }
}
