# Agent Nested Live Duration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep active child Run, step, and tool durations advancing without creating one timer per timeline card.

**Architecture:** Add the persisted duration start to nested presentation models, select live versus frozen duration in
a pure function, and drive every visible nested card from one detail-scoped clock.

**Tech Stack:** Kotlin, coroutines, Jetpack Compose Animation, Material 3, JUnit 4.

---

### Task 1: Specify nested duration behavior

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentationTest.kt`

**Step 1: Write failing tests**

Require working and attention-needed nested items to use a supplied clock, terminal items to retain stored duration,
and child/tool mappings to expose their actual persisted duration start.

**Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest.nestedLiveDurationUsesSharedClockOnlyWhileActive"`

Expected: FAIL because nested timing facts and the generic selector do not exist.

### Task 2: Add nested timing facts

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentation.kt`

**Step 1: Extend presentation models**

Add `durationStartedAt` to child Run and timeline item presentations with compatibility defaults for direct test data.

**Step 2: Map exact persisted starts**

Use `startedAt ?: createdAt` for child Runs and tools, step creation for steps, and event timestamp for trace events.

**Step 3: Implement the generic selector**

Use local time only for live visual states and delegate the existing root selector to the same implementation.

**Step 4: Re-run the focused test**

Expected: PASS.

### Task 3: Share one visible clock

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Generalize the local clock**

Key it by Run/context identity and start it only when its caller reports at least one live item.

**Step 2: Pass shared time to nested cards**

Calculate one detail timestamp and provide it to every child and timeline card without adding item-level effects.

**Step 3: Animate duration changes**

Crossfade duration text once per second while retaining the existing status and content-size motion.

### Task 4: Regression and audit

**Files:**
- Verify only.

**Step 1: Run focused regressions**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunTimelineFollowTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Audit source quality**

Run `git diff --check`, inspect new Kotlin/Markdown lines for the 120-character limit, and leave `_apk_dl2/` untouched.
