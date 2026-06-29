package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.uuid.Uuid

class McpOAuthTokenStore(
    context: Context,
    private val json: Json,
) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private var cachedKey: SecretKey? = null

    @Synchronized
    internal fun read(): McpOAuthStoreState {
        if (!file.exists()) return McpOAuthStoreState()
        return runCatching {
            val bytes = file.readBytes()
            require(bytes.size > IV_SIZE)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH, iv))
            json.decodeFromString<McpOAuthStoreState>(cipher.doFinal(encrypted).decodeToString())
        }.getOrElse {
            throw IllegalStateException("Failed to read MCP OAuth credentials", it)
        }
    }

    @Synchronized
    internal fun write(state: McpOAuthStoreState) {
        file.parentFile?.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.encodeToString(state).encodeToByteArray())
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(cipher.iv + encrypted)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: Throwable) {
            temporary.delete()
            throw IllegalStateException("Failed to atomically write MCP OAuth credentials", error)
        }
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
            cachedKey = it
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }.also { cachedKey = it }
    }

    private companion object {
        const val FILE_NAME = "mcp_oauth_credentials.enc"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rikkahub_mcp_oauth_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH = 128
    }
}

@Serializable
internal data class McpOAuthStoreState(
    val credentials: List<StoredMcpOAuthCredential> = emptyList(),
    val clients: List<StoredMcpOAuthClient> = emptyList(),
)

@Serializable
internal data class StoredMcpOAuthCredential(
    val serverId: Uuid,
    val resource: String,
    val authorizationServerIssuer: String,
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String? = null,
    val scopes: List<String> = emptyList(),
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    val tokenType: String = "Bearer",
)

@Serializable
internal data class StoredMcpOAuthClient(
    val serverId: Uuid,
    val authorizationServerIssuer: String,
    val clientId: String,
    val clientSecret: String? = null,
)
