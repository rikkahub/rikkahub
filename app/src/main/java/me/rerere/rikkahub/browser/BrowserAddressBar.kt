package me.rerere.rikkahub.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R

/**
 * Top app bar for [BrowserActivity]. Editable URL/search bar + history nav arrows +
 * refresh + close + kebab.
 *
 * The title slot is an editable [TextField] so the user can navigate manually before
 * (or instead of) handing control to the AI. Submitting the field invokes [onNavigate]
 * with the raw query — the caller is responsible for normalisation (add scheme, switch
 * to a search query if no dot, etc.) so the search engine pref lives in one place.
 *
 * Tap the field to edit; the text auto-selects on focus so the user can replace the
 * existing URL with one keystroke. Tap outside (or trigger IME-Go) to commit and
 * dismiss the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserAddressBar(
    url: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onStopAi: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var fieldValue by remember(url) {
        mutableStateOf(TextFieldValue(text = url, selection = TextRange(url.length)))
    }
    var hasFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Sync external URL updates into the field ONLY while it's not being edited; once the
    // user is typing we don't want a navigation event to clobber their query mid-type.
    LaunchedEffect(url) {
        if (!hasFocus && url != fieldValue.text) {
            fieldValue = TextFieldValue(text = url, selection = TextRange(url.length))
        }
    }

    TopAppBar(
        title = {
            TextField(
                value = fieldValue,
                onValueChange = { fieldValue = it },
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.browser_address_bar_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        val q = fieldValue.text.trim()
                        if (q.isNotEmpty()) {
                            onNavigate(q)
                            keyboard?.hide()
                        }
                    }
                ),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        val nowFocused = state.isFocused
                        if (nowFocused && !hasFocus) {
                            // Select-all on focus so the user replaces existing URL in one keystroke.
                            fieldValue = fieldValue.copy(
                                selection = TextRange(0, fieldValue.text.length)
                            )
                        }
                        hasFocus = nowFocused
                    },
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.browser_address_bar_close),
                )
            }
        },
        actions = {
            IconButton(onClick = onBack, enabled = canGoBack) {
                Icon(
                    imageVector = HugeIcons.ArrowLeft01,
                    contentDescription = stringResource(R.string.browser_address_bar_back),
                )
            }
            IconButton(onClick = onForward, enabled = canGoForward) {
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = stringResource(R.string.browser_address_bar_forward),
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = HugeIcons.Refresh01,
                    contentDescription = stringResource(R.string.browser_address_bar_refresh),
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.browser_address_bar_more),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_address_bar_stop_ai)) },
                        onClick = {
                            menuExpanded = false
                            onStopAi()
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.padding(horizontal = 0.dp),
    )
}

/**
 * Normalise raw user input into a navigable URL.
 *
 *  - already absolute (`http://`, `https://`, `file://`, `about:`, `data:`) → unchanged
 *  - looks like a host (`example.com`, `foo.bar/baz`, `192.168.1.1:8080`) → prepend `https://`
 *  - everything else (no dot, no colon, has a space) → search query via DuckDuckGo
 *
 * Search engine is hardcoded to DuckDuckGo in v1 — the spec's pref dropdown is a no-op
 * stub for v1.5. Wire through `BrowserPreferences` once Pass 2 lands the read path.
 */
fun normalizeBrowserQuery(raw: String): String {
    val q = raw.trim()
    if (q.isEmpty()) return "about:blank"
    val lower = q.lowercase()
    if (lower.startsWith("http://")
        || lower.startsWith("https://")
        || lower.startsWith("file://")
        || lower.startsWith("about:")
        || lower.startsWith("data:")
    ) return q
    // Heuristic: if it contains a dot OR a colon (port/scheme), treat as URL.
    val looksLikeHost = (q.contains('.') || q.contains(':')) && !q.contains(' ')
    return if (looksLikeHost) "https://$q" else "https://duckduckgo.com/?q=${java.net.URLEncoder.encode(q, "UTF-8")}"
}
