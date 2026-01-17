package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.Screen
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

private const val TAG = "ChatUtil"

fun navigateToChatPage(
    navController: NavHostController,
    chatId: Uuid = Uuid.random(),
    initText: String? = null,
    initFiles: List<Uri> = emptyList(),
) {
    Log.i(TAG, "navigateToChatPage: navigate to $chatId")
    navController.navigate(
        route = Screen.Chat(
            id = chatId.toString(),
            text = initText,
            files = initFiles.map { it.toString() },
        ),
    ) {
        popUpTo(0) {
            inclusive = true
        }
        launchSingleTop = true
    }
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toText())
}

@OptIn(ExperimentalEncodingApi::class)
suspend fun Context.saveMessageImage(image: String) = withContext(Dispatchers.IO) {
    when {
        image.startsWith("data:image") -> {
            val byteArray = Base64.decode(image.substringAfter("base64,").toByteArray())
            val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
            exportImage(this@saveMessageImage.getActivity()!!, bitmap)
        }

        image.startsWith("file:") -> {
            val file = image.toUri().toFile()
            exportImageFile(this@saveMessageImage.getActivity()!!, file)
        }

        image.startsWith("http") -> {
            kotlin.runCatching { // Use runCatching to handle potential network exceptions
                val url = java.net.URL(image)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connect()

                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                    exportImage(this@saveMessageImage.getActivity()!!, bitmap)
                } else {
                    Log.e(
                        TAG,
                        "saveMessageImage: Failed to download image from $image, response code: ${connection.responseCode}"
                    )
                    null // Return null on failure
                }
            }.getOrNull() // Return null if any exception occurs during download
        }

        else -> error("Invalid image format")
    }
}

fun Context.createChatFilesByContents(uris: List<Uri>): List<Uri> {
    val newUris = mutableListOf<Uri>()
    val dir = this.filesDir.resolve("upload")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    uris.forEach { uri ->
        val fileName = Uuid.random()
        val file = dir.resolve("$fileName")
        if (!file.exists()) {
            file.createNewFile()
        }
        val newUri = file.toUri()
        runCatching {
            this.contentResolver.openInputStream(uri)?.use { inputStream ->
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            newUris.add(newUri)
        }.onFailure {
            it.printStackTrace()
            Log.e(TAG, "createChatFilesByContents: Failed to save image from $uri", it)
        }
    }
    return newUris
}

fun Context.createChatFilesByByteArrays(byteArrays: List<ByteArray>): List<Uri> {
    val newUris = mutableListOf<Uri>()
    val dir = this.filesDir.resolve("upload")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    byteArrays.forEach { byteArray ->
        val fileName = Uuid.random()
        val file = dir.resolve("$fileName")
        if (!file.exists()) {
            file.createNewFile()
        }
        val newUri = file.toUri()
        file.outputStream().use { outputStream ->
            outputStream.write(byteArray)
        }
        newUris.add(newUri)
    }
    return newUris
}

fun Context.getFileNameFromUri(uri: Uri): String? {
    var fileName: String? = null
    val projection = arrayOf(
        OpenableColumns.DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME // 优先尝试 DocumentProvider 标准列
    )
    contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        // 移动到第一行结果
        if (cursor.moveToFirst()) {
            // 尝试获取 DocumentsContract.Document.COLUMN_DISPLAY_NAME 的索引
            val documentDisplayNameIndex =
                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (documentDisplayNameIndex != -1) {
                fileName = cursor.getString(documentDisplayNameIndex)
            } else {
                // 如果 DocumentProvider 标准列不存在，尝试 OpenableColumns.DISPLAY_NAME
                val openableDisplayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (openableDisplayNameIndex != -1) {
                    fileName = cursor.getString(openableDisplayNameIndex)
                }
            }
        }
    }
    // 如果查询失败或没有获取到名称，fileName 会保持 null
    return fileName
}

fun Context.getFileMimeType(uri: Uri): String? {
    return when (uri.scheme) {
        "content" -> contentResolver.getType(uri)
        else -> null
    }
}

@OptIn(ExperimentalEncodingApi::class)
suspend fun Context.convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
    withContext(Dispatchers.IO) {
        message.copy(
            parts = message.parts.map { part ->
                when (part) {
                    is UIMessagePart.Image -> {
                        if (part.url.startsWith("data:image")) {
                            // base64 image
                            val sourceByteArray = Base64.decode(part.url.substringAfter("base64,").toByteArray())
                            val bitmap = BitmapFactory.decodeByteArray(sourceByteArray, 0, sourceByteArray.size)
                            val byteArray = bitmap.compress()
                            val urls = createChatFilesByByteArrays(listOf(byteArray))
                            Log.i(
                                TAG,
                                "convertBase64ImagePartToLocalFile: convert base64 img to ${urls.joinToString(", ")}"
                            )
                            part.copy(
                                url = urls.first().toString(),
                            )
                        } else {
                            part
                        }
                    }

                    else -> part
                }
            }
        )
    }

fun Bitmap.compress(): ByteArray = ByteArrayOutputStream().use {
    compress(Bitmap.CompressFormat.PNG, 100, it)
    it.toByteArray()
}

fun Context.deleteChatFiles(uris: List<Uri>) {
    uris.filter { it.toString().startsWith("file:") }.forEach { uri ->
        val file = uri.toFile()
        if (file.exists()) {
            file.delete()
        }
    }
}

fun Context.deleteAllChatFiles() {
    val dir = this.filesDir.resolve("upload")
    if (dir.exists()) {
        dir.deleteRecursively()
    }
}

suspend fun Context.countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
    val dir = filesDir.resolve("upload")
    if (!dir.exists()) {
        return@withContext Pair(0, 0)
    }
    val files = dir.listFiles() ?: return@withContext Pair(0, 0)
    val count = files.size
    val size = files.sumOf { it.length() }
    Pair(count, size)
}

fun Context.getImagesDir(): File {
    val dir = this.filesDir.resolve("images")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}

fun Context.createImageFileFromBase64(base64Data: String, filePath: String): File {
    val data = if (base64Data.startsWith("data:image")) {
        base64Data.substringAfter("base64,")
    } else {
        base64Data
    }

    val byteArray = Base64.decode(data.toByteArray())
    val file = File(filePath)
    file.parentFile?.mkdirs()
    file.writeBytes(byteArray)
    return file
}

fun Context.listImageFiles(): List<File> {
    val imagesDir = getImagesDir()
    return imagesDir.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }?.toList()
        ?: emptyList()
}

fun Context.createChatTextFile(text: String): UIMessagePart.Document {
    val dir = this.filesDir.resolve("upload")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val fileName = "${Uuid.random()}.txt"
    val file = dir.resolve(fileName)
    file.writeText(text)
    return UIMessagePart.Document(
        url = file.toUri().toString(),
        fileName = "pasted_text.txt",
        mime = "text/plain"
    )
}
