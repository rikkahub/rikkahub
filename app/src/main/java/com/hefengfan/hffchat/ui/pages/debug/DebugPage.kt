package com.hefengfan.hffchat.ui.pages.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.common.android.Logging
import com.hefengfan.hffchat.data.model.Avatar
import com.hefengfan.hffchat.ui.components.ui.UIAvatar
import com.hefengfan.hffchat.ui.components.nav.BackButton
import com.hefengfan.hffchat.ui.components.richtext.MarkdownBlock
import com.hefengfan.hffchat.ui.components.richtext.MathBlock
import com.hefengfan.hffchat.ui.components.richtext.Mermaid
import com.hefengfan.hffchat.ui.context.LocalSettings
import com.hefengfan.hffchat.ui.context.LocalToaster
import com.hefengfan.hffchat.ui.theme.JetbrainsMono
import org.koin.androidx.compose.koinViewModel
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.uuid.Uuid

@Composable
fun DebugPage(vm: DebugVM = koinViewModel()) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Debug Mode")
                },
                navigationIcon = {
                    BackButton()
                }
            )
        }
    ) { contentPadding ->
        val state = rememberPagerState { 2 }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            SecondaryTabRow(
                selectedTabIndex = state.currentPage,
            ) {
                Tab(
                    selected = state.currentPage == 0,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(0)
                        }
                    },
                    text = {
                        Text("Main")
                    }
                )
                Tab(
                    selected = state.currentPage == 1,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(1)
                        }
                    },
                    text = {
                        Text("Logging")
                    }
                )
            }
            HorizontalPager(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> MainPage(vm)
                    1 -> Box {}
                }
            }
        }
    }
}

@Composable
private fun MainPage(vm: DebugVM) {
    val settings = LocalSettings.current
    Column(
        modifier = Modifier
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        var avatar: Avatar by remember { mutableStateOf(Avatar.Emoji("😎")) }
        UIAvatar(
            value = avatar,
            onUpdate = {
                println("Avatar updated: $it")
                avatar = it
            },
            name = "A"
        )
        Mermaid(
            code = """
                mindmap
                  root((mindmap))
                    Origins
                      Long history
                      ::icon(fa fa-book)
                      Popularisation
                        British popular psychology author Tony Buzan
                    Research
                      On effectiveness<br/>and features
                      On Automatic creation
                        Uses
                            Creative techniques
                            Strategic planning
                            Argument mapping
                    Tools
                      Pen and paper
                      Mermaid
                """.trimIndent(),
            modifier = Modifier.fillMaxWidth(),
        )

        var counter by remember {
            mutableIntStateOf(0)
        }
        val toaster = LocalToaster.current
        Button(
            onClick = {
                toaster.show("测试 ${counter++}")
                toaster.show("测试 ${counter++}", type = ToastType.Info)
                toaster.show("测试 ${counter++}", type = ToastType.Error)
            }
        ) {
            Text("toast")
        }
        Button(
            onClick = {
                vm.updateSettings(
                    settings.copy(
                        chatModelId = Uuid.random()
                    )
                )
            }
        ) {
            Text("重置Chat模型")
        }

        Button(
            onClick = {
                error("测试崩溃 ${Random.nextInt(0..1000)}")
            }
        ) {
            Text("崩溃")
        }

        Button(
            onClick = {
                vm.createOversizedConversation(30)
                toaster.show("正在创建 30MB 超大对话...")
            }
        ) {
            Text("创建超大对话 (30MB)")
        }

        Button(
            onClick = {
                vm.createConversationWithMessages(1024)
                toaster.show("正在创建 1024 条消息对话...")
            }
        ) {
            Text("创建 1024 个消息的聊天")
        }

        HorizontalDivider()

        Text("Launch Stats", style = MaterialTheme.typography.labelMedium)

        var launchCountInput by remember(settings.launchCount) {
            mutableStateOf(settings.launchCount.toString())
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = launchCountInput,
                onValueChange = { launchCountInput = it },
                label = { Text("launchCount (current: ${settings.launchCount})") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = {
                launchCountInput.toIntOrNull()?.let {
                    vm.updateSettings(settings.copy(launchCount = it))
                }
            }) {
                Text("Set")
            }
        }

        var dismissedAtInput by remember(settings.sponsorAlertDismissedAt) {
            mutableStateOf(settings.sponsorAlertDismissedAt.toString())
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = dismissedAtInput,
                onValueChange = { dismissedAtInput = it },
                label = { Text("sponsorAlertDismissedAt (current: ${settings.sponsorAlertDismissedAt})") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = {
                dismissedAtInput.toIntOrNull()?.let {
                    vm.updateSettings(settings.copy(sponsorAlertDismissedAt = it))
                }
            }) {
                Text("Set")
            }
        }

        var markdown by remember { mutableStateOf("") }
        MarkdownBlock(markdown, modifier = Modifier.fillMaxWidth())
        MathBlock(markdown)
        OutlinedTextField(
            value = markdown,
            onValueChange = { markdown = it },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
