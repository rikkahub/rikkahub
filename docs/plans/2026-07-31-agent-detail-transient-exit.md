# Agent Detail Transient Exit Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Preserve outgoing Agent detail guidance visually while revoking obsolete approval interaction immediately.

**Architecture:** Make nullable status-description data the state of `AnimatedContent` so retained frames capture their
own text. Keep the approval row's existing visibility transition and separate its interaction gate from visual presence.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, AndroidX Compose UI Test

---

### Task 1: Add transition contracts

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Test description ownership**

Render a failed presentation with guidance, remove the guidance under a paused clock, and require the old text during
the first exit frame and its absence after the animation.

**Step 2: Test approval interaction gating**

Render an available approval action, remove the callback under a paused clock, and require the outgoing button to be
visible without a click action before it disappears.

**Step 3: Compile the contracts before implementation**

Compile Android test Kotlin. The new contracts should compile; on a connected device the current implementation is
expected to fail at the first-frame assertions.

### Task 2: Correct transient state ownership

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Replace description visibility with nullable content animation**

Use `AnimatedContent` with top-aligned expand/fade and shrink/fade transitions, rendering text from the lambda state.

**Step 2: Gate approval interaction**

Set `TextButton.enabled` from current callback availability while retaining the existing null-safe invocation.

**Step 3: Compile production and Android test Kotlin**

Run Debug Kotlin and Android test Kotlin compilation.

### Task 3: Verify regressions and formatting

**Files:**
- Verify all files changed by Tasks 1 and 2.

**Step 1: Run Agent JVM regressions**

Run activity, progress, presentation, navigation, timeline-follow, processing, approval, and ask-user tests.

**Step 2: Run static checks**

Run the 120-column scan and `git diff --check`.

**Step 3: Check instrumentation availability**

Query the Android SDK's `adb`. Run the focused instrumentation contracts when a device is connected; otherwise report
them as compile-only.
