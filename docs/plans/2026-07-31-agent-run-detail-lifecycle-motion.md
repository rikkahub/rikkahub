# Agent Run Detail Lifecycle Motion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make Run detail status and activity transition consistently across live and terminal lifecycle states.

**Architecture:** Reuse the lifecycle-aware activity function with terminal guidance disabled in the detail header.
Animate the identity line by status only, while duration remains a non-animating captured value.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3 motion, Compose UI Test.

---

### Task 1: Establish detail lifecycle contracts

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Preserve a stale step in the terminal fixture**

Require that step to disappear after the status transition instead of being shown as current work.

**Step 2: Add controlled-clock status-motion assertions**

Verify the outgoing identity remains during the first transition frame and is removed after the Material animation.

**Step 3: Assert targeted live-region semantics**

Require `LiveRegionMode.Polite` on the activity node with the unmerged semantics tree.

### Task 2: Implement lifecycle-aware detail motion

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Reuse `agentRunActivityText`**

Pass null terminal guidance so completed, failed, and stopped detail activity collapses without duplicating the separate
status-description section.

**Step 2: Animate the identity by status**

Use fade and one-third-height vertical slide transitions with `presentation.status` as the only target state.

**Step 3: Add activity live-region semantics**

Mark only the nullable activity `AnimatedContent` as polite, leaving the duration identity outside the live region.

**Step 4: Compile production and Android test sources**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`.

Expected: PASS.

### Task 3: Regression and static verification

**Files:**
- Verify only.

**Step 1: Run focused regressions**

Run Agent activity, progress, presentation, navigation, timeline-follow, processing-status, ask-user draft, and approval
submission JVM tests.

**Step 2: Audit the patch**

Run `git diff --check`, enforce the 120-character line limit, confirm device availability before claiming
instrumentation execution, and leave `_apk_dl2/` untouched.
