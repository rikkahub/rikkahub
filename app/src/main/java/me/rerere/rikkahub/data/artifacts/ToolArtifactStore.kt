package me.rerere.rikkahub.data.artifacts

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import kotlin.uuid.Uuid

/** Opaque ownership boundary used for every artifact read and write. */
data class ToolArtifactScope(
    val assistantId: String,
    val conversationId: String,
    val runId: String,
    val toolExecutionId: String,
) {
    init {
        listOf(assistantId, conversationId, runId, toolExecutionId).forEach(::requireSafeId)
    }
}

data class ToolArtifactRunScope(
    val assistantId: String,
    val conversationId: String,
    val runId: String,
) {
    init {
        listOf(assistantId, conversationId, runId).forEach(::requireSafeId)
    }

    fun forExecution(toolExecutionId: String): ToolArtifactScope = ToolArtifactScope(
        assistantId = assistantId,
        conversationId = conversationId,
        runId = runId,
        toolExecutionId = toolExecutionId,
    )
}

data class ToolArtifactLimits(
    val inlineMaxBytes: Int = 32 * 1024,
    val inlineMaxEstimatedTokens: Int = 8 * 1024,
    val previewMaxBytes: Int = 4 * 1024,
    val maxArtifactBytes: Long = 4L * 1024 * 1024,
    val maxConversationBytes: Long = 32L * 1024 * 1024,
    val retentionMillis: Long = 7L * 24 * 60 * 60 * 1000,
    val maxMetadataBytes: Int = 2 * 1024,
    val maxFragmentBytes: Int = 64 * 1024,
    val maxSearchBytes: Int = 128 * 1024,
    val maxSearchResultBytes: Int = 32 * 1024,
    val maxSearchQueryBytes: Int = 1024,
    val maxSearchMatches: Int = 100,
    val maxAccessesPerArtifact: Int = 32,
    val maxReadBytesPerArtifact: Long = 256L * 1024,
) {
    init {
        require(inlineMaxBytes > 0 && inlineMaxEstimatedTokens > 0 && previewMaxBytes > 0)
        require(maxArtifactBytes > 0 && maxConversationBytes > 0 && retentionMillis >= 0)
        require(maxMetadataBytes in 1..16 * 1024)
        require(maxFragmentBytes > 0 && maxSearchBytes > 0)
        require(maxSearchResultBytes in 1..maxSearchBytes && maxSearchQueryBytes in 1..maxSearchBytes)
        require(maxSearchMatches in 1..100 && maxAccessesPerArtifact in 1..1_000)
        require(maxReadBytesPerArtifact >= maxFragmentBytes)
    }

    fun requiresArtifact(content: String): Boolean {
        val bytes = content.toByteArray(StandardCharsets.UTF_8).size
        return bytes > inlineMaxBytes || estimateTokens(bytes) > inlineMaxEstimatedTokens
    }

    fun estimateTokens(byteCount: Int): Int = (byteCount + 2) / 3
}

data class ToolArtifactReference(
    val artifactId: String?,
    /** Required with [artifactId] to retain the artifact's opaque ownership boundary in model context. */
    val toolExecutionId: String? = null,
    val sha256: String,
    val sizeBytes: Long,
    val mimeType: String,
    val preview: String,
    val stored: Boolean,
)

data class ToolArtifactFragment(
    val artifactId: String,
    val offset: Long,
    val content: String,
    val endReached: Boolean,
)

interface ToolArtifactStore {
    val limits: ToolArtifactLimits

    fun create(scope: ToolArtifactScope, content: String, mimeType: String): ToolArtifactReference
    fun readFragment(scope: ToolArtifactScope, artifactId: String, offset: Long, maxBytes: Int): ToolArtifactFragment
    fun search(scope: ToolArtifactScope, artifactId: String, query: String, maxMatches: Int = 20): List<String>
    fun deleteConversation(assistantId: String, conversationId: String)
    fun cleanup(nowMillis: Long = System.currentTimeMillis())
}

class ToolArtifactLimitExceeded(message: String) : IllegalStateException(message)

/**
 * Private, filesystem-backed artifact store. Metadata is a sidecar so artifact bodies never enter Room or messages.
 */
