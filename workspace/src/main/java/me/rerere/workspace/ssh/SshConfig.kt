package me.rerere.workspace.ssh

import kotlinx.serialization.Serializable
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

enum class SshAuthType {
    PASSWORD,
    PRIVATE_KEY,
}

/** 已信任的主机公钥 */
@Serializable
data class SshHostKey(
    /** 算法名, 如 ssh-ed25519 */
    val type: String,
    /** Base64 编码的公钥 blob */
    val key: String,
) {
    /** OpenSSH 风格的 SHA256 指纹 */
    val fingerprint: String
        get() {
            val digest = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key))
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
}

data class SshConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType,
    val password: String? = null,
    /** OpenSSH / PEM 格式的私钥文本 */
    val privateKey: String? = null,
    val passphrase: String? = null,
    /** 远程工作目录, 必须是绝对路径 */
    val remoteDir: String,
    /** 为 null 时首次连接会抛出 [SshUnknownHostKeyException], 由用户确认指纹后再连 */
    val hostKey: SshHostKey? = null,
    /** 不为空时先连这台跳板机, 再经它的 direct-tcpip 隧道连目标主机, 相当于 OpenSSH 的 ProxyJump */
    val jumpHost: SshJumpHostConfig? = null,
) {
    val address: String
        get() = "$username@$host:$port"

    // 不让凭据出现在日志和异常信息里
    override fun toString(): String =
        "SshConnectionConfig(address=$address, authType=$authType, remoteDir=$remoteDir, " +
            "hostKey=${hostKey?.fingerprint}, jumpHost=$jumpHost)"
}

/**
 * 跳板机。它只负责转发 TCP, 目标主机的认证和主机密钥校验照常进行;
 * 跳板机自己的主机密钥单独确认, 同样在认证之前校验。
 */
data class SshJumpHostConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType,
    val password: String? = null,
    /** OpenSSH / PEM 格式的私钥文本 */
    val privateKey: String? = null,
    val passphrase: String? = null,
    /** 为 null 时连接会抛出 [SshUnknownHostKeyException] (jumpHost = true) */
    val hostKey: SshHostKey? = null,
) {
    val address: String
        get() = "$username@$host:$port"

    override fun toString(): String =
        "SshJumpHostConfig(address=$address, authType=$authType, hostKey=${hostKey?.fingerprint})"
}

/** 测试连接的结果 */
data class SshProbeResult(
    val hostKey: SshHostKey,
    val homeDir: String,
    val shell: String,
    val remoteDirExists: Boolean,
)

/** 服务器的主机密钥尚未被信任, 需要用户确认 [hostKey] 的指纹; [jumpHost] 表示是跳板机的密钥 */
class SshUnknownHostKeyException(val hostKey: SshHostKey, val jumpHost: Boolean = false) :
    IOException("Unknown SSH ${jumpHost.hostLabel} key ${hostKey.type} ${hostKey.fingerprint}")

/** 服务器的主机密钥与已信任的不一致, 可能遭遇中间人攻击, 也可能是服务器重装过; [jumpHost] 表示是跳板机的密钥 */
class SshHostKeyChangedException(val expected: SshHostKey, val actual: SshHostKey, val jumpHost: Boolean = false) :
    IOException(
        "SSH ${jumpHost.hostLabel} key changed: expected ${expected.fingerprint}, got ${actual.type} ${actual.fingerprint}"
    )

private val Boolean.hostLabel: String
    get() = if (this) "jump host" else "host"
