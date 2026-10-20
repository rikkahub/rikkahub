package me.rerere.workspace.ssh

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 按工作区复用 SSH 连接; 配置变化或连接断开时重新连接。
 *
 * 空闲超过 [idleTimeoutMillis] 且没有进行中操作 (包括打开着的 [SshShell]) 的连接会被关闭,
 * 下次使用时再重连, 避免连接和 keepalive 在 App 存活期间一直挂着。
 *
 * 清理任务持有连接池本身, 不再使用时必须调用 [close] 停止调度线程并关闭所有连接。
 */
class SshConnectionPool(
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MS,
    sweepIntervalMillis: Long = DEFAULT_SWEEP_INTERVAL_MS,
    /** 打开 channel 并等待请求回应的超时 */
    private val channelTimeoutMillis: Int = SshConnection.DEFAULT_CHANNEL_TIMEOUT_MS,
) : Closeable {
    private val connections = ConcurrentHashMap<String, PooledConnection>()

    // 按工作区串行化建连; 可中断地等待, 前一个建连卡住时被取消的调用方能及时返回
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    // 关闭与新连接登记共用这把锁: close() 在锁内标记关闭并取走已登记的连接,
    // 登记前在锁内检查 closed, 关闭期间才建好的连接会自行释放, 不会遗留在已关闭的池里
    private val lifecycleLock = Any()

    @Volatile
    private var closed = false
    private val sweeper = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ssh-idle-sweeper").apply { isDaemon = true }
    }

    init {
        sweeper.scheduleWithFixedDelay(
            // 定时任务抛异常后不会再被调度, 这里吞掉异常保证后续还能清理
            { runCatching { closeIdle() } },
            sweepIntervalMillis,
            sweepIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    /** 用一条临时连接测试配置, 不进入连接池 */
    fun probe(config: SshConnectionConfig): SshProbeResult = SshConnection.probe(config)

    fun close(id: String) {
        lockOf(id).withLock {
            connections.remove(id)?.connection?.close()
        }
    }

    /** 停止空闲清理并关闭所有连接, 之后连接池不能再使用 */
    override fun close() {
        // 不等正在建立的连接, 它们登记时会看到 closed
        val remaining = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            connections.values.toList().also { connections.clear() }
        }
        sweeper.shutdownNow()
        remaining.forEach { it.connection.close() }
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
        val pooled = acquire(id, config)
        return try {
            block(pooled.connection)
        } catch (e: Exception) {
            if (e.isInterruption()) throw e
            val channelFailed = e is SshChannelOpenException
            if (pooled.connection.isConnected && !channelFailed) throw e
            evict(id, pooled)
            if (!retry && !channelFailed) throw e
            val retried = acquire(id, config)
            try {
                block(retried.connection)
            } finally {
                retried.release()
            }
        } finally {
            pooled.release()
        }
    }

    /**
     * 在 [id] 对应的连接上打开交互式 shell, shell 关闭前一直占用这条连接。
     * 只在 channel 没打开 (请求尚未发出) 时重连重试一次。
     */
    internal fun openShell(
        id: String,
        config: SshConnectionConfig,
        open: (SshConnection, onClose: () -> Unit) -> SshShell,
    ): SshShell {
        val pooled = acquire(id, config)
        return try {
            open(pooled.connection, pooled::release)
        } catch (e: Exception) {
            pooled.release()
            if (e.isInterruption()) throw e
            val channelFailed = e is SshChannelOpenException
            if (pooled.connection.isConnected && !channelFailed) throw e
            evict(id, pooled)
            if (!channelFailed) throw e
            val retried = acquire(id, config)
            try {
                open(retried.connection, retried::release)
            } catch (retryError: Exception) {
                retried.release()
                throw retryError
            }
        }
    }

    /** 连接池里 [id] 是否有可用连接, 供测试观察空闲断开 */
    internal fun isConnected(id: String): Boolean = connections[id]?.connection?.isConnected == true

    // 在锁内登记使用, 清理线程同样持锁检查, 保证拿到手的连接不会被当成空闲关掉
    private fun acquire(id: String, config: SshConnectionConfig): PooledConnection {
        val lock = lockOf(id)
        lock.lockInterruptibly()
        try {
            check(!closed) { "SSH connection pool is closed" }
            connections[id]?.let { existing ->
                if (existing.connection.config == config && existing.connection.isConnected) {
                    existing.retain()
                    return existing
                }
                connections.remove(id)
                existing.connection.close()
            }
            // 被取消时放弃等待, 之后建好的连接直接关掉
            val connection = awaitInterruptibly(
                step = { SshConnection.open(config, channelTimeoutMillis) },
                onAbandoned = { it.close() },
            )
            val pooled = PooledConnection(connection).also { it.retain() }
            synchronized(lifecycleLock) {
                if (!closed) {
                    connections[id] = pooled
                    return pooled
                }
            }
            pooled.connection.close()
            throw IllegalStateException("SSH connection pool is closed")
        } finally {
            lock.unlock()
        }
    }

    private fun evict(id: String, pooled: PooledConnection) {
        lockOf(id).withLock {
            connections.remove(id, pooled)
        }
        pooled.connection.close()
    }

    private fun closeIdle() {
        val now = System.nanoTime()
        connections.forEach { (id, pooled) ->
            lockOf(id).withLock {
                if (connections[id] === pooled && pooled.isIdle(now, idleTimeoutMillis)) {
                    connections.remove(id)
                    pooled.connection.close()
                }
            }
        }
    }

    private fun lockOf(id: String): ReentrantLock = locks.computeIfAbsent(id) { ReentrantLock() }

    private class PooledConnection(val connection: SshConnection) {
        private val activeUses = AtomicInteger()

        @Volatile
        private var lastUsedAt = System.nanoTime()

        fun retain() {
            activeUses.incrementAndGet()
        }

        fun release() {
            lastUsedAt = System.nanoTime()
            activeUses.decrementAndGet()
        }

        fun isIdle(now: Long, timeoutMillis: Long): Boolean =
            activeUses.get() == 0 && now - lastUsedAt >= TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    }

    private companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 5 * 60_000L
        const val DEFAULT_SWEEP_INTERVAL_MS = 30_000L
    }
}
