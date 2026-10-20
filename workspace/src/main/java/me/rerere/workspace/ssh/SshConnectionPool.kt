package me.rerere.workspace.ssh

import java.util.concurrent.ConcurrentHashMap

/** 按工作区复用 SSH 连接; 配置变化或连接断开时重新连接 */
class SshConnectionPool {
    private val connections = ConcurrentHashMap<String, SshConnection>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** 用一条临时连接测试配置, 不进入连接池 */
    fun probe(config: SshConnectionConfig): SshProbeResult = SshConnection.probe(config)

    fun close(id: String) {
        synchronized(lockOf(id)) {
            connections.remove(id)?.close()
        }
    }

    /**
     * 在 [id] 对应的连接上执行 [block]。连接已断开时重连并重试一次:
     * [retry] 为 true 表示操作可重复执行; 否则只在请求尚未发出 (打开 channel 失败) 时重试。
     */
    internal fun <T> use(
        id: String,
        config: SshConnectionConfig,
        retry: Boolean,
        block: (SshConnection) -> T,
    ): T {
        val connection = acquire(id, config)
        return try {
            block(connection)
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            val channelFailed = e is SshChannelOpenException
            if (connection.isConnected && !channelFailed) throw e
            evict(id, connection)
            if (!retry && !channelFailed) throw e
            block(acquire(id, config))
        }
    }

    private fun acquire(id: String, config: SshConnectionConfig): SshConnection = synchronized(lockOf(id)) {
        connections[id]?.let { existing ->
            if (existing.config == config && existing.isConnected) return existing
            connections.remove(id)
            existing.close()
        }
        SshConnection.open(config).also { connections[id] = it }
    }

    private fun evict(id: String, connection: SshConnection) {
        synchronized(lockOf(id)) {
            connections.remove(id, connection)
        }
        connection.close()
    }

    private fun lockOf(id: String): Any = locks.computeIfAbsent(id) { Any() }
}
