package me.rerere.rikkahub.ui.pages.backup

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.DatabaseBackup
import com.composables.icons.lucide.Import
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.backup.components.BackupDialog
import me.rerere.rikkahub.ui.pages.backup.tabs.ImportExportTab
import me.rerere.rikkahub.ui.pages.backup.tabs.S3Tab
import me.rerere.rikkahub.ui.pages.backup.tabs.WebDavTab
import org.koin.androidx.compose.koinViewModel

@Composable
fun BackupPage(vm: BackupVM = koinViewModel()) {
    val pagerState = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    var showRestartDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.backup_page_title))
                },
                navigationIcon = {
                    BackButton()
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    icon = {
                        Icon(Lucide.DatabaseBackup, null)
                    },
                    label = {
                        Text(stringResource(R.string.backup_page_webdav_backup))
                    },
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(0) }
                    },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    icon = {
                        Icon(Lucide.Cloud, null)
                    },
                    label = {
                        Text(stringResource(R.string.backup_page_s3_backup))
                    },
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(1) }
                    },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    icon = {
                        Icon(Lucide.Import, null)
                    },
                    label = {
                        Text(stringResource(R.string.backup_page_import_export))
                    },
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(2) }
                    },
                )
            }
        }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = it
        ) { page ->
            when (page) {
                0 -> {
                    WebDavTab(
                        vm = vm,
                        onShowRestartDialog = { showRestartDialog = true }
                    )
                }

                1 -> {
                    S3Tab(
                        vm = vm,
                        onShowRestartDialog = { showRestartDialog = true }
                    )
                }

                2 -> {
                    ImportExportTab(
                        vm = vm,
                        onShowRestartDialog = { showRestartDialog = true }
                    )
                }
            }
        }
    }

    if (showRestartDialog) {
        BackupDialog()
    }
}
