package me.rerere.rikkahub

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import me.rerere.highlight.Highlighter
import me.rerere.highlight.LocalHighlighter
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.context.LocalAnimatedVisibilityScope
import me.rerere.rikkahub.ui.context.LocalFirebaseAnalytics
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.assistant.AssistantPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailPage
import me.rerere.rikkahub.ui.pages.chat.ChatPage
import me.rerere.rikkahub.ui.pages.debug.DebugPage
import me.rerere.rikkahub.ui.pages.history.HistoryPage
import me.rerere.rikkahub.ui.pages.menu.MenuPage
import me.rerere.rikkahub.ui.pages.setting.SettingAboutPage
import me.rerere.rikkahub.ui.pages.setting.SettingDisplayPage
import me.rerere.rikkahub.ui.pages.setting.SettingMcpPage
import me.rerere.rikkahub.ui.pages.setting.SettingModelPage
import me.rerere.rikkahub.ui.pages.setting.SettingPage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderPage
import me.rerere.rikkahub.ui.pages.setting.SettingSearchPage
import me.rerere.rikkahub.ui.pages.translator.TranslatorPage
import me.rerere.rikkahub.ui.pages.webview.WebViewPage
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"

class RouteActivity : ComponentActivity() {
    private lateinit var firebaseAnalytics: FirebaseAnalytics
    private val highlighter by inject<Highlighter>()
    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsStore>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        disableNavigationBarContrast()
        super.onCreate(savedInstanceState)
        setContent {
            val navController = rememberNavController()
            RikkahubTheme {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .crossfade(true)
                        .components {
                            add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
                            add(SvgDecoder.Factory(scaleToDensity = true))
                        }
                        .build()
                }
                AppRoutes(navController)
            }
        }
        firebaseAnalytics = Firebase.analytics
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    @Composable
    fun AppRoutes(navController: NavHostController) {
        val toastState = rememberToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        SharedTransitionLayout {
            CompositionLocalProvider(
                LocalNavController provides navController,
                LocalSharedTransitionScope provides this,
                LocalSettings provides settings,
                LocalHighlighter provides highlighter,
                LocalFirebaseAnalytics provides firebaseAnalytics,
                LocalToaster provides toastState,
            ) {
                Toaster(
                    state = toastState,
                    darkTheme = LocalDarkMode.current,
                    richColors = true,
                )
                NavHost(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    navController = navController,
                    startDestination = rememberSaveable { "chat/${Uuid.random()}" },
                    enterTransition = {
                        scaleIn(initialScale = 0.35f) + fadeIn(animationSpec = tween(300))
                    },
                    exitTransition = {
                        fadeOut(animationSpec = tween(300))
                    },
                    popExitTransition = {
                        scaleOut(
                            targetScale = 0.5f,
                            transformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 0.5f)
                        ) + fadeOut()
                    },
                    popEnterTransition = {
                        EnterTransition.None
                    },
                ) {
                    composableHelper(
                        route = "chat/{id}",
                        args = listOf(
                            navArgument("id") {
                                type = NavType.StringType
                            }
                        ),
                    ) { entry ->
                        ChatPage(
                            id = Uuid.parse(entry.arguments?.getString("id")!!)
                        )
                    }

                    composableHelper("history") {
                        HistoryPage()
                    }

                    composableHelper("assistant") {
                        AssistantPage()
                    }

                    composableHelper(
                        route = "assistant/{id}",
                        args = listOf(
                            navArgument("id") {
                                type = NavType.StringType
                            }
                        ),
                    ) {
                        AssistantDetailPage()
                    }

                    composableHelper("menu") {
                        MenuPage()
                    }

                    composableHelper("translator") {
                        TranslatorPage()
                    }

                    composableHelper("setting") {
                        SettingPage()
                    }

                    composableHelper(
                        route = "webview?url={url}&content={content}",
                        args = listOf(
                            navArgument("url") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("content") {
                                type = NavType.StringType
                                defaultValue = ""
                            }
                        ),
                    ) {
                        val url = it.arguments?.getString("url") ?: ""
                        val content = it.arguments?.getString("content") ?: ""
                        WebViewPage(url, content)
                    }

                    composableHelper("setting/display") {
                        SettingDisplayPage()
                    }

                    composableHelper("setting/provider") {
                        SettingProviderPage()
                    }

                    composableHelper("setting/models") {
                        SettingModelPage()
                    }

                    composableHelper("setting/about") {
                        SettingAboutPage()
                    }

                    composableHelper("setting/search") {
                        SettingSearchPage()
                    }

                    composableHelper("setting/mcp") {
                        SettingMcpPage()
                    }

                    composableHelper("debug") {
                        DebugPage()
                    }
                }
            }
        }
    }
}


private fun NavGraphBuilder.composableHelper(
    route: String,
    args: List<NamedNavArgument> = emptyList(),
    enterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = null,
    exitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = null,
    popEnterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = null,
    popExitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = null,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) {
    this.composable(
        route = route,
        arguments = args,
        enterTransition = enterTransition,
        exitTransition = exitTransition,
        popEnterTransition = popEnterTransition,
        popExitTransition = popExitTransition,
    ) { entry ->
        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
            content(entry)
        }
    }
}