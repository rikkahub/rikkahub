package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Coins
import com.composables.icons.lucide.Lucide
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.SimpleCache
import me.rerere.rikkahub.utils.toDp
import org.koin.compose.koinInject
import java.util.concurrent.TimeUnit

private val cache = SimpleCache.builder<String, String>()
    .expireAfterWrite(2, TimeUnit.MINUTES)
    .build()

@Composable
fun ProviderBalanceText(
    providerSetting: ProviderSetting,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    if (!providerSetting.balanceOption.enabled || providerSetting !is ProviderSetting.OpenAI) {
        // Balance option is disabled or provider is not OpenAI type
        return
    }

    val providerManager = koinInject<ProviderManager>()

    val value = produceState(initialValue = "~", key1 = providerSetting.id, key2 = providerSetting.balanceOption) {
        // Check cache first
        val cachedBalance = cache.getIfPresent("${providerSetting.id},${providerSetting.balanceOption.hashCode()}")
        if (cachedBalance != null) {
            value = cachedBalance
        } else {
            // Fetch balance from API
            runCatching {
                val balance = providerManager.getProviderByType(providerSetting).getBalance(providerSetting)
                // Cache the result
                cache.put("${providerSetting.id},${providerSetting.balanceOption.hashCode()}", balance)
                value = balance
            }.onFailure {
                // Handle error
                val errorMsg = "Error: ${it.message}"
                // Don't cache error messages
                value = errorMsg
            }
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Lucide.Coins,
            contentDescription = null,
            modifier = Modifier.size(style.fontSize.toDp()),
            tint = color.takeOrElse { LocalContentColor.current }
        )
        Text(
            text = value.value,
            style = style,
            maxLines = 1,
            color = color
        )
    }
}
