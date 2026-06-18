package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.ai.runtime.hooks.GuardrailMode
import me.rerere.ai.runtime.hooks.HookConfig
import me.rerere.ai.runtime.hooks.HookEvent
import me.rerere.ai.runtime.hooks.HookHandler
import me.rerere.ai.runtime.hooks.HookMatcher
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.UI_GLOBAL_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.UI_OBSERVE_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.UI_SCROLL_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.UI_SET_TEXT_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.UI_TAP_TOOL_NAME
import me.rerere.rikkahub.data.InstalledPackageInfo
import me.rerere.rikkahub.data.InstalledPackageSource
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AutomationGrant
import me.rerere.rikkahub.data.model.AutomationSink
import me.rerere.rikkahub.data.model.AutomationVerb
import me.rerere.rikkahub.service.automation.AutomationRuntimeRegistry
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.FormTextField
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

// ---------------------------------------------------------------------------------------------
// Pure scope-editor logic (unit-tested on the JVM; the composables below are a thin shell).
//
// The grant only ever *narrows* the kernel Capability — these helpers encode the editor invariants
// (additive/idempotent verb & sink toggles, blank/dup-free package set, SUBMIT never grantable from
// this surface, conservative TTL/steps defaults on first enable) so the page never has to.
// ---------------------------------------------------------------------------------------------

/** Conservative default lease length applied when the user first enables a grant (zero ⇒ deny). */
internal const val DEFAULT_TTL_MINUTES: Int = 5

/** Conservative default admit budget applied when the user first enables a grant (zero ⇒ deny). */
internal const val DEFAULT_MAX_STEPS: Int = 50

/**
 * The sinks this editor may grant. `SUBMIT` is deliberately absent: submit-class automation stays
 * the stricter separate opt-in the kernel withholds, so it is never reachable from this surface.
 */
internal val EDITABLE_SINKS: List<AutomationSink> =
    listOf(AutomationSink.TYPE_INTO, AutomationSink.GLOBAL_NAV)

/**
 * Flip the master switch. Enabling a grant that has never carried a lease seeds the conservative
 * TTL/steps defaults (a zero TTL or zero steps derives DENY, so an un-set enabled grant would be a
 * silent no-op); user-set values are preserved. Disabling never wipes the configured scope.
 */
internal fun AutomationGrant.withEnabled(enabled: Boolean): AutomationGrant = copy(
    enabled = enabled,
    ttlMinutes = if (enabled && ttlMinutes <= 0) DEFAULT_TTL_MINUTES else ttlMinutes,
    maxSteps = if (enabled && maxSteps <= 0) DEFAULT_MAX_STEPS else maxSteps,
)

internal fun AutomationGrant.withVerb(verb: AutomationVerb, granted: Boolean): AutomationGrant =
    copy(verbs = if (granted) verbs + verb else verbs - verb)

/**
 * Toggle a sink. `SUBMIT` can never be added here regardless of the caller — the editor surface
 * never offers it and this guard keeps the invariant true even if a future caller passes it.
 */
internal fun AutomationGrant.withSink(sink: AutomationSink, granted: Boolean): AutomationGrant {
    if (sink == AutomationSink.SUBMIT) return copy(sinks = sinks - AutomationSink.SUBMIT)
    return copy(sinks = if (granted) sinks + sink else sinks - sink)
}

/** Add a package, trimmed; blanks are dropped and a `Set` de-dupes silently. */
internal fun AutomationGrant.withAddedPackage(pkg: String): AutomationGrant {
    val trimmed = pkg.trim()
    if (trimmed.isEmpty()) return this
    return copy(allowedPackages = allowedPackages + trimmed)
}

internal fun AutomationGrant.withRemovedPackage(pkg: String): AutomationGrant =
    copy(allowedPackages = allowedPackages - pkg)

/** Clamp a TTL/steps text field to a non-negative int; non-numeric input keeps the prior value. */
internal fun parsePositiveIntOrNull(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it >= 0 }

