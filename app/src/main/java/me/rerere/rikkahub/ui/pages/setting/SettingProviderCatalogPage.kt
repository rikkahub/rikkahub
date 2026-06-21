package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import kotlin.uuid.Uuid
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.catalog.ProviderTemplate
import me.rerere.rikkahub.data.catalog.ProviderTemplates
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import org.koin.androidx.compose.koinViewModel

/**
 * Browse the curated provider catalog and add ONLY the providers the user picks. Adding mints a fresh,
 * user-owned provider from the template and opens its config page so the user enters the API key right
 * away; models are then fetched FROM THE PROVIDER itself on that page. We deliberately do NOT jump to a
 * model browser pre-filled from models.dev: that would show a model list even for a wrong/empty key and
 * mislead the user into thinking the connection works.
 */
@Composable
fun SettingProviderCatalogPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    var query by remember { mutableStateOf("") }

    val templates = remember(query) {
        if (query.isBlank()) ProviderTemplates.ALL
        else ProviderTemplates.ALL.filter { it.displayName.contains(query, ignoreCase = true) }
    }

    fun add(template: ProviderTemplate) {
        val provider = template.instantiate()
        vm.updateSettings(settings.copy(providers = listOf(provider) + settings.providers))
        // Go straight to the new provider's config page to enter the API key. Pop the catalog off the
        // back stack so Back from there returns to the provider list, not here. Models are fetched from
        // the provider on that page — never a models.dev list that would appear even for a wrong key.
        navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString())) {
            popUpTo(Screen.SettingProviderCatalog) { inclusive = true }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_provider_page_add_provider)) },
                navigationIcon = { BackButton() },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.setting_provider_page_search_providers)) },
                leadingIcon = { Icon(Lucide.Search, contentDescription = null) },
                singleLine = true,
                shape = CircleShape,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(templates, key = { it.displayName }) { template ->
                    CatalogProviderRow(template = template, onClick = { add(template) })
                }
            }
        }
    }
}

@Composable
private fun CatalogProviderRow(template: ProviderTemplate, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AutoAIIcon(
                name = template.displayName,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = template.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Lucide.ChevronRight, contentDescription = null)
        }
    }
}
