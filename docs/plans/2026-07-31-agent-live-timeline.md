# Agent Live Timeline Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the active operation, approval wait, failure, and safe details immediately understandable in Run history.

**Architecture:** Add a UI-only visual state to the redacted timeline presentation, derived from persisted status.
Render each stable timeline item as a status-aware expandable Material card with standard Compose transitions.

**Tech Stack:** Kotlin, Jetpack Compose Animation, Material 3, JUnit 4, AndroidX Compose UI Test.

---

### Task 1: Map persisted timeline status to visual state

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentation.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentationTest.kt`

**Step 1: Write the failing assertions**

Require a running step to be `WORKING`, a waiting-approval tool to be `NEEDS_ATTENTION`, and a denied trace to be
`FAILED`.

**Step 2: Run the focused presentation tests**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest"`

Expected: FAIL because timeline items do not expose a visual state.

**Step 3: Implement one content-independent mapping**

Add `visualState` to `AgentRunTimelineItem` and map the allow-listed persisted status names. Do not infer from `status`
display text or decode any new payload.

**Step 4: Re-run the focused tests**

Expected: PASS.

### Task 2: Build the expandable native timeline card

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Add UI test cases**

Verify active progress and its transition to success. Verify a successful item starts collapsed and exposes localized
expand/collapse semantics while revealing its safe summary on click.

**Step 2: Compile Android tests and observe the failure**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: FAIL until the new timeline card contract and strings exist.

**Step 3: Implement status motion and expansion**

Use `animateColorAsState`, `AnimatedContent`, `AnimatedVisibility`, `animateFloatAsState`, `animateContentSize`, and
`rememberSaveable(item.stableKey)`. Only running items show indeterminate progress. Only items with safe details are
clickable.

**Step 4: Localize the new interaction semantics**

Add English and Simplified Chinese labels for duration, detail fields, expand/collapse actions, and expansion state.

**Step 5: Compile Android test sources**

Expected: PASS.

### Task 3: Run regression and workspace audit

**Files:**
- Verify only.

**Step 1: Run focused regressions**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Inspect source quality and scope**

Run `git diff --check`, scan changed files for lines over 120 characters, verify no raw telemetry fields enter the UI,
and confirm the user-owned `_apk_dl2/` directory remains untouched.
