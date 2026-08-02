# Agent New Activity Count Motion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Give Agent timeline unseen-count changes directional native motion and accessible announcements.

**Architecture:** Add a pure count-motion classifier beside the existing Agent detail motion helpers. Use it from the
floating button's `AnimatedContent` transition while keeping the parent visibility and scroll state unchanged.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, JUnit, AndroidX Compose UI Test

---

### Task 1: Lock count direction and UI behavior

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunTimelineFollowTest.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Add the pure direction contract**

Assert that larger targets classify as `INCREASE`, smaller targets as `DECREASE`, and equal values as `STEADY`.

**Step 2: Add the controlled Compose contract**

Render count one with a paused clock, update to three, and require both labels on the first transition frame. Advance
past the animation and require only the new label.

**Step 3: Add accessibility and click assertions**

Require `LiveRegionMode.Polite` on the button while preserving its existing click callback.

### Task 2: Implement directional native count motion

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Add the count-motion classifier**

Create an internal enum and pure comparison function near the existing detail motion helpers.

**Step 2: Apply the transition**

Use upward incoming/outgoing offsets for increases, inverse offsets for decreases, and a fade fallback for steady
values. Use the Material expressive spatial specification for vertical movement.

**Step 3: Add polite announcements**

Apply the live-region semantic to the stable floating-button modifier rather than to transient text frames.

### Task 3: Verify the integration

**Files:**
- Verify all files changed by Tasks 1 and 2.

**Step 1: Run focused tests and compilation**

Run the timeline-follow JVM test, Debug Kotlin compilation, and Android test Kotlin compilation.

**Step 2: Run Agent regressions**

Run the existing Agent progress, presentation, navigation, activity, processing, approval, and ask-user JVM tests.

**Step 3: Perform static and environment checks**

Run the 120-column scan and `git diff --check`, then query the Android SDK's `adb`. Execute instrumentation when a
device is available; otherwise report the Compose contract as compile-only.
