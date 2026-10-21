package me.rerere.rikkahub.ui.components.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QRCodeDecoderTest {
    private val content = "rikkahub://provider/eyJ0eXBlIjoib3BlbmFpIiwibmFtZSI6IuS4reaWhyJ9"

    /** 生成二维码的灰度帧，四周留白并在每行末尾加 [padding] 字节填充，模拟相机帧的 rowStride */
    private fun frame(width: Int, height: Int, padding: Int): ByteArray {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 400, 400)
        val rowStride = width + padding
        val data = ByteArray(rowStride * height) { 0xFF.toByte() }
        val left = (width - matrix.width) / 2
        val top = (height - matrix.height) / 2
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) data[(top + y) * rowStride + left + x] = 0
            }
        }
        return data
    }

    @Test
    fun `decodes a frame whose rows are padded`() {
        val decoder = QRCodeDecoder()
        assertEquals(content, decoder.decode(frame(640, 480, 64), 640 + 64, 640, 480))
        assertEquals(content, decoder.decode(frame(640, 480, 0), 640, 640, 480))
    }

    @Test
    fun `returns null when there is no qr code`() {
        val blank = ByteArray(640 * 480) { 0x80.toByte() }
        assertNull(QRCodeDecoder().decode(blank, 640, 640, 480))
    }
}
