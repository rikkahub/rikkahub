package me.rerere.ai.util

import android.util.Log
import me.rerere.ai.BuildConfig

/**
 * 打印请求体，只在调试构建里执行。
 *
 * 请求体带着历史消息里所有图片的 base64，可能有几十 MB；[body] 在发布版里不会被调用，
 * 免得为了一行日志把整个请求再序列化一遍。
 */
internal inline fun logRequestBody(tag: String, name: String, body: () -> String) {
    if (BuildConfig.DEBUG) Log.i(tag, "$name: ${body()}")
}
