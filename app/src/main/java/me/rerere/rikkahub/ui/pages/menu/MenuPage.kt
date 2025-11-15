package me.rerere.rikkahub.ui.pages.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ui.Greeting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import okhttp3.HttpUrl.Companion.toHttpUrl

@Composable
fun MenuPage() {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    BackButton()
                },
                title = {},
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = it + PaddingValues(16.dp)
        ) {
            item {
                Greeting(modifier = Modifier.padding(vertical = 32.dp))
            }

            item {
                FeaturesSection()
            }

            item {
                LeaderBoard()
            }
        }
    }
}

@Composable
private fun FeaturesSection() {
    val navController = LocalNavController.current

    @Composable
    fun FeatureCard(
        title: @Composable () -> Unit,
        image: @Composable () -> Unit,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable { onClick() }
                .height(150.dp)
                .wrapContentHeight()
                .fillMaxWidth()
        ) {
            image()
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                ProvideTextStyle(MaterialTheme.typography.headlineSmall.copy(color = Color.White)) {
                    title()
                }
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
    ) {
        FeatureCard(
            title = {
                Text(stringResource(id = R.string.menu_page_ai_translator))
            },
            image = {
                AsyncImage(
                    model = "file:///android_asset/banner/banner-1.png",
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            },
        ) {
            navController.navigate(Screen.Translator)
        }
        FeatureCard(
            title = {
                Text(stringResource(id = R.string.menu_page_image_generation))
            },
            image = {
                AsyncImage(
                    model = "file:///android_asset/banner/banner-3.png",
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            },
        ) {
            navController.navigate(Screen.ImageGen)
        }
//        FeatureCard(
//            title = {
//                Text(stringResource(id = R.string.menu_page_knowledge_base))
//            },
//            image = {
//                AsyncImage(
//                    model = "file:///android_asset/banner/banner-2.png",
//                    contentDescription = null,
//                    modifier = Modifier.fillMaxSize(),
//                    contentScale = ContentScale.Crop
//                )
//            },
//        ) {
//            // navController.push(Screen.Library)
//        }
    }
}

@Composable
private fun LeaderBoard() {
    val context = LocalContext.current

    @Composable
    fun LeaderBoardItem(
        url: String,
        name: String,
        modifier: Modifier = Modifier
    ) {
        Card(
            onClick = {
                context.openUrl(url)
            },
            modifier = modifier.widthIn(min = 150.dp)
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Favicon(
                    url = url,
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = url.toHttpUrl().host,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.75f)
                )
            }
        }
    }

    Column {
        Text(
            text = stringResource(id = R.string.menu_page_llm_leaderboard),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 16.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LeaderBoardItem(
                url = "https://lmarena.ai/leaderboard",
                name = "LMArena",
                modifier = Modifier.weight(1f),
            )

            LeaderBoardItem(
                url = "https://artificialanalysis.ai/leaderboards/models",
                name = "ArtificialAnalysis",
                modifier = Modifier.weight(1f),
            )
        }
    }
}
