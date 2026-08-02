# Agent Child Run Cards Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make child Agent progress, completion, failure, and findings immediately understandable inside the parent Run.

**Architecture:** Extend the existing redacted `ChildRunPresentation` with UI-only visual state and duration derived
from the persisted child Run. Render each child as a separately keyed Material card, so Room updates animate in place
and new children enter the parent detail list without rebuilding unrelated telemetry content.

**Tech Stack:** Kotlin, Jetpack Compose Animation, Material 3, JUnit 4, AndroidX Compose UI Test.

---

### Task 1: Extend the safe child presentation

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentation.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentationTest.kt`

**Step 1: Write the failing test**

Require a child presentation to expose `visualState` and `duration` in addition to its existing ID, safe status label,
and bounded structured findings.

**Step 2: Run the focused test and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest.presentationShowsOnlyChildLinkAndStructuredFinding"`

Expected: FAIL because the child presentation does not expose visual state or duration.

**Step 3: Implement the mapping**

Map the child's persisted status through the same `AgentRunVisualState` function used by parent Runs. Derive duration
from persisted start/create and finish/update timestamps. Do not expose raw summary JSON or internal errors.

**Step 4: Re-run the focused test**

Expected: PASS.

### Task 2: Create a native child Run card

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Add UI assertions**

Verify that an active child exposes indeterminate progress and an open action. Transition it to success and verify the
progress disappears while findings become visible and the accessibility description updates.

**Step 2: Implement the card**

Use a Material clickable card, `AnimatedContent` for visual state/status, `animateColorAsState` for semantic tint, and
`AnimatedVisibility` plus `animateContentSize` for findings. Use the platform animation-duration scale through standard
Compose transitions; do not add timers or infinite effects beyond the native progress indicator.

**Step 3: Compile Android test sources**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: PASS.

### Task 3: Animate child list updates

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Replace the nested button group**

Emit a localized section header and separate keyed lazy-list items for children. Bind each click to that child's frozen
`runId`.

**Step 2: Add item placement animation**

Apply `Modifier.animateItem()` to each keyed child card so new child Runs and status-driven size changes settle without
replaying the whole Run detail pane.

**Step 3: Compile the app**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS.

### Task 4: Final verification

**Files:**
- Verify only.

**Step 1: Run focused regression tests**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Inspect the diff**

Confirm child status is derived from persisted status rather than translated text, findings stay bounded/redacted, click
identity remains `child.runId`, active-only progress semantics are correct, and `_apk_dl2/` remains untouched.
