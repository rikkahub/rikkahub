package me.rerere.rikkahub.data.ai.runtime

import me.rerere.ai.core.Tool

/**
 * The base (non-MCP / non-spawn / non-board / non-schedule) tool-pool composition for a chat turn,
 * extracted from `ChatService.handleMessageComplete` (issue #360 P5) so the GATE policy — which
 * capability is offered under which condition, and in which order — is JVM-unit-testable without the
 * full ChatService, the concrete tool factories, or an Android runtime. The security-relevant
 * assembly policy (by-target MCP, the spawn recursion guard, the approval strip) already lives in
 * [AppToolCatalog]; this is the remaining inline closure it consumes via its `baseTools` seam.
 *
 * Behavior is preserved 1:1 from the former inline `AppToolCatalog.baseTools` closure:
 *  - web-search and managed-fetch are GATED — when off, the producer is not even invoked;
 *  - image-gen / local / workspace / ui-automation are UNCONDITIONAL producers (each self-gates to
 *    empty internally — ui-automation returns empty when the a11y core is absent);
 *  - skills ride the enabled-skills gate;
 *  - the order is the same order the original `buildList` appended in.
 *
 * The gated producers (search / fetch / skills) are suspend lambdas invoked LAZILY behind their
 * gate, so a disabled capability does no work (e.g. `createSearchTools` is never built when web
 * search is off) — matching the original short-circuit, not just its result.
 *
 * ui-automation is an UNCONDITIONAL producer rather than a Boolean gate ON PURPOSE: the original
 * read `automationRegistry.core()` ONCE, at this position in the sequence (after the suspend
 * workspace read), and used that single snapshot for both the null-check and the tool build. Lifting
 * the null-check into a pre-computed Boolean would move the snapshot earlier (a `createWorkspaceTools`
 * suspension could see the a11y service connect/disconnect in between), so the producer keeps the
 * single read at this exact point and returns empty when the core is absent.
 */
suspend fun assembleBaseTools(
    webSearchEnabled: Boolean,
    managedFetchAvailable: Boolean,
    skillsEnabled: Boolean,
    searchTools: suspend () -> Collection<Tool>,
    fetchTools: suspend () -> Collection<Tool>,
    imageGenTools: suspend () -> Collection<Tool>,
    localTools: suspend () -> Collection<Tool>,
    workspaceTools: suspend () -> Collection<Tool>,
    uiAutomationTools: suspend () -> Collection<Tool>,
    skillTools: suspend () -> Collection<Tool>,
): List<Tool> = buildList {
    if (webSearchEnabled) addAll(searchTools())
    if (managedFetchAvailable) addAll(fetchTools())
    addAll(imageGenTools())
    addAll(localTools())
    addAll(workspaceTools())
    addAll(uiAutomationTools())
    if (skillsEnabled) addAll(skillTools())
}