// ---------------------------------------------------------------------------------------------
// PreToolUse guardrail over the high-risk device/automation tools (M5).
//
// The guardrail is a MANAGED PreToolUse hook entry, not a new hook event: the existing
// `applyPreToolUseHooks` gate already maps a `Deny`/`Ask` decision onto a fresh tool call before it
// runs. The toggle ensures/removes ONE matcher whose `matcher` regex covers the high-risk tool names
// and whose handler is the deterministic `HookHandler.Static` (no LLM round-trip). The matcher is
// identified by its exact regex pattern so ensure/remove are idempotent and never disturb the user's
// own authored hooks.
// ---------------------------------------------------------------------------------------------

/**
 * The high-risk device/automation tools the guardrail gates: the two app-launch tools plus every
 * accessibility verb. These are the tools that touch other apps' screens or launch apps — the ones a
 * user wants a per-call confirmation (or an outright block) over.
 */
internal val HIGH_RISK_TOOL_NAMES: List<String> = listOf(
    "open_app",
    "list_app",
    UI_OBSERVE_TOOL_NAME,
    UI_SCROLL_TOOL_NAME,
    UI_GLOBAL_TOOL_NAME,
    UI_SET_TEXT_TOOL_NAME,
    UI_TAP_TOOL_NAME,
)

/**
 * The anchored alternation the managed guardrail matcher carries. `matches()` treats a non-null
 * matcher as exact-name OR full-regex match, so an anchored alternation matches exactly the high-risk
 * names and nothing else. This exact string is the matcher's identity — `withoutGuardrail` removes the
 * entry by matching it, so the managed entry round-trips without a separate marker field.
 */
internal val HIGH_RISK_GUARDRAIL_MATCHER: String =
    HIGH_RISK_TOOL_NAMES.joinToString(prefix = "^(", separator = "|", postfix = ")$")

private fun HookMatcher.isManagedGuardrail(): Boolean =
    matcher == HIGH_RISK_GUARDRAIL_MATCHER &&
        handlers.size == 1 &&
        handlers.single() is HookHandler.Static

/** The guardrail's current mode, or null when no managed guardrail entry is present (disabled). */
internal fun HookConfig.guardrailMode(): GuardrailMode? =
    hooks[HookEvent.PreToolUse].orEmpty()
        .firstOrNull { it.isManagedGuardrail() }
        ?.handlers
        ?.filterIsInstance<HookHandler.Static>()
        ?.firstOrNull()
        ?.mode

/**
 * Ensure exactly one managed guardrail matcher over the high-risk tools at [mode]. Idempotent:
 * re-applying replaces the prior managed entry (so flipping Deny<->Ask never stacks duplicates) and
 * leaves every user-authored hook untouched. Enabling a managed guardrail grants trust — the user IS
 * the author of this toggle — which is also required for the dispatcher to actually run it.
 *
 * The `require(!requiresTrustReview())` guard mirrors `withAuthoredHook`: granting trust must never
 * piggyback onto unreviewed imported hooks (H4). Without it, enabling the guardrail on an assistant
 * that carries quarantined imported LLM hooks would flip `trusted=true` and un-quarantine them all on
 * the next turn. The page must surface the import-trust review flow before offering this toggle.
 */
internal fun HookConfig.withGuardrail(mode: GuardrailMode): HookConfig {
    require(!requiresTrustReview()) { "imported hooks must be reviewed before enabling the guardrail" }
    val entry = HookMatcher(
        matcher = HIGH_RISK_GUARDRAIL_MATCHER,
        handlers = listOf(HookHandler.Static(mode = mode)),
    )
    val preserved = hooks[HookEvent.PreToolUse].orEmpty().filterNot { it.isManagedGuardrail() }
    return copy(
        hooks = hooks + (HookEvent.PreToolUse to (preserved + entry)),
        trusted = true,
    )
}

/** Remove the managed guardrail entry, leaving every user-authored hook untouched. */
internal fun HookConfig.withoutGuardrail(): HookConfig {
    val remaining = hooks[HookEvent.PreToolUse].orEmpty().filterNot { it.isManagedGuardrail() }
    val nextHooks = if (remaining.isEmpty()) {
        hooks - HookEvent.PreToolUse
    } else {
        hooks + (HookEvent.PreToolUse to remaining)
    }
    return copy(hooks = nextHooks)
}

// ---------------------------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------------------------

