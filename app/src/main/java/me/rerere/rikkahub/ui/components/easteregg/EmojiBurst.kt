package me.rerere.rikkahub.ui.components.easteregg

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@Composable
fun EmojiBurstHost(
    modifier: Modifier = Modifier,
    emojiOptions: List<String>,
    burstCount: Int = 12,
    content: @Composable (onBurst: (Offset) -> Unit) -> Unit
) {
    val particles = remember { mutableStateListOf<EmojiParticle>() }
    var nextId by remember { mutableStateOf(0L) }
    val density = LocalDensity.current
    val emojiRadiusPx = remember(density) { with(density) { 28.sp.toPx() / 2f } }
    val emojiTextStyle = remember { TextStyle(fontSize = 28.sp) }
    val initialDt = 1f / 60f
    val pendingBursts = remember { mutableStateListOf<BurstRequest>() }
    BoxWithConstraints(modifier = modifier) {
        val bounds by rememberUpdatedState(
            Size(
                width = constraints.maxWidth.toFloat(),
                height = constraints.maxHeight.toFloat()
            )
        )
        val onBurst: (Offset) -> Unit = onBurst@{ origin ->
            if (emojiOptions.isEmpty()) return@onBurst
            val minX = emojiRadiusPx
            val minY = emojiRadiusPx
            val maxX = (bounds.width - emojiRadiusPx).coerceAtLeast(minX)
            val maxY = (bounds.height - emojiRadiusPx).coerceAtLeast(minY)
            val start = Offset(
                origin.x.coerceIn(minX, maxX),
                origin.y.coerceIn(minY, maxY)
            )
            pendingBursts.add(
                BurstRequest(
                    center = start,
                    remaining = burstCount,
                    minX = minX,
                    maxX = maxX,
                    minY = minY,
                    maxY = maxY
                )
            )
        }
        content(onBurst)
        particles.forEach { particle ->
            key(particle.id) {
                Text(
                    text = particle.emoji,
                    style = emojiTextStyle,
                    modifier = Modifier.offset {
                        IntOffset(
                            (particle.position.x - emojiRadiusPx).roundToInt(),
                            (particle.position.y - emojiRadiusPx).roundToInt()
                        )
                    }
                )
            }
        }
        LaunchedEffect(Unit) {
            val gravity = 2500f
            val wallDamping = 0.7f
            val floorDamping = 0.65f
            val restitution = 0.75f
            val lifetimeSeconds = 4f
            val damping = 0.99f
            var lastTime = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = ((now - lastTime) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastTime = now
                if (pendingBursts.isNotEmpty()) {
                    val batchSize = 4
                    val iterator = pendingBursts.listIterator()
                    while (iterator.hasNext()) {
                        val request = iterator.next()
                        val spawnCount = minOf(batchSize, request.remaining)
                        repeat(spawnCount) {
                            val emoji = emojiOptions[Random.nextInt(emojiOptions.size)]
                            val jitterAngle = Math.toRadians(Random.nextDouble(0.0, 360.0)).toFloat()
                            val jitterRadius = emojiRadiusPx * 1.5f * Random.nextFloat()
                            val jitter = Offset(
                                cos(jitterAngle) * jitterRadius,
                                sin(jitterAngle) * jitterRadius
                            )
                            val spawn = Offset(
                                (request.center.x + jitter.x).coerceIn(request.minX, request.maxX),
                                (request.center.y + jitter.y).coerceIn(request.minY, request.maxY)
                            )
                            val angleRad = Math.toRadians(Random.nextDouble(220.0, 320.0)).toFloat()
                            val speed = 350f + Random.nextFloat() * 250f
                            val vx = cos(angleRad) * speed
                            val vy = sin(angleRad) * speed
                            val startPrev = Offset(spawn.x - vx * initialDt, spawn.y - vy * initialDt)
                            particles.add(
                                EmojiParticle(
                                    id = nextId++,
                                    emoji = emoji,
                                    position = spawn,
                                    previousPosition = startPrev,
                                    ageSeconds = 0f
                                )
                            )
                        }
                        val remaining = request.remaining - spawnCount
                        if (remaining <= 0) {
                            iterator.remove()
                        } else {
                            iterator.set(request.copy(remaining = remaining))
                        }
                    }
                }
                if (particles.isEmpty()) continue
                val current = particles.toList()
                val count = current.size
                val positions = Array(count) { current[it].position }
                val previousPositions = Array(count) { current[it].previousPosition }
                val ages = FloatArray(count) { current[it].ageSeconds + dt }
                val minX = emojiRadiusPx
                val minY = emojiRadiusPx
                val maxX = (bounds.width - emojiRadiusPx).coerceAtLeast(minX)
                val maxY = (bounds.height - emojiRadiusPx).coerceAtLeast(minY)
                for (i in 0 until count) {
                    val position = positions[i]
                    val previous = previousPositions[i]
                    val vx = (position.x - previous.x) * damping
                    val vy = (position.y - previous.y) * damping
                    val newX = position.x + vx
                    val newY = position.y + vy + gravity * dt * dt
                    previousPositions[i] = position
                    positions[i] = Offset(newX, newY)
                }
                val minDistance = emojiRadiusPx * 2f
                val minDistanceSq = minDistance * minDistance
                repeat(2) {
                    for (i in 0 until count) {
                        for (j in i + 1 until count) {
                            val dx = positions[j].x - positions[i].x
                            val dy = positions[j].y - positions[i].y
                            val distSq = dx * dx + dy * dy
                            if (distSq >= minDistanceSq) continue
                            val dist = sqrt(distSq).coerceAtLeast(0.01f)
                            val nx = dx / dist
                            val ny = dy / dist
                            val overlap = minDistance - dist
                            positions[i] = Offset(
                                positions[i].x - nx * overlap / 2f,
                                positions[i].y - ny * overlap / 2f
                            )
                            positions[j] = Offset(
                                positions[j].x + nx * overlap / 2f,
                                positions[j].y + ny * overlap / 2f
                            )
                            val vi = positions[i] - previousPositions[i]
                            val vj = positions[j] - previousPositions[j]
                            val rvx = vj.x - vi.x
                            val rvy = vj.y - vi.y
                            val velAlongNormal = rvx * nx + rvy * ny
                            if (velAlongNormal < 0f) {
                                val impulse = -(1f + restitution) * velAlongNormal / 2f
                                val ix = impulse * nx
                                val iy = impulse * ny
                                val newVi = Offset(vi.x - ix, vi.y - iy)
                                val newVj = Offset(vj.x + ix, vj.y + iy)
                                previousPositions[i] = positions[i] - newVi
                                previousPositions[j] = positions[j] - newVj
                            }
                        }
                    }
                    for (i in 0 until count) {
                        val x = positions[i].x.coerceIn(minX, maxX)
                        val y = positions[i].y.coerceIn(minY, maxY)
                        positions[i] = Offset(x, y)
                    }
                }
                for (i in 0 until count) {
                    val position = positions[i]
                    val previous = previousPositions[i]
                    var vx = position.x - previous.x
                    var vy = position.y - previous.y
                    if (position.x <= minX && vx < 0f) {
                        vx = -vx * wallDamping
                    } else if (position.x >= maxX && vx > 0f) {
                        vx = -vx * wallDamping
                    }
                    if (position.y <= minY && vy < 0f) {
                        vy = -vy * wallDamping
                    } else if (position.y >= maxY && vy > 0f) {
                        vy = -vy * floorDamping
                    }
                    previousPositions[i] = Offset(position.x - vx, position.y - vy)
                }
                particles.clear()
                for (i in 0 until count) {
                    val position = positions[i]
                    val previous = previousPositions[i]
                    val age = ages[i]
                    val vx = (position.x - previous.x) / dt
                    val vy = (position.y - previous.y) / dt
                    val speed = sqrt(vx * vx + vy * vy)
                    if (position.y >= maxY && speed < 20f) {
                        previousPositions[i] = position
                    }
                    val shouldRemove =
                        age >= lifetimeSeconds && position.y >= maxY && speed < 200f
                    if (!shouldRemove) {
                        particles.add(
                            current[i].copy(
                                position = position,
                                previousPosition = previousPositions[i],
                                ageSeconds = age
                            )
                        )
                    }
                }
            }
        }
    }
}

private data class EmojiParticle(
    val id: Long,
    val emoji: String,
    val position: Offset,
    val previousPosition: Offset,
    val ageSeconds: Float
)

private data class BurstRequest(
    val center: Offset,
    val remaining: Int,
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float
)
