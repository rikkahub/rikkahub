# Agent Run Detail State-Owned Motion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Preserve complete outgoing Run-state geometry throughout the detail-header native transition.

**Architecture:** Keep interaction-level stopping as an outer gate, but derive progress-ring visibility and icon size
from the `AnimatedContent` state passed to each retained frame.

**Tech Stack:** Kotlin, Jetpack Compose `AnimatedContent`, Material 3 progress, Compose UI Test.

---

### Task 1: Establish the outgoing-frame contract

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Extend the controlled-clock detail transition test**

Assert known 0.25 progress before the transition and again after the first terminal transition frame.

**Step 2: Assert settled progress removal**

After advancing past the Material transition, require the old determinate progress semantics to be absent.

### Task 2: Make geometry state-owned

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Replace the outer state predicate**

Keep only `!isStopping` outside `AnimatedContent`.

**Step 2: Compute frame progress inside the content lambda**

Treat only that lambda's `PENDING` and `WORKING` states as progress-bearing, then use the result for both ring presence
and icon size.

**Step 3: Compile production and Android test sources**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`.

Expected: PASS.

### Task 3: Regression and static verification

**Files:**
- Verify only.

**Step 1: Run focused JVM regressions**

Run Agent activity, progress, presentation, navigation, timeline-follow, processing-status, ask-user draft, and approval
submission tests.

**Step 2: Audit the patch**

Run `git diff --check`, enforce the 120-character line limit, check device availability before claiming instrumentation
execution, and leave `_apk_dl2/` untouched.
