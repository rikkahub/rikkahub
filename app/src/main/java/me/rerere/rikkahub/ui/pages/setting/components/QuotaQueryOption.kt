package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.QuotaQuery
import me.rerere.ai.provider.renderQuotaTemplate
import me.rerere.common.http.isJsonExprValid
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 自定义额度查询的设置项，只给 API Key 类提供商用 */
@Composable
fun SettingProviderQuotaQuery(
    provider: ProviderSetting.ApiKey,
    modifier: Modifier = Modifier,
    // 标题下方的说明，用来放查到的额度
    supportingContent: (@Composable () -> Unit)? = null,
    onEdit: (QuotaQuery) -> Unit,
) {
    val query = provider.quotaQuery
    var expand by remember { mutableStateOf(false) }
    val codeStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono)
    CardGroup(modifier = modifier) {
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_provider_page_balance_info)) },
            supportingContent = supportingContent,
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { expand = !expand }) {
                        Icon(
                            imageVector = if (expand) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                            contentDescription = null,
                        )
                    }
                    Switch(
                        checked = query.enabled,
                        onCheckedChange = { onEdit(query.copy(enabled = it)) },
                    )
                }
            },
        ) {
            AnimatedVisibility(visible = expand) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = query.url,
                        onValueChange = { onEdit(query.copy(url = it)) },
                        label = { Text("URL") },
                        isError = !isQuotaUrlValid(query.url),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = codeStyle,
                    )
                    query.headers.forEachIndexed { index, header ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            OutlinedTextField(
                                value = header.name,
                                onValueChange = { name ->
                                    onEdit(query.copy(headers = query.headers.replaced(index, header.copy(name = name))))
                                },
                                label = { Text(stringResource(R.string.assistant_page_header_name)) },
                                singleLine = true,
                                modifier = Modifier.weight(2f),
                                textStyle = codeStyle,
                            )
                            OutlinedTextField(
                                value = header.value,
                                onValueChange = { value ->
                                    onEdit(query.copy(headers = query.headers.replaced(index, header.copy(value = value))))
                                },
                                label = { Text(stringResource(R.string.assistant_page_header_value)) },
                                singleLine = true,
                                modifier = Modifier.weight(3f),
                                textStyle = codeStyle,
                            )
                            IconButton(
                                onClick = {
                                    onEdit(query.copy(headers = query.headers.filterIndexed { i, _ -> i != index }))
                                }
                            ) {
                                Icon(
                                    HugeIcons.Delete01,
                                    contentDescription = stringResource(R.string.assistant_page_delete_header)
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = query.resultPath,
                        onValueChange = { onEdit(query.copy(resultPath = it)) },
                        label = { Text(stringResource(R.string.setting_provider_page_balance_json_key)) },
                        isError = !isJsonExprValid(query.resultPath),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = codeStyle,
                    )
                    Text(
                        text = stringResource(
                            R.string.setting_provider_page_quota_variables,
                            QuotaQuery.VARIABLES.joinToString { "{{$it}}" },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            onClick = { onEdit(query.copy(headers = query.headers + CustomHeader("", ""))) }
                        ) {
                            Icon(HugeIcons.Add01, contentDescription = null)
                            Text(stringResource(R.string.assistant_page_add_header))
                        }
                        TextButton(
                            onClick = {
                                val builtIn = DEFAULT_PROVIDERS.find { it.id == provider.id } as? ProviderSetting.ApiKey
                                onEdit(builtIn?.quotaQuery ?: provider.defaultQuotaQuery())
                            },
                        ) {
                            Text(stringResource(R.string.setting_model_page_reset_to_default))
                        }
                    }
                }
            }
        }
    }
}

private fun List<CustomHeader>.replaced(index: Int, header: CustomHeader): List<CustomHeader> =
    toMutableList().apply { set(index, header) }

// 鉴权头按类型不同，取各类型的默认配置
private fun ProviderSetting.ApiKey.defaultQuotaQuery(): QuotaQuery = when (this) {
    is ProviderSetting.OpenAI -> ProviderSetting.OpenAI().quotaQuery
    is ProviderSetting.Google -> ProviderSetting.Google().quotaQuery
    is ProviderSetting.Claude -> ProviderSetting.Claude().quotaQuery
}

// 用示例值代入变量后能解析成 http(s) 地址
private fun isQuotaUrlValid(url: String): Boolean = runCatching {
    renderQuotaTemplate(url.trim()) { name ->
        when (name) {
            QuotaQuery.VARIABLE_BASE_URL -> "https://example.com/v1"
            QuotaQuery.VARIABLE_API_KEY -> "key"
            else -> null
        }
    }.toHttpUrlOrNull() != null
}.getOrDefault(false)
