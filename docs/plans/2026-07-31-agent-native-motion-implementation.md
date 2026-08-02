# Agent Native Motion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make Agent Run state changes immediately understandable with restrained, native Compose/Material 3 motion.

**Architecture:** Keep persisted run data unchanged and derive a small UI-only visual state from `AgentRunStatus`. The active card owns state/color/content transitions, while `ChatPage` owns card entrance and exit so animation remains aligned with composition lifecycle. Motion uses the app's existing Material Expressive motion scheme and standard Compose transitions, which inherit the platform animation-duration scale.

**Tech Stack:** Kotlin, Jetpack Compose Animation, Material 3 Expressive, JUnit 4, AndroidX Compose UI Test.

---

### Task 1: Define stable visual states

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentation.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentationTest.kt`

**Step 1: Write the failing test**

Add a table-driven test covering queued/preflight/running, waiting approval, succeeded, failed/blocked, and cancelled/interrupted mappings.

**Step 2: Run the focused test and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest"`

Expected: FAIL because the visual-state contract does not exist.

**Step 3: Implement the minimal mapping**

Add an `AgentRunVisualState` enum and expose it from `AgentRunPresentation`. Do not persist it or infer it from translated display text.

**Step 4: Re-run the focused test**

Expected: PASS.

### Task 2: Upgrade the active run card with native motion

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Extend UI assertions**

Verify the running card exposes progress semantics and the approval card exposes its waiting message and open action.

**Step 2: Implement the visual treatment**

Use Material 3 container/content colors per visual state, `AnimatedContent` for icon/status/current step, `animateContentSize` for text changes, and the standard linear progress indicator for active work. Waiting approval receives a distinct tertiary treatment without a looping animation.

**Step 3: Run the instrumentation test when an Android target is available**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.AgentRunCenterTest`

Expected: PASS, or record that no device is available and rely on compile plus JVM coverage.

### Task 3: Animate card lifecycle and timeline updates

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Animate the active card lifecycle**

Replace the direct nullable composition with keyed `AnimatedVisibility`, combining a short fade with vertical expand/shrink. Keep the card aligned below the top app bar and preserve its open action.

**Step 2: Animate live Run Center updates**

Give timeline items stable keys and use `animateItem()` plus `animateContentSize()` so newly persisted telemetry settles into place without replaying the whole sheet.

**Step 3: Compile the app**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS.

### Task 4: Final verification

**Files:**
- Verify only; no planned source changes.

**Step 1: Run focused JVM tests**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest"`

Expected: PASS.

**Step 2: Run Android lint for changed UI code**

Run: `./gradlew :app:lintDebug`

Expected: PASS, or report unrelated pre-existing findings separately.

**Step 3: Inspect the final diff**

Confirm no persisted Agent semantics changed, no sensitive telemetry is newly rendered, and the unrelated `_apk_dl2/` directory remains untouched.
