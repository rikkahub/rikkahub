package me.rerere.rikkahub.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import me.rerere.highlight.Highlighter
import me.rerere.highlight.LocalHighlighter
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.textselection.TextSelectionSheet
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class TextSelectionActivity : ComponentActivity() {
    private val highlighter by inject<Highlighter>()
    private val settingsStore by inject<SettingsStore>()
    private val viewModel: TextSelectionVM by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val selectedText = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty().trim()
        if (selectedText.isBlank()) {
            finish()
            return
        }
        viewModel.updateSelectedText(selectedText)

        setContent {
            val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
            val toasterState = rememberToasterState()

            RikkahubTheme {
                CompositionLocalProvider(
                    LocalSettings provides settings,
                    LocalHighlighter provides highlighter,
                    LocalToaster provides toasterState,
                ) {
                    TextSelectionSheet(
                        viewModel = viewModel,
                        onDismiss = { finish() },
                        onContinueInApp = {
                            val routeIntent = Intent(this@TextSelectionActivity, RouteActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra("continue_conversation", true)
                                putExtra("selected_text", viewModel.selectedText)
                                settings.textSelectionConfig.assistantId?.let {
                                    putExtra("selection_assistant_id", it.toString())
                                }
                                viewModel.lastUserPrompt?.let {
                                    putExtra("user_prompt", it)
                                }
                                val resultState = viewModel.state
                                if (resultState is TextSelectionState.Result && resultState.responseText.isNotBlank()) {
                                    putExtra("ai_response", resultState.responseText)
                                }
                            }
                            startActivity(routeIntent)
                            finish()
                        },
                    )
                    Toaster(
                        state = toasterState,
                        darkTheme = LocalDarkMode.current,
                        richColors = true,
                        alignment = Alignment.TopCenter,
                        showCloseButton = true,
                    )
                }
            }
        }
    }
}
