package me.rerere.ai.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.common.http.await
import me.rerere.common.http.getByKey
import okhttp3.OkHttpClient
import okhttp3.Request

private val TEMPLATE_VARIABLE = Regex("""\{\{\s*(\w+)\s*\}\}""")

/** 替换模板里的 `{{name}}`，引用了不存在的变量时报错 */
fun renderQuotaTemplate(template: String, variable: (String) -> String?): String =
    TEMPLATE_VARIABLE.replace(template) { match ->
        val name = match.groupValues[1]
        variable(name) ?: throw IllegalArgumentException("Unknown variable {{$name}}")
    }

/**
 * 执行 API Key 类提供商的自定义额度查询（GET）。
 *
 * 先放提供商级的自定义请求头，再写入查询配置的请求头，同名时以查询配置为准。
 */
internal suspend fun OkHttpClient.runQuotaQuery(
    setting: ProviderSetting,
    query: QuotaQuery,
    baseUrl: String,
    nextApiKey: () -> String,
): ProviderQuota.Balance = withContext(Dispatchers.IO) {
    // URL 和请求头引用的是同一个 key
    val apiKey by lazy(nextApiKey)
    val render = { template: String ->
        renderQuotaTemplate(template) { name ->
            when (name) {
                QuotaQuery.VARIABLE_BASE_URL -> baseUrl
                QuotaQuery.VARIABLE_API_KEY -> apiKey
                else -> null
            }
        }
    }
    val request = Request.Builder()
        .url(render(query.url.trim()))
        .headers(setting.mergeCustomHeaders())
        .apply {
            query.headers
                .filter { it.name.isNotBlank() }
                .forEach { header(it.name.trim(), render(it.value)) }
        }
        .get()
        .build()
    newCall(request).await().use { response ->
        val body = response.body.string()
        if (!response.isSuccessful) {
            error("Failed to query quota: ${response.code} $body")
        }
        val value = json.parseToJsonElement(body).jsonObject.getByKey(query.resultPath)
        ProviderQuota.Balance(value.toFloatOrNull()?.let { "%.2f".format(it) } ?: value)
    }
}
