# Agent Permission and Workspace Simplification Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add user-controlled Agent permission modes and make workspace creation, binding, and configuration reachable from one shared picker.

**Architecture:** Persist the selected mode on each serialized `Assistant`, carry it through `PermissionPolicy`, and evaluate it after hard safety/availability gates but before automatic risk rules. Reuse one Compose workspace sheet in assistant settings and chat, with inline creation and direct detail navigation.

**Tech Stack:** Kotlin, kotlinx.serialization, Jetpack Compose Material 3, JUnit 4, Gradle.

---

### Task 1: Permission mode policy

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/permission/PermissionPolicy.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/permission/CapabilityPolicy.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/agent/permission/CapabilityPolicyTest.kt`

1. Add failing tests for critical confirmation, full access, and hard-deny precedence.
2. Run `./gradlew :app:testDebugUnitTest --tests '*CapabilityPolicyTest'` and verify the new tests fail.
3. Add the serializable mode and implement the three policy branches.
4. Run the focused test again and verify it passes.

### Task 2: Runtime propagation and frozen-policy identity

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/agent/routing/ToolProfileResolver.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/agent/routing/ToolProfileResolverTest.kt`

1. Add a failing test proving a permission-mode change alters the permission digest.
2. Pass the assistant mode into `PermissionPolicy` during Agent preparation.
3. Include the mode in canonical digest data and increment its schema version.
4. Run the focused routing and permission tests.

### Task 3: Shared workspace access UI

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/WorkspaceSelectSheet.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantBasicPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

1. Replace the assistant's plain workspace dropdown with the shared sheet.
2. Add inline workspace creation and direct workspace-detail navigation to the sheet.
3. Show the workspace entry in chat even when no workspaces exist.
4. Add the assistant permission-mode selector and concise mode descriptions.
5. Run Kotlin compilation to catch Compose/API errors.

### Task 4: Verification

1. Run the focused permission and routing tests.
2. Run `./gradlew :app:compileDebugKotlin`.
3. Review `git diff --check` and the scoped diff, preserving unrelated user changes.
