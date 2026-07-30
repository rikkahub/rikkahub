# General Subagent and Mode UI Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add an isolated, approval-governed general subagent and replace cyclic Chat/Plan/Agent mode changes with an explicit, persistent selector.

**Architecture:** Reuse `DefaultSubagentRunner` for both kinds. Explore retains its fixed read-only allowlist; General resolves the parent's enabled tools in `AGENT` mode and keeps the existing `PermissionPolicy` approval path. A shared Compose mode selector is invoked from the chat top bar and the workspace composer panel so both entry points present the same labels, guidance, and persistence behavior.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Koin, JUnit 4, kotlinx.serialization.

---

### Task 1: Establish general-subagent runtime contracts

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/subagent/Subagent.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/subagent/DefaultSubagentRunner.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/agent/SubagentTest.kt`

**Step 1: Write failing tests**

Add tests that General has no fixed read-only allowlist, remains unable to register either subagent tool in a child run, and keeps its requested `AGENT` mode.

**Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.agent.SubagentTest"`

Expected: FAIL until General-specific policy helpers exist.

**Step 3: Implement the minimal runtime behavior**

Add `GENERAL_SUBAGENT_TOOL_NAME` and a General system prompt. In `DefaultSubagentRunner`, branch tool filtering by `SubagentKind`: Explore intersects its allowlist; General keeps every resolved parent tool except both subagent tools. Preserve the supplied `AgentMode` (General uses `AGENT`) and the existing `PermissionPolicy` on every call.

**Step 4: Re-run the focused test**

Expected: PASS.

### Task 2: Expose and register the general-subagent tool

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/tools/providers/GeneralSubagentToolProvider.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/ai/agent/SubagentTest.kt`

**Step 1: Write failing provider tests**

Verify that the provider is absent inside child runs and that its tool name is exposed in a parent registry.

**Step 2: Implement the provider**

Mirror the Explore provider's JSON validation and result payload. Set `kind = GENERAL`, `mode = AgentMode.AGENT`, include workspace/project-doc transformers, use `needsApproval = false` only for spawning, and describe that child tool calls still require their normal approvals.

**Step 3: Register after Explore**

Add the provider to `ToolRegistry` after `ExploreSubagentToolProvider`, retaining stable source order.

**Step 4: Run focused tests**

Expected: PASS.

### Task 3: Render a general-subagent result card

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/GeneralSubagentToolUI.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt`

**Step 1: Implement the renderer**

Reuse the trace, status, and report presentation of `ExploreSubagentToolUI`, but label it as a general subagent and use an execution-oriented icon/copy.

**Step 2: Register the renderer**

Add it to the tool renderer registry so `general_subagent` does not fall back to generic JSON UI.

**Step 3: Build the app module**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS.

### Task 4: Replace cyclic mode selection with a shared explicit selector

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/AgentModeSelector.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

**Step 1: Implement a reusable sheet/dialog**

Provide three explicit choices: Chat (standard chat tools), Plan (read-only investigation), and Agent (workspace edits/shell subject to approval). Mark the selected mode, add concise descriptions, and show the workspace-not-ready warning in-context without preventing the saved selection.

**Step 2: Wire both entry points**

Top-bar chip opens the selector instead of cycling. The workspace section of `FilesPicker` opens the same selector. Each selection calls the existing conversation update callback; the chat page continues its asynchronous save after selection.

**Step 3: Remove obsolete cycling callback**

Replace `onCycleAgentMode` with an explicit `onSetAgentMode(AgentMode)` callback and remove `AgentMode.next()` usage from these UI paths.

**Step 4: Build and inspect**

Run `./gradlew :app:compileDebugKotlin`. Launch the debug app/emulator if available and verify that every choice is visible and the selected state updates immediately.

### Task 5: Document and verify the complete feature

**Files:**
- Modify: `docs/references/agent-runtime-design.md`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/agent/SubagentTest.kt`

**Step 1: Update design documentation**

Mark General Subagent implemented and record its isolated-session, AGENT-mode, per-tool-approval semantics.

**Step 2: Run focused verification**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.agent.*"`

Expected: PASS.

**Step 3: Run compilation verification**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS.
