package me.rerere.rikkahub.ui.components.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Share03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.io.encoding.Base64

@Composable
fun ShareSheet(
    state: ShareSheetState,
) {
    val context = LocalContext.current
    val providers = state.currentProviders
    val shareContent = remember(providers) {
        when {
            providers.isNullOrEmpty() -> ""
            providers.size == 1 -> providers.single().encodeForShare()
            else -> providers.encodeForShare()
        }
    }
    val canRenderQrCode = remember(shareContent) {
        shareContent.isNotBlank() && runCatching {
            QRCodeWriter().encode(shareContent, BarcodeFormat.QR_CODE, 1, 1)
        }.isSuccess
    }
    if (state.isShow) {
        ModalBottomSheet(
            onDismissRequest = {
                state.dismiss()
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = when (providers?.size ?: 0) {
                            0 -> stringResource(R.string.share)
                            1 -> "共享提供商配置"
                            else -> "共享 ${providers?.size ?: 0} 个提供商配置"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )

                    IconButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND)
                            intent.type = "text/plain"
                            intent.putExtra(
                                Intent.EXTRA_TEXT,
                                shareContent
                            )
                            try {
                                context.startActivity(Intent.createChooser(intent, null))
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    ) {
                        Icon(HugeIcons.Share03, null)
                    }
                }

                if (providers?.isNotEmpty() == true) {
                    Text(
                        text = stringResource(R.string.provider_share_sheet_models_not_included),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (canRenderQrCode) {
                    QRCode(
                        value = shareContent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.provider_share_sheet_qr_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Serializable
private data class ProviderSharePayload(
    val providers: List<ProviderSetting>
)

private const val PROVIDER_SHARE_PREFIX_V1 = "ai-provider:v1:"
private const val PROVIDER_SHARE_PREFIX_V2 = "ai-provider:v2:"

private fun ProviderSetting.sanitizedForShare(): ProviderSetting {
    return copyProvider(models = emptyList())
}

fun ProviderSetting.encodeForShare(): String {
    return buildString {
        append(PROVIDER_SHARE_PREFIX_V1)
        val value = JsonInstant.encodeToString(this@encodeForShare.sanitizedForShare())
        append(Base64.encode(value.encodeToByteArray()))
    }
}

fun List<ProviderSetting>.encodeForShare(): String {
    require(isNotEmpty()) { "No provider settings to share" }

    return buildString {
        append(PROVIDER_SHARE_PREFIX_V2)
        val value = JsonInstant.encodeToString(
            ProviderSharePayload(
                providers = this@encodeForShare.map { it.sanitizedForShare() }
            )
        )
        append(Base64.encode(value.encodeToByteArray()))
    }
}

fun decodeProviderSettings(value: String): List<ProviderSetting> {
    return when {
        value.startsWith(PROVIDER_SHARE_PREFIX_V1) -> {
            val base64Str = value.removePrefix(PROVIDER_SHARE_PREFIX_V1)
            val jsonBytes = Base64.decode(base64Str)
            val jsonStr = jsonBytes.decodeToString()
            listOf(JsonInstant.decodeFromString<ProviderSetting>(jsonStr))
        }

        value.startsWith(PROVIDER_SHARE_PREFIX_V2) -> {
            val base64Str = value.removePrefix(PROVIDER_SHARE_PREFIX_V2)
            val jsonBytes = Base64.decode(base64Str)
            val jsonStr = jsonBytes.decodeToString()
            val payload = JsonInstant.decodeFromString<ProviderSharePayload>(jsonStr)
            require(payload.providers.isNotEmpty()) { "Provider share payload is empty" }
            payload.providers
        }

        else -> throw IllegalArgumentException("Invalid provider setting string")
    }
}

fun decodeProviderSetting(value: String): ProviderSetting {
    return decodeProviderSettings(value).single()
}

class ShareSheetState {
    private var show by mutableStateOf(false)
    val isShow get() = show

    private var providers by mutableStateOf<List<ProviderSetting>?>(null)
    val currentProviders get() = providers

    fun show(provider: ProviderSetting) {
        show(listOf(provider))
    }

    fun show(providers: List<ProviderSetting>) {
        this.show = true
        this.providers = providers
    }

    fun dismiss() {
        this.show = false
    }
}

@Composable
fun rememberShareSheetState(): ShareSheetState {
    return ShareSheetState()
}
