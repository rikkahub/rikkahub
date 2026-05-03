package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved SSH host the LLM (or user) can reference by name.
 *
 * Secrets (password, privateKey, passphrase) are stored in plaintext in Room. This is the
 * same posture as the rest of the app's stored credentials (provider API keys, etc.).
 * Encryption-at-rest via Android Keystore would be a future hardening.
 */
@Entity(tableName = "ssh_hosts")
data class SshHostEntity(
    /** Display name; also the lookup key from the LLM. */
    @PrimaryKey val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    val createdAtMs: Long,
)
