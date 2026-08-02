# Agent Run Stop Control Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Let users stop the exact active Agent Run from its status card with immediate, duplicate-safe native feedback.

**Architecture:** Reuse the existing run-id-bound `ChatService.stopGeneration()` cancellation contract. `ChatVM` owns a transient `stoppingRunId` flow so every stop entry point shares duplicate suppression and failure cleanup; the active card only renders that state and emits the frozen run ID it displays. No optimistic durable status changes are introduced.

**Tech Stack:** Kotlin coroutines and StateFlow, Jetpack Compose Animation, Material 3, AndroidX Compose UI Test.

---

### Task 1: Track stop requests in ChatVM

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`
- Verify: `app/src/test/java/me/rerere/rikkahub/service/ChatServiceLifecycleTest.kt`

**Step 1: Add a transient stop state**

Expose `stoppingRunId: StateFlow<String?>` backed by a private `MutableStateFlow`.

**Step 2: Make stop requests duplicate-safe**

Use `compareAndSet(null, runId)` before launching cancellation. Always clear with `compareAndSet(runId, null)` in `finally`, and send non-cancellation failures to the existing conversation error center.

**Step 3: Verify the identity-bound service contract**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.ChatServiceLifecycleTest"`

Expected: PASS.

### Task 2: Add the native stop control to the active card

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Write UI assertions**

Verify that the active card exposes a stop action, invokes it once, and replaces it with a disabled stopping indicator while a request is in flight.

**Step 2: Implement the control**

Add `onStop` and `isStopping` parameters. Render a Material icon button whose content uses `AnimatedContent` to switch between a stop icon and a compact progress indicator. Override the activity line with localized stopping text while the request is pending.

**Step 3: Compile Android test sources**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: PASS.

### Task 3: Wire the stop state through ChatPage

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`

**Step 1: Observe the shared state**

Collect `vm.stoppingRunId` alongside generation state and pass it through both compact and wide chat layouts.

**Step 2: Bind the displayed run identity**

The card's stop callback must use `run.runId` from the retained card payload, while its spinner is true only when that same ID equals `stoppingRunId`.

**Step 3: Compile the app**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS.

### Task 4: Final verification

**Files:**
- Verify only.

**Step 1: Run focused tests and compilation**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.ChatServiceLifecycleTest" --tests "me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Inspect the diff**

Confirm the stop action never selects a replacement Run, does not mutate Room optimistically, reports failures, and leaves `_apk_dl2/` untouched.
