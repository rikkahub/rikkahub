package me.rerere.rikkahub.data.ai.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [assembleBaseTools] (#360 P5): the base tool-pool GATE composition extracted
 * from `ChatService.handleMessageComplete`. Pins which capability is offered under which condition,
 * in which order, and that a gated-off producer is never even invoked (the lazy short-circuit) —
 * none of which was unit-testable while it lived inside the per-generation `AppToolCatalog.baseTools`
 * closure. The security-relevant policy (by-target MCP, spawn guard, approval strip) is pinned
 * separately by AppToolCatalogPolicyTest.
 *
 * ui-automation is an UNCONDITIONAL producer (it self-gates to empty when the a11y core is absent),
 * so it is invoked every call; its single-snapshot core read lives in the ChatService producer, not
 * in this pure function (see [assembleBaseTools]' contract for why).
 */
class BaseToolAssemblyTest {

    private fun tool(name: String): Tool = Tool(name = name, description = "", execute = { emptyList() })

    /** A producer that records whether it was invoked, so the lazy-gate short-circuit is observable. */
    private class Producer(private val tools: List<Tool>) {
        var invoked = false
        val producer: suspend () -> Collection<Tool> = { invoked = true; tools }
    }

    private fun assemble(
        webSearchEnabled: Boolean = false,
        managedFetchAvailable: Boolean = false,
        skillsEnabled: Boolean = false,
        skillAuthoringActive: Boolean = false,
        search: Producer = Producer(listOf(tool("search"))),
        fetch: Producer = Producer(listOf(tool("fetch"))),
        imageGen: Producer = Producer(listOf(tool("imagegen"))),
        local: Producer = Producer(listOf(tool("local"))),
        workspace: Producer = Producer(listOf(tool("workspace"))),
        uiAutomation: Producer = Producer(listOf(tool("ui"))),
        skills: Producer = Producer(listOf(tool("skill"))),
        skillAuthoring: Producer = Producer(listOf(tool("skill_authoring"))),
    ): List<Tool> = runBlocking {
        assembleBaseTools(
            webSearchEnabled = webSearchEnabled,
            managedFetchAvailable = managedFetchAvailable,
            skillsEnabled = skillsEnabled,
            skillAuthoringActive = skillAuthoringActive,
            searchTools = search.producer,
            fetchTools = fetch.producer,
            imageGenTools = imageGen.producer,
            localTools = local.producer,
            workspaceTools = workspace.producer,
            uiAutomationTools = uiAutomation.producer,
            skillTools = skills.producer,
            skillAuthoringTools = skillAuthoring.producer,
        )
    }

    @Test
    fun `unconditional producers are always present`() {
        val names = assemble().map { it.name }
        assertEquals(
            "image-gen / local / workspace / ui-automation are unconditional (each self-gates internally)",
            listOf("imagegen", "local", "workspace", "ui"),
            names,
        )
    }

    @Test
    fun `web search is gated and its producer is not invoked when disabled`() {
        val search = Producer(listOf(tool("search")))
        val off = assemble(webSearchEnabled = false, search = search)
        assertFalse("search must be absent when web search is off", off.any { it.name == "search" })
        assertFalse("a gated-off producer must not be invoked (lazy short-circuit)", search.invoked)

        val search2 = Producer(listOf(tool("search")))
        val on = assemble(webSearchEnabled = true, search = search2)
        assertTrue("search must be present when web search is on", on.any { it.name == "search" })
        assertTrue(search2.invoked)
    }

    @Test
    fun `managed fetch is gated on the managed-provider flag`() {
        val fetch = Producer(listOf(tool("fetch")))
        assertFalse(assemble(managedFetchAvailable = false, fetch = fetch).any { it.name == "fetch" })
        assertFalse("fetch producer must not run when no managed provider is configured", fetch.invoked)
        assertTrue(assemble(managedFetchAvailable = true).any { it.name == "fetch" })
    }

    @Test
    fun `skills are gated on the enabled-skills flag`() {
        val skills = Producer(listOf(tool("skill")))
        assertFalse(assemble(skillsEnabled = false, skills = skills).any { it.name == "skill" })
        assertFalse("skill producer must not run when no skills are enabled", skills.invoked)
        assertTrue(assemble(skillsEnabled = true).any { it.name == "skill" })
    }

    @Test
    fun `skill authoring is gated on its own flag, independent of skillsEnabled`() {
        // Authoring is offered ONLY while a slash-armed authoring session is active, and rides its OWN
        // gate — a user can author the FIRST skill (none enabled) so it must NOT depend on skillsEnabled.
        val off = Producer(listOf(tool("skill_authoring")))
        assertFalse(
            "authoring tool absent when not armed",
            assemble(skillsEnabled = true, skillAuthoringActive = false, skillAuthoring = off).any { it.name == "skill_authoring" },
        )
        assertFalse("a disarmed authoring producer must not run (lazy short-circuit)", off.invoked)

        val on = Producer(listOf(tool("skill_authoring")))
        val armed = assemble(skillsEnabled = false, skillAuthoringActive = true, skillAuthoring = on)
        assertTrue("authoring tool present when armed even with NO skills enabled", armed.any { it.name == "skill_authoring" })
        assertTrue(on.invoked)
    }

    @Test
    fun `ui automation producer is always invoked and contributes nothing when it self-gates to empty`() {
        // The a11y-core null-check lives inside the ChatService producer (single post-workspace
        // snapshot); when the core is absent the producer returns empty. The pure function still
        // invokes it unconditionally — modelled here by a producer that returns empty.
        val ui = Producer(emptyList())
        val names = assemble(uiAutomation = ui).map { it.name }
        assertTrue("the unconditional ui producer is always invoked", ui.invoked)
        assertFalse("a self-gated-empty ui producer contributes nothing", names.contains("ui"))
        assertEquals(listOf("imagegen", "local", "workspace"), names)
    }

    @Test
    fun `order is preserved when every capability is enabled`() {
        val all = assemble(
            webSearchEnabled = true,
            managedFetchAvailable = true,
            skillsEnabled = true,
        ).map { it.name }
        assertEquals(
            "the pool order matches the former inline buildList append order",
            listOf("search", "fetch", "imagegen", "local", "workspace", "ui", "skill"),
            all,
        )
    }

    @Test
    fun `all producers contribute their full tool lists, not just one each`() {
        val multi = runBlocking {
            assembleBaseTools(
                webSearchEnabled = true,
                managedFetchAvailable = false,
                skillsEnabled = false,
                skillAuthoringActive = false,
                searchTools = { listOf(tool("s1"), tool("s2")) },
                fetchTools = { emptyList() },
                imageGenTools = { listOf(tool("i1")) },
                localTools = { listOf(tool("l1"), tool("l2"), tool("l3")) },
                workspaceTools = { emptyList() },
                uiAutomationTools = { emptyList() },
                skillTools = { emptyList() },
                skillAuthoringTools = { emptyList() },
            )
        }
        assertEquals(listOf("s1", "s2", "i1", "l1", "l2", "l3"), multi.map { it.name })
    }
}
