package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.text.TextPaint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Avatar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.security.MessageDigest
import kotlin.math.abs

/**
 * 将 [Avatar] 渲染为 sizePx × sizePx 的正方形 [Bitmap], 供通知 largeIcon 使用。
 *
 * | 头像类型 | 渲染方式 |
 * |---------|---------|
 * | Image   | file:// 用 [ImageUtils.loadOptimizedBitmap]; http(s):// 用 OkHttpClient 下载 + BitmapFactory 解码; 之后中心裁剪为正方形。失败降级 Dummy |
 * | Emoji   | Canvas + TextPaint 居中绘制 Emoji; 背景为与 Dummy 同源的 HSL 渐变 |
 * | Dummy   | 移植自 UIAvatar 的 vercelAvatarColors + LinearGradient 绘制渐变方块 |
 *
 * 本工具不依赖 Coil 的 execute() 路径 (项目无该用法先例, API 不确定);
 * 本地图片复用 [ImageUtils.loadOptimizedBitmap], 网络图片用 Koin 注入的 [OkHttpClient]。
 * 系统通知栏对 largeIcon 会按需再做圆形/圆角渲染, 故产出正方形即可。
 */
object TaMessageAvatarUtil : KoinComponent {
    private const val TAG = "TaMessageAvatarUtil"

    /** 产出 sizePx × sizePx 正方形 Bitmap, 供通知 largeIcon 使用 */
    suspend fun renderAvatarBitmap(
        context: Context,
        name: String,
        avatar: Avatar,
        sizePx: Int
    ): Bitmap = withContext(Dispatchers.IO) {
        when (avatar) {
            is Avatar.Image -> renderImage(context, avatar.url, sizePx) ?: renderDummy(name, sizePx)
            is Avatar.Emoji -> renderEmoji(name, avatar.content, sizePx)
            is Avatar.Dummy -> renderDummy(name, sizePx)
        }
    }

    private fun renderImage(context: Context, url: String, sizePx: Int): Bitmap? =
        runCatching {
            val raw = loadRawBitmap(context, url, sizePx) ?: return null
            centerCrop(raw, sizePx)
        }.onFailure {
            Log.e(TAG, "renderImage failed for url=$url", it)
        }.getOrNull()

    /** 加载原始 Bitmap: 本地 file:// 走 ImageUtils, 网络 http(s):// 走 OkHttpClient 下载 */
    private fun loadRawBitmap(context: Context, url: String, sizePx: Int): Bitmap? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "file" -> ImageUtils.loadOptimizedBitmap(context, uri, sizePx)
            "http", "https" -> loadNetworkBitmap(url, sizePx)
            "content" -> ImageUtils.loadOptimizedBitmap(context, uri, sizePx)
            else -> ImageUtils.loadOptimizedBitmap(context, uri, sizePx)
        }
    }

    /** 用 Koin 注入的 OkHttpClient 下载网络图片并解码 (采样至 sizePx, 防 OOM) */
    private fun loadNetworkBitmap(url: String, sizePx: Int): Bitmap? =
        runCatching {
            val client = get<OkHttpClient>()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.byteStream()?.use { input ->
                    // 先读尺寸
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, bounds)
                    val sample = ImageUtils.calculateInSampleSize(bounds, sizePx, sizePx)
                    // 重新请求流解码 (InputStream 不可重读)
                    val resp2 = client.newCall(Request.Builder().url(url).build()).execute()
                    resp2.use { r ->
                        r.body?.byteStream()?.use { ins ->
                            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                            BitmapFactory.decodeStream(ins, null, opts)
                        }
                    }
                }
            }
        }.getOrNull()

    /** 中心裁剪为正方形 */
    private fun centerCrop(src: Bitmap, sizePx: Int): Bitmap {
        val scale = maxOf(sizePx.toFloat() / src.width, sizePx.toFloat() / src.height)
        val scaledW = (src.width * scale).toInt().coerceAtLeast(sizePx)
        val scaledH = (src.height * scale).toInt().coerceAtLeast(sizePx)
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val x = (scaledW - sizePx) / 2
        val y = (scaledH - sizePx) / 2
        return Bitmap.createBitmap(scaled, x, y, sizePx, sizePx)
    }

    private fun renderDummy(name: String, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawGradientBackground(canvas, name, sizePx)
        return bmp
    }

    private fun renderEmoji(name: String, emoji: String, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // 背景: 与 Dummy 同源渐变, 保证视觉一致
        drawGradientBackground(canvas, name, sizePx)
        // Emoji 居中绘制
        val paint = TextPaint().apply {
            textSize = sizePx * 0.6f
            textAlign = Paint.Align.CENTER
            color = Color.WHITE
            isAntiAlias = true
        }
        val fm = paint.fontMetrics
        val baseline = sizePx / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(emoji, sizePx / 2f, baseline, paint)
        return bmp
    }

    /** 绘制与 UI ProceduralAvatar 一致的对角线渐变背景 */
    private fun drawGradientBackground(canvas: Canvas, name: String, sizePx: Int) {
        val (from, to) = vercelAvatarColors(name.ifBlank { "?" })
        val paint = Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(
                0f, 0f, sizePx.toFloat(), sizePx.toFloat(),
                intArrayOf(from, to),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)
    }

    // vercelAvatarColors / hslToColorInt: 移植自 UIAvatar.kt (SHA-1 -> HSL -> argb),
    // 把 Compose Color 换成 android.graphics.Color, 保证与 UI 的 Dummy 视觉一致。
    private fun vercelAvatarColors(name: String): Pair<Int, Int> {
        val bytes = MessageDigest.getInstance("SHA-1").digest(name.toByteArray(Charsets.UTF_8))
        val sum = bytes.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
        val hue = (sum % 360).toFloat()
        return Pair(
            hslToColorInt(hue, 0.65f, 0.55f),
            hslToColorInt((hue + 120f) % 360f, 0.65f, 0.55f)
        )
    }

    private fun hslToColorInt(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hPrime = h / 60f
        val x = c * (1f - abs(hPrime % 2f - 1f))
        val (r1, g1, b1) = when {
            hPrime < 1f -> Triple(c, x, 0f)
            hPrime < 2f -> Triple(x, c, 0f)
            hPrime < 3f -> Triple(0f, c, x)
            hPrime < 4f -> Triple(0f, x, c)
            hPrime < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        fun comp(v: Float) = ((v + m).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return Color.rgb(comp(r1), comp(g1), comp(b1))
    }
}
