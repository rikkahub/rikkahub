package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.api.SponsorAPI
import me.rerere.rikkahub.data.model.Sponsor
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.onError
import me.rerere.rikkahub.utils.onLoading
import me.rerere.rikkahub.utils.onSuccess
import me.rerere.rikkahub.utils.openUrl
import org.koin.compose.koinInject

@Composable
fun SettingDonatePage() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.donate_page_title))
                },
                navigationIcon = {
                    BackButton()
                }
            )
        }
    ) { paddings ->
        Column(
            modifier = Modifier
                .padding(paddings)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.donate_page_donation_methods),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Kofi()
            Afdian()

            Text(
                text = stringResource(R.string.donate_page_sponsor_list),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Sponsors(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun Kofi() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        onClick = {
            context.openUrl("https://ko-fi.com/reovodev")
        }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AsyncImage(
                model = R.drawable.kofi,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Kofi",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.donate_page_kofi_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Afdian() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        onClick = {
            context.openUrl("https://afdian.com/a/reovo")
        }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.afdian),
                contentDescription = null
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "爱发电",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.donate_page_afdian_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Sponsors(modifier: Modifier = Modifier) {
    val sponsorAPI = koinInject<SponsorAPI>()
    val sponsors by produceState<UiState<List<Sponsor>>>(UiState.Idle) {
        value = UiState.Loading
        runCatching {
            val sponsors = sponsorAPI.getSponsors()
            println(sponsors)
            value = UiState.Success(sponsors)
        }.onFailure {
            value = UiState.Error(it)
        }
    }
    Box(
        modifier = modifier
    ) {
        sponsors.onSuccess { value ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 48.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(value) {
                    Surface {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AsyncImage(
                                model = it.avatar,
                                contentDescription = null,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .size(48.dp)
                            )
                            Text(
                                text = it.userName,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }.onLoading {
            CircularWavyProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }.onError {
            Text(
                text = it.message ?: it.javaClass.simpleName,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

