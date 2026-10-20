package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshHostKey

/**
 * SSH 工作区的持久化配置, 以 JSON 存在 workspaces.ssh_config 列。
 *
 * 数据库会随备份导出, 凭据字段只存 [me.rerere.rikkahub.utils.SecretCipher] 加密后的密文;
 * 密钥在 Android Keystore 中且不可导出, 换设备恢复备份后需要重新填写凭据。
 */
@Serializable
data class SshWorkspaceSettings(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType = SshAuthType.PASSWORD,
    /** 远程工作目录, 绝对路径 */
    val remoteDir: String,
    /** 用户确认过的主机公钥 */
    val hostKey: SshHostKey? = null,
    val encryptedPassword: String? = null,
    val encryptedPrivateKey: String? = null,
    val encryptedPassphrase: String? = null,
)

/** SSH 工作区编辑表单。凭据为明文; 编辑已有工作区时留空表示沿用已保存的值 */
data class SshWorkspaceDraft(
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val remoteDir: String,
    val hostKey: SshHostKey? = null,
)
