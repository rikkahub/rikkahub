package me.rerere.rikkahub.utils

import android.database.CursorWindow
import android.util.Log

private const val TAG = "DatabaseUtil"

object DatabaseUtil {
    // DiscouragedPrivateApi: reflecting on CursorWindow.sCursorWindowSize is deliberate — it raises the
    // global cursor-window size so large Room rows don't overflow the default 2 MB window. There is no
    // public API for this; the reflection is guarded (try/catch) and degrades to the default on failure.
    @Suppress("DiscouragedPrivateApi")
    fun setCursorWindowSize(size: Int) {
        try {
            val field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            val oldValue = field.get(null) as Int
            field.set(null, size)
            Log.i(TAG, "setCursorWindowSize: set $oldValue to $size")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 已fork io.requery.android.database 修改了window size，避免无法反射修改final字段
//        try {
//            val field =
//                io.requery.android.database.CursorWindow::class.java.getDeclaredField("sDefaultCursorWindowSize")
//            field.isAccessible = true
//            val oldValue = field.get(null) as Int
//            field.set(null, size)
//            Log.i(TAG, "setCursorWindowSize: set $oldValue to $size")
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
    }
}
