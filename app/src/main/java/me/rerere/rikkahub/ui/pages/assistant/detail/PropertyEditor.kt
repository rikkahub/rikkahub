package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.highlight.LocalHighlighter
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode

private val jsonLenient = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

@Composable
fun CustomHeaders(headers: List<CustomHeader>, onUpdate: (List<CustomHeader>) -> Unit) {
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.assistant_page_custom_headers))
        Spacer(Modifier.height(8.dp))

        headers.forEachIndexed { index, header ->
            var headerName by remember(header.name) { mutableStateOf(header.name) }
            var headerValue by remember(header.value) { mutableStateOf(header.value) }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = headerName,
                            onValueChange = {
                                headerName = it
                                val updatedHeaders = headers.toMutableList()
                                updatedHeaders[index] = updatedHeaders[index].copy(name = it.trim())
                                onUpdate(updatedHeaders)
                            },
                            label = { Text(stringResource(R.string.assistant_page_header_name)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = headerValue,
                            onValueChange = {
                                headerValue = it
                                val updatedHeaders = headers.toMutableList()
                                updatedHeaders[index] =
                                    updatedHeaders[index].copy(value = it.trim())
                                onUpdate(updatedHeaders)
                            },
                            label = { Text(stringResource(R.string.assistant_page_header_value)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    IconButton(onClick = {
                        val updatedHeaders = headers.toMutableList()
                        updatedHeaders.removeAt(index)
                        onUpdate(updatedHeaders)
                    }) {
                        Icon(
                            Lucide.Trash,
                            contentDescription = stringResource(R.string.assistant_page_delete_header)
                        )
                    }
                }
            }
        }

        Button(
            onClick = {
                val updatedHeaders = headers.toMutableList()
                updatedHeaders.add(CustomHeader("", ""))
                onUpdate(updatedHeaders)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Lucide.Plus, contentDescription = stringResource(R.string.assistant_page_add_header))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.assistant_page_add_header))
        }
    }
}

@Composable
fun CustomBodies(customBodies: List<CustomBody>, onUpdate: (List<CustomBody>) -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.assistant_page_custom_bodies))
        Spacer(Modifier.height(8.dp))

        customBodies.forEachIndexed { index, body ->
            var bodyKey by remember(body.key) { mutableStateOf(body.key) }
            var bodyValueString by remember(body.value) {
                mutableStateOf(jsonLenient.encodeToString(JsonElement.serializer(), body.value))
            }
            var jsonParseError by remember { mutableStateOf<String?>(null) }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = bodyKey,
                            onValueChange = {
                                bodyKey = it
                                val updatedBodies = customBodies.toMutableList()
                                updatedBodies[index] = updatedBodies[index].copy(key = it.trim())
                                onUpdate(updatedBodies)
                            },
                            label = { Text(stringResource(R.string.assistant_page_body_key)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = bodyValueString,
                            onValueChange = { newString ->
                                bodyValueString = newString
                                try {
                                    val newJsonValue = jsonLenient.parseToJsonElement(newString)
                                    val updatedBodies = customBodies.toMutableList()
                                    updatedBodies[index] =
                                        updatedBodies[index].copy(value = newJsonValue)
                                    onUpdate(updatedBodies)
                                    jsonParseError = null // Clear error on successful parse
                                } catch (e: Exception) { // Catching general Exception, JsonException is common here
                                    jsonParseError =
                                        context.getString(
                                            R.string.assistant_page_invalid_json,
                                            e.message?.take(100) ?: ""
                                        ) // Truncate for very long messages
                                }
                            },
                            label = { Text(stringResource(R.string.assistant_page_body_value)) },
                            modifier = Modifier.fillMaxWidth(),
                            isError = jsonParseError != null,
                            supportingText = {
                                if (jsonParseError != null) {
                                    Text(jsonParseError!!)
                                }
                            },
                            minLines = 3,
                            maxLines = 5,
                            visualTransformation = HighlightCodeVisualTransformation(
                                language = "json",
                                highlighter = LocalHighlighter.current,
                                darkMode = LocalDarkMode.current
                            ),
                            textStyle = LocalTextStyle.current.merge(fontFamily = JetbrainsMono),
                        )
                    }
                    IconButton(onClick = {
                        val updatedBodies = customBodies.toMutableList()
                        updatedBodies.removeAt(index)
                        onUpdate(updatedBodies)
                    }) {
                        Icon(
                            Lucide.Trash,
                            contentDescription = stringResource(R.string.assistant_page_delete_body)
                        )
                    }
                }
            }
        }

        Button(
            onClick = {
                val updatedBodies = customBodies.toMutableList()
                updatedBodies.add(CustomBody("", JsonPrimitive("")))
                onUpdate(updatedBodies)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Lucide.Plus, contentDescription = stringResource(R.string.assistant_page_add_body))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.assistant_page_add_body))
        }
    }
}