@Composable
fun AssistantAutomationPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val registry: AutomationRuntimeRegistry = koinInject()
    val a11yEnabled = registry.backend() != null
    val yoloAcknowledged = LocalSettings.current.displaySetting.automationYoloAcknowledged
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_automation_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AutomationScopeContent(
            modifier = Modifier.padding(innerPadding),
            grant = assistant.automationGrant,
            hooks = assistant.hooks,
            a11yEnabled = a11yEnabled,
            yoloAcknowledged = yoloAcknowledged,
            externalKey = assistant.id,
            onUpdate = { vm.update(assistant.copy(automationGrant = it)) },
            onUpdateHooks = { vm.update(assistant.copy(hooks = it)) },
            onAcknowledgeYolo = { vm.setAutomationYoloAcknowledged(true) },
        )
    }
}

@Composable
private fun AutomationScopeContent(
    modifier: Modifier = Modifier,
    grant: AutomationGrant,
    hooks: HookConfig,
    a11yEnabled: Boolean,
    yoloAcknowledged: Boolean,
    externalKey: Any,
    onUpdate: (AutomationGrant) -> Unit,
    onUpdateHooks: (HookConfig) -> Unit,
    onAcknowledgeYolo: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AccessibilityStatusCard(a11yEnabled = a11yEnabled)

        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_automation_enable_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_automation_enable_desc))
                },
                trailingContent = {
                    Switch(
                        checked = grant.enabled,
                        onCheckedChange = { onUpdate(grant.withEnabled(it)) },
                    )
                },
            )
        }

        VerbSwitches(grant = grant, onUpdate = onUpdate)

        SinkSwitches(grant = grant, onUpdate = onUpdate)

        AllowedPackagesEditor(grant = grant, onUpdate = onUpdate)

        LeaseLimitsEditor(grant = grant, externalKey = externalKey, onUpdate = onUpdate)

        GuardrailEditor(hooks = hooks, onUpdate = onUpdateHooks)

        // YOLO "bypass all restriction" danger zone. Flavor-gated: the sideload build renders the
        // toggle + danger-consent dialog + warning banner; the Play build renders nothing (the
        // unrestricted surface is sideload-only, mirroring the workspace shell security boundary).
        AutomationYoloSection(
            grant = grant,
            yoloAcknowledged = yoloAcknowledged,
            onUpdate = onUpdate,
            onAcknowledge = onAcknowledgeYolo,
        )
    }
}

/**
 * The PreToolUse guardrail toggle. Enabling it ensures a managed deny/ask matcher over the high-risk
 * device/automation tools (`open_app`, `list_app`, `ui_*`); the mode picker selects whether a matching
 * call is held for confirmation (Ask, the conservative default) or blocked outright (Deny). The gate
 * is the existing `applyPreToolUseHooks` fire-point — no new hook event.
 */
