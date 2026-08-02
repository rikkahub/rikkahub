# Agent Run Activity Lifecycle Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove misleading terminal activity from Agent Run cards while preserving useful failure guidance and native
transition feedback.

**Architecture:** Derive nullable activity text with a pure lifecycle-aware function. Feed it into existing Compose
transitions and isolate status/activity semantics in a polite live region that excludes the duration clock.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3 motion, JUnit 4, Compose UI Test.

---

### Task 1: Define activity lifecycle rules

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunActivityTest.kt`

**Step 1: Write failing JVM tests**

Cover stopping precedence; live waiting reason, current step, and fallback order; terminal stale-field suppression;
success without a description; and terminal failure guidance.

**Step 2: Run the focused test and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.AgentRunActivityTest"`.

Expected: FAIL because `agentRunActivityText` does not exist.

**Step 3: Implement the pure function**

Return live fields only for `isLive()` states and return only `statusDescription` for terminal states.

**Step 4: Re-run the focused test**

Expected: PASS.

### Task 2: Wire native terminal motion and semantics

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Add Compose assertions first**

Require a successful terminal transition to remove stale activity and the telemetry fallback, retain failure guidance,
and expose `LiveRegionMode.Polite` on the dynamic block.

**Step 2: Make activity nullable**

Render nullable activity through `AnimatedContent`; rely on the existing expressive size animation for compaction.

**Step 3: Isolate the live region**

Wrap only status and activity in a semantic group. Leave duration metadata outside it.

**Step 4: Compile production and Android tests**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`.

Expected: PASS.

### Task 3: Regression and static verification

**Files:**
- Verify only.

**Step 1: Run focused JVM regressions**

Run activity, progress, presentation, navigation, timeline-follow, processing-status, ask-user draft, and approval
submission tests.

**Step 2: Audit the patch**

Run `git diff --check`, enforce the 120-character line limit, check device availability before claiming instrumentation
execution, and leave `_apk_dl2/` untouched.
