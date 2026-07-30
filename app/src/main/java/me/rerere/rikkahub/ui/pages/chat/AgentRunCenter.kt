package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.agent.routing.AgentIntent
import me.rerere.rikkahub.data.ai.agent.routing.InputTrust
import me.rerere.rikkahub.data.db.entity.AgentApprovalEntity

@Composable
fun AgentRunActiveCard(
    run: AgentRunPresentation,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val routingLabel = run.routing.displayLabel()
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(HugeIcons.Cpu, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$routingLabel · ${run.status}", style = MaterialTheme.typography.titleSmall)
                Text(
                    run.currentStep ?: run.waitingReason ?: "正在等待运行遥测",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        run.model,
                        "${run.completedSteps}/${run.maxSteps ?: "?"} 步",
                    ).joinToString(" · ").ifBlank { "运行配置尚未写入" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun AgentRunDetailSheet(
    state: AgentRunDetailState,
    onDismiss: () -> Unit,
    onOpenApproval: (AgentApprovalEntity) -> Unit,
    onOpenChildRun: (String) -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        when (state) {
            AgentRunDetailState.Closed,
            AgentRunDetailState.Loading -> Text(
                text = "正在读取运行遥测...",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            AgentRunDetailState.Missing -> Text(
                text = "运行记录已不存在，可能已随会话删除。",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            is AgentRunDetailState.Content -> {
                val detail = state.detail
            val presentation = detail.toPresentation()
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                        Text("运行详情", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Run ${presentation.runId.takeLast(8)} · ${presentation.status} · ${presentation.duration}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        presentation.statusDescription?.let {
                            Text(
                                it,
                                modifier = Modifier.padding(top = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        detail.approvals.firstOrNull { it.status == "PENDING" }?.let { approval ->
                            TextButton(onClick = { onOpenApproval(approval) }) {
                                Text("前往聊天中的审批卡")
                                Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                item {
                    AgentRunSection("基本信息") {
                        AgentRunInfo("模型", presentation.model ?: "未记录")
                        AgentRunInfo(
                            stringResource(R.string.agent_routing_label),
                            presentation.routing.displayLabel(),
                        )
                        AgentRunInfo(
                            "步骤",
                            presentation.maxSteps?.let { "${presentation.completedSteps}/$it" }
                                ?: "${presentation.completedSteps}",
                        )
                        presentation.runtimeVersion?.let { AgentRunInfo("运行时", it) }
                        presentation.waitingReason?.let { AgentRunInfo("等待原因", it) }
                        presentation.failureCategory?.let { AgentRunInfo("失败分类", it) }
                    }
                }
                item { AgentRunRoutingSection(presentation.routing) }
                if (presentation.children.isNotEmpty()) {
                    item {
                        AgentRunSection("子运行") {
                            presentation.children.forEach { child ->
                                TextButton(onClick = { onOpenChildRun(child.runId) }) {
                                    Column(horizontalAlignment = Alignment.Start) {
                                        Text("Run ${child.runId.takeLast(8)} · ${child.status}")
                                        if (child.findings.isNotBlank()) {
                                            Text(child.findings, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "步骤、工具与追踪",
                        modifier = Modifier.padding(horizontal = 24.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (presentation.timeline.isEmpty()) {
                    item {
                        Text(
                            "暂未写入步骤或工具遥测。",
                            modifier = Modifier.padding(horizontal = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(presentation.timeline, key = { "${it.sequence}-${it.label}" }) { item ->
                        AgentRunTimelineCard(item)
                    }
                }
                item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
            }
            }
        }
    }
}

@Composable
internal fun AgentRunRoutingPresentation.displayLabel(): String = when (kind) {
    AgentRunRoutingKind.AUTO -> stringResource(
        R.string.agent_routing_auto_label,
        intent.intentLabel(),
    )
    AgentRunRoutingKind.LEGACY -> stringResource(
        R.string.agent_routing_legacy_label,
        legacyMode.safeLegacyModeLabel(),
    )
    AgentRunRoutingKind.UNAVAILABLE -> stringResource(R.string.agent_routing_unavailable_label)
}

@Composable
private fun AgentIntent?.intentLabel(): String = when (this) {
    AgentIntent.ANSWER -> stringResource(R.string.agent_intent_answer)
    AgentIntent.EXPLORE -> stringResource(R.string.agent_intent_explore)
    AgentIntent.EXECUTE -> stringResource(R.string.agent_intent_execute)
    AgentIntent.CLARIFY -> stringResource(R.string.agent_intent_clarify)
    null -> stringResource(R.string.agent_intent_unknown)
}

private fun String?.safeLegacyModeLabel(): String = when (this) {
    "CHAT" -> "Chat"
    "PLAN" -> "Plan"
    "AGENT" -> "Agent"
    else -> "Legacy"
}

@Composable
internal fun AgentRunRoutingSection(routing: AgentRunRoutingPresentation) {
    AgentRunSection(stringResource(R.string.agent_routing_section)) {
        when (routing.kind) {
            AgentRunRoutingKind.AUTO -> {
                AgentRunInfo(
                    stringResource(R.string.agent_routing_trust),
                    when (routing.inputTrust) {
                        InputTrust.USER_DIRECT -> stringResource(R.string.agent_routing_trust_user_direct)
                        InputTrust.DERIVED_UNTRUSTED -> stringResource(R.string.agent_routing_trust_derived)
                        null -> stringResource(R.string.agent_routing_unknown)
                    },
                )
                AgentRunInfo(
                    stringResource(R.string.agent_routing_reason),
                    routing.reasonCode ?: stringResource(R.string.agent_routing_reason_unknown),
                )
                AgentRunInfo(
                    stringResource(R.string.agent_routing_tool_count),
                    routing.toolCount.toString(),
                )
                val noToolsLabel = stringResource(R.string.agent_routing_tools_none)
                val toolNames = routing.visibleToolNames.joinToString(", ")
                    .ifBlank { noToolsLabel }
                    .let { if (routing.toolNamesTruncated) "$it …" else it }
                AgentRunInfo(stringResource(R.string.agent_routing_tools), toolNames)
                AgentRunInfo(
                    stringResource(R.string.agent_routing_permission_digest),
                    routing.permissionDigest ?: stringResource(R.string.agent_routing_unknown),
                )
                AgentRunInfo(
                    stringResource(R.string.agent_routing_policy_version),
                    routing.policyVersion ?: stringResource(R.string.agent_routing_unknown),
                )
            }

            AgentRunRoutingKind.LEGACY -> AgentRunInfo(
                stringResource(R.string.agent_routing_legacy),
                stringResource(R.string.agent_routing_legacy_description),
            )

            AgentRunRoutingKind.UNAVAILABLE -> AgentRunInfo(
                stringResource(R.string.agent_routing_degraded),
                when (routing.degradedReason) {
                    AgentRunRoutingDegradedReason.MALFORMED -> stringResource(R.string.agent_routing_degraded_malformed)
                    AgentRunRoutingDegradedReason.TOO_LARGE -> stringResource(R.string.agent_routing_degraded_too_large)
                    AgentRunRoutingDegradedReason.UNSUPPORTED -> stringResource(
                        R.string.agent_routing_degraded_unsupported
                    )
                    AgentRunRoutingDegradedReason.INVALID,
                    null,
                    -> stringResource(R.string.agent_routing_degraded_invalid)
                },
            )
        }
    }
}

@Composable
private fun AgentRunSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun AgentRunInfo(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AgentRunTimelineCard(item: AgentRunTimelineItem) {
    Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(item.status, style = MaterialTheme.typography.labelMedium)
            }
            Text("耗时 ${item.duration}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.summary?.let { Text("摘要：$it", style = MaterialTheme.typography.bodySmall) }
            item.outputSummary?.let { Text("输出摘要：$it", style = MaterialTheme.typography.bodySmall) }
            item.failureCategory?.let { Text("失败分类：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            item.approval?.let { Text("审批：$it", style = MaterialTheme.typography.bodySmall) }
            item.approvalReason?.let { Text("审批原因：$it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
