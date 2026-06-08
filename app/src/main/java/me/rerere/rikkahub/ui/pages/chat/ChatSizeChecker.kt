package me.rerere.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import me.rerere.ai.core.contextTokens
import me.rerere.ai.core.resolveReserveOutput
import me.rerere.ai.core.tokenPressure
import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.registry.ModelRegistry.getContextWindowForModel
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation

// 消息节点数量警告阈值（次级条件，保持告警罕见）。
const val MESSAGE_NODE_WARNING_THRESHOLD = 768

// 告警占用比例的绝对上限（design #193 §3/§5 不变式 warningPercent <= autoCompactPercent）。即便用户把
// autoCompactThreshold 调得很高（如 0.99），告警仍不晚于此处弹出，保证窗口真正耗尽前已有提示，同时保持
// 告警“危险且罕见”。取代旧硬编码 0.9f 常量——那与默认软阈值 0.8 比较时 0.9 <= 0.8 为假，违反不变式。
const val CONVERSATION_SIZE_WARNING_FRACTION_CAP = 0.9f

/**
 * 告警占用比例（design #193）：从该助手的软阈值 [autoCompactThreshold] 推导，并以
 * [CONVERSATION_SIZE_WARNING_FRACTION_CAP] 封顶。对任何有限阈值 `min(threshold, cap) <= threshold` 恒成立，
 * 因此设计不变式 warningFraction <= autoCompactThreshold 成立——告警绝不晚于自动压缩触发，二者不再用相互独立
 * 的比例。[autoCompactThreshold] 为 null（无对话助手）或非有限值（如持久化损坏得到的 NaN）时退回封顶值，避免
 * NaN 比例使 softOver 恒为 false 而静默关掉告警。保持原“危险且罕见”的告警语义。
 */
internal fun warningFraction(autoCompactThreshold: Float?): Float {
    val threshold = autoCompactThreshold?.takeIf { it.isFinite() } ?: CONVERSATION_SIZE_WARNING_FRACTION_CAP
    return minOf(threshold, CONVERSATION_SIZE_WARNING_FRACTION_CAP)
}

data class ConversationSizeInfo(
    val nodeCount: Int,
    val contextTokens: Int,
    val contextWindow: Int,
    val exceedNodeCountThreshold: Boolean,
    val exceedTokenThreshold: Boolean,
    val showWarning: Boolean
)

/**
 * 纯函数：会话体量告警的判定核心（design #193 Stage 1，R3）。抽出以便 JVM 单测（无 Compose/Android 依赖），
 * 使 P5（告警与触发器共用同一 [tokenPressure]、对相同输入在 over-threshold 上不可能分歧）测的是生产真正
 * 使用的接线，而非手抄镜像。
 *
 * 旧实现用最后一条 assistant 消息的 promptTokens 对照硬编码 300k 触发——token 无关于模型、且 300k 对窗口
 * 非 ~300k 的模型都是错的。改为：以与触发器完全相同的 [contextTokens]（真实 totalTokens 锚 + 待发送轮估算）
 * 对照模型相对窗口（[getContextWindowForModel]）经 [tokenPressure] 求得占用比例。
 *
 * 告警仍是“危险且罕见”的最后手段，故保留与节点数的合取：占用越过 [warningFraction]（由助手
 * [autoCompactThreshold] 推导、并以 [CONVERSATION_SIZE_WARNING_FRACTION_CAP] 封顶）**且**节点数越过
 * [MESSAGE_NODE_WARNING_THRESHOLD] 才弹窗。[model] 为 null（未配置对话模型）时退回保守默认窗口。
 */
internal fun computeConversationSizeInfo(
    nodeCount: Int,
    messages: List<UIMessage>,
    model: Model?,
    assistantMaxTokens: Int?,
    autoCompactThreshold: Float?,
): ConversationSizeInfo {
    val window = model?.let { getContextWindowForModel(it) } ?: ModelRegistry.DEFAULT_CONTEXT_WINDOW
    val pressure = tokenPressure(
        contextTokens = contextTokens(messages),
        window = window,
        // 软阈值从助手的 autoCompactThreshold 推导并封顶，保证 warningFraction <= autoCompactThreshold（设计
        // 不变式）——告警绝不晚于自动压缩触发，二者不再用相互独立的比例。
        thresholdFraction = warningFraction(autoCompactThreshold),
        // 必须与触发器（ChatService.maybeAutoCompact）用同一 reserve，否则 hardOver 的 allowedTokens 不同，
        // 两条路径在“小窗口 + 大 maxTokens”下会对 hardOver 分歧——破坏单一事实源（P5）。
        reserveOutput = resolveReserveOutput(assistantMaxTokens),
    )
    val exceedNodeCountThreshold = nodeCount > MESSAGE_NODE_WARNING_THRESHOLD
    // 与自动压缩触发器完全相同的越界谓词：soft（占用比例）或 hard（绝对安全护栏）任一越过即视为危险。
    // 不仅用 softOver——否则在“小窗口 + 大 reserve”下 hard 护栏会先于 soft 触发（allowedTokens < 软线），
    // 形成介于二者之间的区间：触发器会压缩而告警却沉默。共用 soft||hard 才是真正的单一事实源（P5）。
    val exceedTokenThreshold = pressure.softOver || pressure.hardOver
    return ConversationSizeInfo(
        nodeCount = nodeCount,
        contextTokens = pressure.contextTokens,
        contextWindow = pressure.window,
        exceedNodeCountThreshold = exceedNodeCountThreshold,
        exceedTokenThreshold = exceedTokenThreshold,
        showWarning = exceedNodeCountThreshold && exceedTokenThreshold
    )
}

@Composable
fun rememberConversationSizeInfo(
    conversation: Conversation,
    model: Model?,
    assistantMaxTokens: Int?,
    autoCompactThreshold: Float?,
): ConversationSizeInfo {
    return remember(conversation.messageNodes, model, assistantMaxTokens, autoCompactThreshold) {
        computeConversationSizeInfo(
            nodeCount = conversation.messageNodes.size,
            messages = conversation.currentMessages,
            model = model,
            assistantMaxTokens = assistantMaxTokens,
            autoCompactThreshold = autoCompactThreshold,
        )
    }
}

@Composable
fun ConversationSizeWarningDialog(
    sizeInfo: ConversationSizeInfo,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = HugeIcons.Alert01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary
            )
        },
        title = {
            Text(text = stringResource(R.string.chat_size_dialog_title))
        },
        text = {
            Text(text = stringResource(R.string.chat_size_dialog_content, sizeInfo.nodeCount))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}
