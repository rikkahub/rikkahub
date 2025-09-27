package me.rerere.rikkahub.ui.pages.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Logs
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.AILogging
import org.koin.androidx.compose.koinViewModel

@Composable
fun DeveloperPage(vm: DeveloperVM = koinViewModel()) {
    val pager = rememberPagerState { 1 }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Developer Page",
                        maxLines = 1,
                    )
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                NavigationBarItem(
                    selected = pager.currentPage == 0,
                    onClick = { scope.launch { pager.animateScrollToPage(0) } },
                    label = {
                        Text(text = "Developer")
                    },
                    icon = {
                        Icon(Lucide.Logs, null)
                    }
                )
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pager,
            contentPadding = innerPadding
        ) { page ->
            when (page) {
                0 -> {
                    LoggingPaging(vm = vm)
                }
            }
        }
    }
}

@Composable
fun LoggingPaging(vm: DeveloperVM) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(logs) { log ->
            when (log) {
                is AILogging.Generation -> {
                    Card {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {

                        }
                    }
                }
            }
        }
    }
}
