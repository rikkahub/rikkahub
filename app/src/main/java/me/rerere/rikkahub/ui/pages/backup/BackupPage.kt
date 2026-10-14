package me.rerere.rikkahub.ui.pages.backup

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bucket
import me.rerere.hugeicons.stroke.CloudServer
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.pages.backup.components.BackupDialog
import me.rerere.rikkahub.ui.pages.backup.tabs.ImportExportTab
import me.rerere.rikkahub.ui.pages.backup.tabs.ReminderTab
import me.rerere.rikkahub.ui.pages.backup.tabs.S3Tab
import me.rerere.rikkahub.ui.pages.backup.tabs.WebDavTab
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

private enum class BackupTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    LOCAL(HugeIcons.SmartPhone01, R.string.backup_page_import_export),
    WEBDAV(HugeIcons.CloudServer, R.string.backup_page_webdav_backup),
    S3(HugeIcons.Bucket, R.string.backup_page_s3_backup),
    REMINDER(HugeIcons.Notification01, R.string.backup_page_reminder);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun BackupPage(vm: BackupVM = koinViewModel()) {
    val pagerState = rememberPagerState { BackupTab.entries.size }
    var showRestartDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.backup_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding)
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = BackupTab.entries,
                modifier = Modifier.padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (BackupTab.entries[page]) {
                    BackupTab.LOCAL -> {
                        ImportExportTab(
                            vm = vm,
                            onShowRestartDialog = { showRestartDialog = true }
                        )
                    }

                    BackupTab.WEBDAV -> {
                        WebDavTab(
                            vm = vm,
                            onShowRestartDialog = { showRestartDialog = true }
                        )
                    }

                    BackupTab.S3 -> {
                        S3Tab(
                            vm = vm,
                            onShowRestartDialog = { showRestartDialog = true }
                        )
                    }

                    BackupTab.REMINDER -> {
                        ReminderTab(vm = vm)
                    }
                }
            }
        }
    }

    if (showRestartDialog) {
        BackupDialog()
    }
}
