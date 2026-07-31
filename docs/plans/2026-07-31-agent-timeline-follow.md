# Agent Timeline Smart Follow Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep new Agent telemetry visible at the end while preserving the user's position when reviewing older items.

**Architecture:** Model stable-key updates with pure functions, then let the visible detail composable own its
`LazyListState`, follow preference, and unseen count. Use Material native motion for automatic scrolling and the
new-activity affordance.

**Tech Stack:** Kotlin, coroutines Flow, Jetpack Compose LazyColumn and Animation, Material 3, JUnit 4.

---

### Task 1: Specify timeline update decisions

**Files:**
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunTimelineFollowTest.kt`

**Step 1: Write failing pure tests**

Assert that stable-key insertions are counted once, status-only updates add nothing, follow mode requests scrolling,
paused mode accumulates unseen items, and list-index calculation includes child and empty-state items.

**Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunTimelineFollowTest"`

Expected: FAIL because the timeline update helpers do not exist.

### Task 2: Implement pure update helpers

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Add the update result model**

Return current stable keys, number added, accumulated unseen count, and whether the UI should follow the latest item.

**Step 2: Add final-index calculation**

Account for the header, information, routing, optional child section, timeline header, timeline or empty item, and final
spacer.

**Step 3: Re-run the focused test**

Expected: PASS.

### Task 3: Add smart-follow list behavior

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

**Step 1: Observe intentional list movement**

Initialize from the first completed layout, pause following only when the user scrolls away, and clear unseen items when
the latest items become visible again.

**Step 2: React to stable-key insertions**

Animate to the calculated final item in follow mode; otherwise retain position and accumulate the unseen count.

**Step 3: Add the native affordance**

Overlay an animated Material extended floating action button with pluralized new-activity text and a down-arrow icon.
Clicking it reenables follow mode and animates to the newest item.

### Task 4: Regression and audit

**Files:**
- Verify only.

**Step 1: Run focused regressions**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunTimelineFollowTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Audit source quality**

Run `git diff --check`, inspect new lines for the 120-character Kotlin/Markdown limit, and verify `_apk_dl2/` remains
untouched.
