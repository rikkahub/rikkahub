# Agent Run Active Progress Identity Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Give every Agent Run surface the same native progress identity for pending and working states.

**Architecture:** Add one internal predicate to `AgentRunVisualState` and replace duplicated state comparisons with it.
The timeline card will consequently render its existing progress-ring composition for pending items as well.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, JUnit, AndroidX Compose UI Test

---

### Task 1: Lock the lifecycle rule with tests

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunProgressTest.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Write the JVM contract**

Add an exhaustive assertion that `PENDING` and `WORKING` show active progress while attention and terminal states do
not.

**Step 2: Write the Compose contract**

Extend the pending-to-attention timeline test so pending exposes `ProgressBarRangeInfo.Indeterminate` and attention
removes it.

**Step 3: Run the tests**

Run the focused JVM test and compile the Android test source. The current implementation should fail the JVM compile
until the predicate exists; the Compose test is expected to fail on a connected device before implementation.

### Task 2: Centralize and apply active progress identity

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Add the lifecycle predicate**

Implement `AgentRunVisualState.showsActiveProgress()` for pending and working states only.

**Step 2: Replace duplicated comparisons**

Use the predicate in the active card, top-bar entry, child Run card, detail header, and timeline card. Keep interaction
gates such as `isStopping` outside the lifecycle predicate.

**Step 3: Run focused verification**

Run `AgentRunProgressTest`, compile Debug Kotlin and Android test Kotlin, then run the broader Agent JVM regression set.

### Task 3: Perform static and environment checks

**Files:**
- Verify all files changed by Tasks 1 and 2.

**Step 1: Check formatting**

Run the 120-column scan and `git diff --check`.

**Step 2: Check instrumentation availability**

Query the Android SDK's `adb` for connected devices. Execute the focused instrumentation contract if a device is
available; otherwise report it as compile-only.