@Composable
private fun GuardrailEditor(hooks: HookConfig, onUpdate: (HookConfig) -> Unit) {
    val mode = hooks.guardrailMode()
    // H4: enabling the guardrail grants trust, so it must never be offered while imported hooks await
    // review — surface the review pointer instead of the (now `require`-guarded) toggle.
    if (hooks.requiresTrustReview()) {
        GuardrailNeedsReviewCard()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.assistant_page_automation_guardrail_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_automation_guardrail_enable_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_automation_guardrail_enable_desc))
                },
                trailingContent = {
                    Switch(
                        checked = mode != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                if (enabled) hooks.withGuardrail(GuardrailMode.ASK) else hooks.withoutGuardrail()
                            )
                        },
                    )
                },
            )
        }
        if (mode != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == GuardrailMode.ASK,
                    onClick = { onUpdate(hooks.withGuardrail(GuardrailMode.ASK)) },
                    label = { Text(stringResource(R.string.assistant_page_automation_guardrail_mode_ask)) },
                )
                FilterChip(
                    selected = mode == GuardrailMode.DENY,
                    onClick = { onUpdate(hooks.withGuardrail(GuardrailMode.DENY)) },
                    label = { Text(stringResource(R.string.assistant_page_automation_guardrail_mode_deny)) },
                )
            }
            Text(
                text = stringResource(
                    if (mode == GuardrailMode.ASK) {
                        R.string.assistant_page_automation_guardrail_mode_ask_desc
                    } else {
                        R.string.assistant_page_automation_guardrail_mode_deny_desc
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shown in place of the guardrail toggle while imported hooks await review (H4). Enabling the
 * guardrail grants trust, so the toggle is withheld until the user reviews and trusts the imported
 * hooks on the Hooks page — keeping the trust grant scoped to the user's own authored config.
 */
@Composable
private fun GuardrailNeedsReviewCard() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.assistant_page_automation_guardrail_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = HugeIcons.AlertCircle, contentDescription = null)
                Text(
                    text = stringResource(R.string.assistant_page_automation_guardrail_needs_review),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AccessibilityStatusCard(a11yEnabled: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (a11yEnabled) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
            contentColor = if (a11yEnabled) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (a11yEnabled) HugeIcons.CheckmarkCircle01 else HugeIcons.AlertCircle,
                contentDescription = null,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(
                        if (a11yEnabled) {
                            R.string.assistant_page_automation_a11y_on_title
                        } else {
                            R.string.assistant_page_automation_a11y_off_title
                        }
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(
                        if (a11yEnabled) {
                            R.string.assistant_page_automation_a11y_on_desc
                        } else {
                            R.string.assistant_page_automation_a11y_off_desc
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun VerbSwitches(grant: AutomationGrant, onUpdate: (AutomationGrant) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.assistant_page_automation_verbs_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        CardGroup {
            AutomationVerb.entries.forEach { verb ->
                item(
                    headlineContent = { Text(verbLabel(verb)) },
                    supportingContent = { Text(verbDescription(verb)) },
                    trailingContent = {
                        Switch(
                            checked = grant.verbs.contains(verb),
                            onCheckedChange = { onUpdate(grant.withVerb(verb, it)) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SinkSwitches(grant: AutomationGrant, onUpdate: (AutomationGrant) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.assistant_page_automation_sinks_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        CardGroup {
            EDITABLE_SINKS.forEach { sink ->
                item(
                    headlineContent = { Text(sinkLabel(sink)) },
                    trailingContent = {
                        Switch(
                            checked = grant.sinks.contains(sink),
                            onCheckedChange = { onUpdate(grant.withSink(sink, it)) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun AllowedPackagesEditor(grant: AutomationGrant, onUpdate: (AutomationGrant) -> Unit) {
    var input by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }

    fun commit() {
        if (input.isNotBlank()) {
            onUpdate(grant.withAddedPackage(input))
            input = ""
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.assistant_page_automation_packages_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.assistant_page_automation_packages_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Pick-from-installed-apps shortcut: most users do not know exact package names, and the
        // launcher/home package differs per device, so a tap-to-add picker is what makes scoped
        // automation usable. The free-text field below stays as a fallback for packages the picker
        // cannot enumerate (e.g. the launcher-only set on the play flavor).
        OutlinedButton(
            onClick = { showPicker = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(HugeIcons.Add01, contentDescription = null)
            Text(
                text = "Pick from installed apps",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.assistant_page_automation_packages_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
            )
            IconButton(onClick = { commit() }) {
                Icon(
                    HugeIcons.Add01,
                    contentDescription = stringResource(R.string.assistant_page_automation_packages_add),
                )
            }
        }
        if (grant.allowedPackages.isEmpty()) {
            Text(
                text = stringResource(R.string.assistant_page_automation_packages_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                grant.allowedPackages.sorted().forEach { pkg ->
                    FilterChip(
                        selected = true,
                        onClick = { onUpdate(grant.withRemovedPackage(pkg)) },
                        label = { Text(pkg, style = MaterialTheme.typography.labelMedium) },
                        trailingIcon = {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(
                                    R.string.assistant_page_automation_packages_remove
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    if (showPicker) {
        PackagePickerSheet(
            allowed = grant.allowedPackages,
            onToggle = { pkg, add ->
                onUpdate(if (add) grant.withAddedPackage(pkg) else grant.withRemovedPackage(pkg))
            },
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * Bottom-sheet picker over the installed packages ([InstalledPackageSource]) so the user can tap to
 * add/remove a scope target instead of typing a package name. Includes a search box and a
 * "show system packages" toggle (default ON — the home/launcher and many automation targets ARE
 * system packages). Tapping a row toggles it in the grant immediately; the already-allowed packages
 * render selected. On the play flavor the source returns only launcher-visible apps (no
 * QUERY_ALL_PACKAGES), so the list is shorter but the picker still works.
 */
@Composable
private fun PackagePickerSheet(
    allowed: Set<String>,
    onToggle: (pkg: String, add: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val source: InstalledPackageSource = koinInject()
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(true) }
    // null = still loading (the enumeration hops to Dispatchers.IO); a non-null list = loaded.
    var packages by remember { mutableStateOf<List<InstalledPackageInfo>?>(null) }

    LaunchedEffect(showSystem) {
        packages = null
        packages = source.list(includeSystem = showSystem)
    }

    val filtered = remember(packages, query) {
        val q = query.trim().lowercase()
        packages.orEmpty().filter {
            q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Pick from installed apps",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Text,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show system packages")
                Switch(checked = showSystem, onCheckedChange = { showSystem = it })
            }
            val loaded = packages
            if (loaded == null) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                }
            } else if (filtered.isEmpty()) {
                Text(
                    text = "No matching packages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { it.packageName }) { pkg ->
                        val selected = pkg.packageName in allowed
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(pkg.packageName, !selected) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pkg.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = pkg.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = HugeIcons.CheckmarkCircle01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaseLimitsEditor(grant: AutomationGrant, externalKey: Any, onUpdate: (AutomationGrant) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.assistant_page_automation_limits_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormTextField(
                value = grant.ttlMinutes.toString(),
                externalKey = "$externalKey:automationGrant:ttlMinutes",
                onValueChange = { raw ->
                    parsePositiveIntOrNull(raw)?.let { onUpdate(grant.copy(ttlMinutes = it)) }
                },
                label = { Text(stringResource(R.string.assistant_page_automation_ttl_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            FormTextField(
                value = grant.maxSteps.toString(),
                externalKey = "$externalKey:automationGrant:maxSteps",
                onValueChange = { raw ->
                    parsePositiveIntOrNull(raw)?.let { onUpdate(grant.copy(maxSteps = it)) }
                },
                label = { Text(stringResource(R.string.assistant_page_automation_steps_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(R.string.assistant_page_automation_limits_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun verbLabel(verb: AutomationVerb): String = stringResource(
    when (verb) {
        AutomationVerb.OBSERVE -> R.string.assistant_page_automation_verb_observe
        AutomationVerb.TAP -> R.string.assistant_page_automation_verb_tap
        AutomationVerb.SET_TEXT -> R.string.assistant_page_automation_verb_set_text
        AutomationVerb.SCROLL -> R.string.assistant_page_automation_verb_scroll
        AutomationVerb.GLOBAL -> R.string.assistant_page_automation_verb_global
    }
)

@Composable
private fun verbDescription(verb: AutomationVerb): String = stringResource(
    when (verb) {
        AutomationVerb.OBSERVE -> R.string.assistant_page_automation_verb_observe_desc
        AutomationVerb.TAP -> R.string.assistant_page_automation_verb_tap_desc
        AutomationVerb.SET_TEXT -> R.string.assistant_page_automation_verb_set_text_desc
        AutomationVerb.SCROLL -> R.string.assistant_page_automation_verb_scroll_desc
        AutomationVerb.GLOBAL -> R.string.assistant_page_automation_verb_global_desc
    }
)

@Composable
private fun sinkLabel(sink: AutomationSink): String = stringResource(
    when (sink) {
        AutomationSink.TYPE_INTO -> R.string.assistant_page_automation_sink_type_into
        AutomationSink.GLOBAL_NAV -> R.string.assistant_page_automation_sink_global_nav
        // SUBMIT is never in EDITABLE_SINKS, so this branch is unreachable from the editor — it only
        // exists to keep the `when` exhaustive. The label deliberately reuses the GLOBAL_NAV string
        // rather than minting a resource for a sink this surface must never render.
        AutomationSink.SUBMIT -> R.string.assistant_page_automation_sink_global_nav
    }
)