class FileToolArtifactStore(
    rootDirectory: File,
    override val limits: ToolArtifactLimits = ToolArtifactLimits(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ToolArtifactStore {
    private val root = rootDirectory.canonicalFile.also { it.mkdirs() }
    private val accessBudgets = mutableMapOf<String, ArtifactAccessBudget>()

    init {
        require(root.isDirectory) { "Unable to create tool artifact directory" }
    }

    @Synchronized
    override fun create(scope: ToolArtifactScope, content: String, mimeType: String): ToolArtifactReference {
        requireSafeMimeType(mimeType)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > limits.maxArtifactBytes) {
            throw ToolArtifactLimitExceeded("Tool output exceeds the maximum artifact size")
        }
        val directory = scopeDirectory(scope).also { it.mkdirs() }
        require(directory.isDirectory) { "Unable to create tool artifact scope" }
        if (conversationBytes(scope.assistantId, scope.conversationId) + bytes.size > limits.maxConversationBytes) {
            throw ToolArtifactLimitExceeded("Tool output exceeds the conversation artifact quota")
        }

        val artifactId = Uuid.random().toString()
        val sha256 = sha256(bytes)
        val body = artifactFile(scope, artifactId)
        val metadata = metadataFile(scope, artifactId)
        atomicWrite(body, bytes)
        try {
            val properties = Properties().apply {
                setProperty("artifactId", artifactId)
                setProperty("assistantId", scope.assistantId)
                setProperty("conversationId", scope.conversationId)
                setProperty("runId", scope.runId)
                setProperty("toolExecutionId", scope.toolExecutionId)
                setProperty("sha256", sha256)
                setProperty("sizeBytes", bytes.size.toString())
                setProperty("mimeType", mimeType)
                setProperty("createdAt", nowMillis().toString())
            }
            atomicWrite(metadata, propertiesToBytes(properties))
        } catch (error: Throwable) {
            body.delete()
            throw error
        }
        return ToolArtifactReference(
            artifactId = artifactId,
            toolExecutionId = scope.toolExecutionId,
            sha256 = sha256,
            sizeBytes = bytes.size.toLong(),
            mimeType = mimeType,
            preview = "",
            stored = true,
        )
    }

    @Synchronized
    override fun readFragment(scope: ToolArtifactScope, artifactId: String, offset: Long, maxBytes: Int): ToolArtifactFragment {
        require(offset >= 0) { "Artifact offset must not be negative" }
        require(maxBytes in 1..limits.maxFragmentBytes) { "Artifact fragment size is invalid" }
        val body = verifiedArtifact(scope, artifactId)
        require(offset <= body.length()) { "Artifact offset exceeds its size" }
        reserveAccess(body, requestedReadBytes = minOf(maxBytes.toLong(), body.length() - offset))
        val bytes = body.inputStream().use { input ->
            var remaining = offset
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    require(input.read() >= 0) { "Artifact is incomplete" }
                    remaining--
                }
            }
            val output = java.io.ByteArrayOutputStream(maxBytes)
            val buffer = ByteArray(minOf(DEFAULT_BUFFER_SIZE, maxBytes))
            while (output.size() < maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return ToolArtifactFragment(
            artifactId = artifactId,
            offset = offset,
            content = String(bytes, StandardCharsets.UTF_8),
            endReached = offset + bytes.size >= body.length(),
        )
    }

    @Synchronized
    override fun search(scope: ToolArtifactScope, artifactId: String, query: String, maxMatches: Int): List<String> {
        require(query.isNotBlank()) { "Artifact search query is required" }
        require(query.toByteArray(StandardCharsets.UTF_8).size <= limits.maxSearchQueryBytes) {
            "Artifact search query is too large"
        }
        require(maxMatches in 1..limits.maxSearchMatches) { "Artifact search match count is invalid" }
        val body = verifiedArtifact(scope, artifactId)
        reserveAccess(body, requestedReadBytes = minOf(limits.maxSearchBytes.toLong(), body.length()))
        val searchable = body.inputStream().use { readAtMost(it, limits.maxSearchBytes) }
        var resultBytes = 0
        return String(searchable, StandardCharsets.UTF_8).lineSequence().mapNotNull { line ->
            line.takeIf { it.contains(query, ignoreCase = true) }?.takeIf {
                val lineBytes = it.toByteArray(StandardCharsets.UTF_8).size
                if (resultBytes + lineBytes > limits.maxSearchResultBytes) {
                    false
                } else {
                    resultBytes += lineBytes
                    true
                }
            }
        }.take(maxMatches).toList()
    }

    private fun reserveAccess(body: File, requestedReadBytes: Long) {
        val key = body.canonicalPath
        val budget = accessBudgets.getOrPut(key, ::ArtifactAccessBudget)
        require(budget.accesses < limits.maxAccessesPerArtifact) { "Artifact access limit exceeded" }
        require(budget.readBytes + requestedReadBytes <= limits.maxReadBytesPerArtifact) {
            "Artifact read byte limit exceeded"
        }
        budget.accesses++
        budget.readBytes += requestedReadBytes
    }

    @Synchronized
    override fun deleteConversation(assistantId: String, conversationId: String) {
        requireSafeId(assistantId)
        requireSafeId(conversationId)
        val directory = conversationDirectory(assistantId, conversationId)
        accessBudgets.keys.removeAll { it.startsWith("${directory.path}${File.separator}") }
        directory.takeIf(File::exists)?.deleteRecursively()
    }

    @Synchronized
    override fun cleanup(nowMillis: Long) {
        root.walkTopDown().filter { it.isFile && it.extension == "properties" }.forEach { metadata ->
            val canonicalMetadata = runCatching { canonicalWithinRoot(metadata) }.getOrNull() ?: return@forEach
            val parsed = runCatching { readMetadata(canonicalMetadata, nowMillis) }.getOrNull()
            if (parsed == null || isExpired(parsed.createdAt, nowMillis)) {
                deleteArtifactPair(canonicalMetadata)
            }
        }
        root.walkTopDown().filter { it.isFile && (it.extension == "tmp" || it.extension == "bin") }.forEach { file ->
            val canonicalFile = runCatching { canonicalWithinRoot(file) }.getOrNull() ?: return@forEach
            val metadata = File(canonicalFile.parentFile, "${canonicalFile.nameWithoutExtension}.properties")
            if (!metadata.exists() && canonicalFile.lastModified() + limits.retentionMillis <= nowMillis) canonicalFile.delete()
        }
        root.walkBottomUp().filter(File::isDirectory).forEach { directory ->
            if (directory != root && directory.list().isNullOrEmpty()) directory.delete()
        }
    }

    private fun verifiedArtifact(scope: ToolArtifactScope, artifactId: String): File {
        requireSafeId(artifactId)
        val metadata = metadataFile(scope, artifactId)
        require(metadata.isFile) { "Artifact not found" }
        val properties = try {
            readMetadata(metadata, nowMillis())
        } catch (error: IllegalArgumentException) {
            deleteArtifactPair(metadata)
            throw error
        }
        if (isExpired(properties.createdAt, nowMillis())) {
            deleteArtifactPair(metadata)
            throw IllegalArgumentException("Artifact has expired")
        }
        require(
            properties.assistantId == scope.assistantId &&
                properties.conversationId == scope.conversationId &&
                properties.runId == scope.runId &&
                properties.toolExecutionId == scope.toolExecutionId &&
                properties.artifactId == artifactId,
        ) { "Artifact access is not authorized" }
        val body = artifactFile(scope, artifactId)
        require(body.isFile && body.length() == properties.sizeBytes) { "Artifact is incomplete" }
        require(sha256(body.readBytes()) == properties.sha256) { "Artifact integrity check failed" }
        return body
    }

    private fun readMetadata(file: File, now: Long): ArtifactMetadata {
        require(file.length() in 1..limits.maxMetadataBytes.toLong()) { "Artifact metadata is invalid" }
        val properties = Properties().also { properties -> file.inputStream().use { properties.load(it) } }
        require(properties.stringPropertyNames() == METADATA_KEYS) { "Artifact metadata schema is invalid" }
        val artifactId = properties.getProperty("artifactId")
        val assistantId = properties.getProperty("assistantId")
        val conversationId = properties.getProperty("conversationId")
        val runId = properties.getProperty("runId")
        val toolExecutionId = properties.getProperty("toolExecutionId")
        val sha256 = properties.getProperty("sha256")
        val sizeBytes = properties.getProperty("sizeBytes")?.toLongOrNull()
        val mimeType = properties.getProperty("mimeType")
        val createdAt = properties.getProperty("createdAt")?.toLongOrNull()
        listOf(artifactId, assistantId, conversationId, runId, toolExecutionId).forEach(::requireSafeId)
        require(sha256 != null && SHA256.matches(sha256)) { "Artifact metadata hash is invalid" }
        require(sizeBytes != null && sizeBytes in 0..limits.maxArtifactBytes) { "Artifact metadata size is invalid" }
        require(mimeType != null) { "Artifact metadata MIME type is missing" }
        requireSafeMimeType(mimeType)
        require(createdAt != null && createdAt in 0..now) { "Artifact metadata timestamp is invalid" }
        return ArtifactMetadata(artifactId!!, assistantId!!, conversationId!!, runId!!, toolExecutionId!!, sha256, sizeBytes, createdAt)
    }

    private fun isExpired(createdAt: Long, now: Long): Boolean = createdAt <= now - limits.retentionMillis

    private fun deleteArtifactPair(metadata: File) {
        accessBudgets.remove(File(metadata.parentFile, "${metadata.nameWithoutExtension}.bin").canonicalPath)
        metadata.delete()
        File(metadata.parentFile, "${metadata.nameWithoutExtension}.bin").delete()
    }

    private fun scopeDirectory(scope: ToolArtifactScope): File = canonicalWithinRoot(
        File(conversationDirectory(scope.assistantId, scope.conversationId), "${scope.runId}/${scope.toolExecutionId}"),
    )

    private fun conversationDirectory(assistantId: String, conversationId: String): File = canonicalWithinRoot(
        File(root, "v1/$assistantId/$conversationId"),
    )

    private fun artifactFile(scope: ToolArtifactScope, artifactId: String): File =
        canonicalWithinRoot(File(scopeDirectory(scope), "$artifactId.bin"))

    private fun metadataFile(scope: ToolArtifactScope, artifactId: String): File =
        canonicalWithinRoot(File(scopeDirectory(scope), "$artifactId.properties"))

    private fun canonicalWithinRoot(file: File): File {
        val canonical = file.canonicalFile
        require(canonical.path == root.path || canonical.path.startsWith("${root.path}${File.separator}")) {
            "Artifact path escapes its private root"
        }
        return canonical
    }

    private fun conversationBytes(assistantId: String, conversationId: String): Long =
        conversationDirectory(assistantId, conversationId).walkTopDown().filter { it.isFile && it.extension == "bin" }.sumOf(File::length)

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = Files.createTempFile(target.parentFile.toPath(), ".${target.name}", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readAtMost(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(maxBytes)
        val buffer = ByteArray(minOf(DEFAULT_BUFFER_SIZE, maxBytes))
        while (output.size() < maxBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun propertiesToBytes(properties: Properties): ByteArray =
        java.io.ByteArrayOutputStream().use { output ->
            properties.store(output, null)
            output.toByteArray()
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class ArtifactAccessBudget(var accesses: Int = 0, var readBytes: Long = 0)

    private data class ArtifactMetadata(
        val artifactId: String,
        val assistantId: String,
        val conversationId: String,
        val runId: String,
        val toolExecutionId: String,
        val sha256: String,
        val sizeBytes: Long,
        val createdAt: Long,
    )

    private companion object {
        val METADATA_KEYS = setOf(
            "artifactId", "assistantId", "conversationId", "runId", "toolExecutionId", "sha256", "sizeBytes", "mimeType", "createdAt",
        )
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")

private fun requireSafeId(value: String) {
    require(SAFE_ID.matches(value)) { "Artifact identifiers must be opaque safe IDs" }
}

private fun requireSafeMimeType(value: String) {
    require(
        value.matches(
            Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:; charset=[A-Za-z0-9._-]{1,32})?"),
        ),
    ) {
        "Artifact MIME type is invalid"
    }
}
