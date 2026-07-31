# Agent Tool Submission Transition Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Preserve native Agent tool submission crossfades without leaving outgoing controls interactive.

**Architecture:** Keep `AnimatedContent` state frames for visual identity, but gate outgoing approval buttons from the
latest submission state. Put polite live-region semantics on stable submission animation containers.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, AndroidX Compose UI Test

---

### Task 1: Strengthen Compose contracts

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/components/message/ToolApprovalActionsTest.kt`

**Step 1: Pause the approval transition clock**

Update the submission test to inspect the first crossfade frame rather than waiting for completion.

**Step 2: Assert immediate interaction revocation**

Require approve and deny controls to remain visible but disabled while the progress indicator is already present. Then
advance past the animation and require the old controls to disappear.

**Step 3: Assert accessible progress notification**

Require `LiveRegionMode.Polite` for approval and ask-user submission animation containers.

### Task 2: Gate retained controls and add semantics

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`

**Step 1: Disable outgoing approval buttons**

Set both icon buttons' `enabled` property from the latest `isSubmitting` value.

**Step 2: Add polite live regions**

Add stable live-region semantics to approval and ask-user submit `AnimatedContent` containers.

**Step 3: Compile production and Android test Kotlin**

Run Debug Kotlin and Android test Kotlin compilation.

### Task 3: Verify integration

**Files:**
- Verify all files changed by Tasks 1 and 2.

**Step 1: Run focused and Agent JVM tests**

Run approval submission, ask-user draft, Agent activity, progress, presentation, navigation, timeline, and processing
tests.

**Step 2: Run static checks**

Run the 120-column scan and `git diff --check`.

**Step 3: Check device availability**

Query the Android SDK's `adb`. Execute instrumentation when a device is connected; otherwise report the Compose
contracts as compile-only.
