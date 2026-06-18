package me.rerere.rikkahub.ui.pages.assistant.detail

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun AssistantTaMessagePage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_ta_message))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantTaMessageContent(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            assistant = assistant,
            onUpdateAssistant = { vm.update(it) },
        )
    }
}

@Composable
private fun AssistantTaMessageContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 总开关
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.ta_message_enable_label)) },
                supportingContent = {
                    Text(text = stringResource(R.string.ta_message_enable_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.taMessageEnabled,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(taMessageEnabled = it)
                            )
                        }
                    )
                }
            )
        }

        // 以下选项仅在开关开启时显示
        AnimatedVisibility(
            visible = assistant.taMessageEnabled,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 下次助手主动发消息时间
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.ta_message_next_time_label)) },
                        supportingContent = {
                            NextTimeDisplay(
                                timestamp = assistant.taMessageNextTime,
                                onSelectTime = { newTimestamp ->
                                    onUpdateAssistant(
                                        assistant.copy(taMessageNextTime = newTimestamp)
                                    )
                                },
                                onClearTime = {
                                    onUpdateAssistant(
                                        assistant.copy(taMessageNextTime = null)
                                    )
                                }
                            )
                        }
                    )
                }

                // Ta的来信提示词
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.ta_message_prompt_label)) },
                        supportingContent = {
                            OutlinedTextField(
                                value = assistant.taMessagePrompt,
                                onValueChange = {
                                    onUpdateAssistant(
                                        assistant.copy(taMessagePrompt = it)
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                minLines = 3,
                                maxLines = 6,
                            )
                        }
                    )
                }

                // 决策时间功能说明提示词
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.ta_message_decision_prompt_label)) },
                        supportingContent = {
                            OutlinedTextField(
                                value = assistant.taMessageDecisionPrompt,
                                onValueChange = {
                                    onUpdateAssistant(
                                        assistant.copy(taMessageDecisionPrompt = it)
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                minLines = 6,
                                maxLines = 15,
                            )
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun NextTimeDisplay(
    timestamp: Long?,
    onSelectTime: (Long?) -> Unit,
    onClearTime: () -> Unit,
) {
    val context = LocalContext.current
    val calendar = remember { Calendar.getInstance() }

    // 用于暂存日期选择结果，防止取消时间选择时状态不一致
    var pendingDate by remember { mutableStateOf<Long?>(null) }

    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 当前已设置时间显示
        if (timestamp != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${dateFormat.format(Date(timestamp))} ${timeFormat.format(Date(timestamp))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = onClearTime
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.ta_message_clear_time),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.ta_message_next_time_not_set),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 选择日期按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    if (timestamp != null) {
                        calendar.timeInMillis = timestamp
                    }
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            // 暂存日期，不立即写入
                            val tempCal = Calendar.getInstance()
                            if (timestamp != null) {
                                tempCal.timeInMillis = timestamp
                            }
                            tempCal.set(Calendar.YEAR, year)
                            tempCal.set(Calendar.MONTH, month)
                            tempCal.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                            pendingDate = tempCal.timeInMillis

                            // 然后弹出时间选择器
                            TimePickerDialog(
                                context,
                                { _, hourOfDay, minute ->
                                    val finalCal = Calendar.getInstance()
                                    if (pendingDate != null) {
                                        finalCal.timeInMillis = pendingDate!!
                                    } else if (timestamp != null) {
                                        finalCal.timeInMillis = timestamp
                                    }
                                    finalCal.set(Calendar.HOUR_OF_DAY, hourOfDay)
                                    finalCal.set(Calendar.MINUTE, minute)
                                    finalCal.set(Calendar.SECOND, 0)
                                    finalCal.set(Calendar.MILLISECOND, 0)
                                    onSelectTime(finalCal.timeInMillis)
                                    pendingDate = null
                                },
                                if (timestamp != null) {
                                    val cal = Calendar.getInstance()
                                    cal.timeInMillis = timestamp
                                    cal.get(Calendar.HOUR_OF_DAY)
                                } else {
                                    calendar.get(Calendar.HOUR_OF_DAY)
                                },
                                if (timestamp != null) {
                                    val cal = Calendar.getInstance()
                                    cal.timeInMillis = timestamp
                                    cal.get(Calendar.MINUTE)
                                } else {
                                    calendar.get(Calendar.MINUTE)
                                },
                                true
                            ).apply {
                                setOnCancelListener {
                                    // 取消时间选择 → 丢弃暂存的日期
                                    pendingDate = null
                                }
                            }.show()
                        },
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH)
                    ).apply {
                        // 如果有已设置的时间，将日期选择器的默认日期设为已设置的时间
                        if (timestamp != null) {
                            val cal = Calendar.getInstance()
                            cal.timeInMillis = timestamp
                            updateDate(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                        }
                    }.show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.ta_message_select_date))
            }

            // 选择时间按钮
            OutlinedButton(
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, hourOfDay, minute ->
                            val cal = Calendar.getInstance()
                            if (timestamp != null) {
                                cal.timeInMillis = timestamp
                            }
                            cal.set(Calendar.HOUR_OF_DAY, hourOfDay)
                            cal.set(Calendar.MINUTE, minute)
                            cal.set(Calendar.SECOND, 0)
                            cal.set(Calendar.MILLISECOND, 0)
                            onSelectTime(cal.timeInMillis)
                        },
                        if (timestamp != null) {
                            val cal = Calendar.getInstance()
                            cal.timeInMillis = timestamp
                            cal.get(Calendar.HOUR_OF_DAY)
                        } else {
                            calendar.get(Calendar.HOUR_OF_DAY)
                        },
                        if (timestamp != null) {
                            val cal = Calendar.getInstance()
                            cal.timeInMillis = timestamp
                            cal.get(Calendar.MINUTE)
                        } else {
                            calendar.get(Calendar.MINUTE)
                        },
                        true
                    ).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.ta_message_select_time))
            }
        }
    }
}
