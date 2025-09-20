package me.rerere.rikkahub.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.CallSuper

open class GetContentWithMultiMime : ActivityResultContract<List<String>, List<Uri>>() {
    @CallSuper
    override fun createIntent(context: Context, input: List<String>): Intent {
        return Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

    final override fun getSynchronousResult(
        context: Context,
        input: List<String>
    ): SynchronousResult<List<Uri>>? = null

    final override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        return intent.takeIf { resultCode == Activity.RESULT_OK }?.getClipDataUris()
            ?: emptyList()
    }

    internal companion object {
        internal fun Intent.getClipDataUris(): List<Uri> {
            // Use a LinkedHashSet to maintain any ordering that may be
            // present in the ClipData
            val resultSet = LinkedHashSet<Uri>()
            data?.let { data -> resultSet.add(data) }
            val clipData = clipData
            if (clipData == null && resultSet.isEmpty()) {
                return emptyList()
            } else if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    val uri = clipData.getItemAt(i).uri
                    if (uri != null) {
                        resultSet.add(uri)
                    }
                }
            }
            return ArrayList(resultSet)
        }
    }
}
