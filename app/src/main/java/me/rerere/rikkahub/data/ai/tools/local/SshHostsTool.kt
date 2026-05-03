package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository

/**
 * Save an SSH host so it can be referenced by name in subsequent calls instead of passing
 * credentials every time. Replaces any existing host with the same name.
 */
fun saveSshHostTool(repo: SshHostRepository): Tool = Tool(
    name = "save_ssh_host",
    description = """
        Persist an SSH host (host, port, user, credentials) under a short name so the LLM can
        reference it later via ssh_exec_saved / ssh_upload / ssh_download without re-typing
        credentials. Replaces any existing host with the same name. Pass either password OR
        private_key for authentication.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Short name (used as lookup key)") })
                put("host", buildJsonObject { put("type", "string"); put("description", "Hostname or IP") })
                put("port", buildJsonObject { put("type", "integer"); put("description", "SSH port, default 22") })
                put("user", buildJsonObject { put("type", "string"); put("description", "SSH username") })
                put("password", buildJsonObject { put("type", "string"); put("description", "Password (use only if no private_key)") })
                put("private_key", buildJsonObject { put("type", "string"); put("description", "Full PEM/OpenSSH private key contents") })
                put("passphrase", buildJsonObject { put("type", "string"); put("description", "Optional passphrase for the private key") })
            },
            required = listOf("name", "host", "user")
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
        val host = p["host"]?.jsonPrimitive?.contentOrNull ?: error("host is required")
        val user = p["user"]?.jsonPrimitive?.contentOrNull ?: error("user is required")
        val port = p["port"]?.jsonPrimitive?.intOrNull ?: 22
        val password = p["password"]?.jsonPrimitive?.contentOrNull
        val privateKey = p["private_key"]?.jsonPrimitive?.contentOrNull
        val passphrase = p["passphrase"]?.jsonPrimitive?.contentOrNull
        if (password.isNullOrBlank() && privateKey.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "must provide password or private_key") }.toString()
            ))
        }
        repo.upsert(SshHostEntity(
            name = name, host = host, port = port, user = user,
            password = password, privateKey = privateKey, passphrase = passphrase,
            createdAtMs = System.currentTimeMillis(),
        ))
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("name", name)
        }.toString()))
    }
)

/** List saved hosts. Secrets omitted — only metadata + which auth method is configured. */
fun listSshHostsTool(repo: SshHostRepository): Tool = Tool(
    name = "list_ssh_hosts",
    description = "List all saved SSH hosts (name, host, port, user, has_password, has_private_key). Secrets are not returned.".trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val all = repo.getAll()
        listOf(UIMessagePart.Text(buildJsonObject {
            put("hosts", buildJsonArray {
                all.forEach { h ->
                    addJsonObject {
                        put("name", h.name)
                        put("host", h.host)
                        put("port", h.port)
                        put("user", h.user)
                        put("has_password", !h.password.isNullOrBlank())
                        put("has_private_key", !h.privateKey.isNullOrBlank())
                    }
                }
            })
        }.toString()))
    }
)

/** Delete a saved host by name. */
fun deleteSshHostTool(repo: SshHostRepository): Tool = Tool(
    name = "delete_ssh_host",
    description = "Delete a saved SSH host by name.".trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
            },
            required = listOf("name")
        )
    },
    execute = { input ->
        val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            ?: error("name is required")
        repo.deleteByName(name)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("name", name)
        }.toString()))
    }
)

/**
 * Forget the stored SSH host key(s) for a host. Use after the user explicitly confirms they
 * reinstalled the remote — the next connect will trust the new key per accept-new policy.
 *
 * The host argument is the actual hostname/IP (not a saved-host name). If the saved host's
 * underlying address changed too, look it up via list_ssh_hosts first.
 */
fun forgetSshHostKeyTool(context: Context): Tool = Tool(
    name = "ssh_forget_host_key",
    description = """
        Remove the stored host key for an SSH host from known_hosts. Call this AFTER the user
        explicitly confirms they reinstalled the remote machine — the next connect will trust
        the new key. NEVER call this without user confirmation: a changed host key can also
        indicate a man-in-the-middle attack.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", buildJsonObject { put("type", "string"); put("description", "Hostname or IP whose stored key should be forgotten") })
            },
            required = listOf("host")
        )
    },
    execute = { input ->
        val host = input.jsonObject["host"]?.jsonPrimitive?.contentOrNull
            ?: error("host is required")
        val removed = forgetHostKey(context, host)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("host", host)
            put("keys_removed", removed)
        }.toString()))
    }
)

/** Run a command on a saved host without re-passing credentials. */
fun sshExecSavedTool(context: Context, repo: SshHostRepository): Tool = Tool(
    name = "ssh_exec_saved",
    description = """
        Run a shell command on a previously-saved SSH host (looked up by name). Returns
        stdout, stderr, exit_code. For destructive or system-level commands you should
        explicitly confirm with the user before invoking.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
                put("command", buildJsonObject { put("type", "string"); put("description", "Shell command to run") })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Total timeout, default 30, max 300") })
            },
            required = listOf("name", "command")
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
        val command = p["command"]?.jsonPrimitive?.contentOrNull ?: error("command is required")
        val timeoutSec = (p["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 300)
        val h = repo.getByName(name)
            ?: return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "no saved host: $name") }.toString()
            ))
        val auth = SshAuth(password = h.password, privateKey = h.privateKey, passphrase = h.passphrase)
        if (!auth.isUsable()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "saved host has no usable credentials") }.toString()
            ))
        }
        val payload = withTimeoutOrNull(timeoutSec * 1000L) {
            withContext(Dispatchers.IO) {
                execOneShot(context, h.host, h.port, h.user, auth, command, timeoutSec * 1000)
            }
        } ?: buildJsonObject { put("error", "timeout") }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
