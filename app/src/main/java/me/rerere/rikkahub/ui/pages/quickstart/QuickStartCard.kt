package me.rerere.rikkahub.ui.pages.quickstart

import me.rerere.rikkahub.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController

/** 还没有可用模型时放在聊天列表里，引导用户去快速入门页 */
@Composable
fun QuickStartCard(modifier: Modifier = Modifier) {
    val navController = LocalNavController.current
    QuickStartHeroCard(
        title = stringResource(R.string.quick_start_card_title),
        description = stringResource(R.string.quick_start_card_desc),
        icon = {
            Icon(
                imageVector = HugeIcons.Rocket01,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        },
        modifier = modifier,
    ) {
        Button(
            onClick = { navController.navigate(Screen.QuickStart) },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.quick_start_page_title))
        }
    }
}
