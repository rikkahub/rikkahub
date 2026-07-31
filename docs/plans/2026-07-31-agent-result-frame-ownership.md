# Agent Result Frame Ownership Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Preserve child Run findings and timeline details through native exit animations without retaining stale
interaction.

**Architecture:** Animate a nullable finding string for child cards and an immutable nullable details snapshot for
timeline cards. Share the same nullable-content transition policy already used by the detail header.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, AndroidX Compose UI Test

---

### Task 1: Add outgoing-frame contracts

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Test child finding exit**

Render a terminal child with findings under a paused clock, clear the findings, require the old text on the first frame,
then require its removal after the animation.

**Step 2: Test timeline details exit**

Render an expanded timeline item, clear every details field, require the old summary on the first frame while expansion
interaction is already removed, then require the summary to disappear.

**Step 3: Compile the contracts**

Run Android test Kotlin compilation before implementation.

### Task 2: Implement state-owned result frames

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Extract the nullable-content transition**

Create one private helper returning enter, exit, or replacement `ContentTransform` based on content availability. Reuse
it in the detail-header description.

**Step 2: Animate child findings by value**

Replace findings `AnimatedVisibility` with nullable `AnimatedContent` and render the lambda's finding value.

**Step 3: Animate an immutable timeline snapshot**

Create `AgentRunTimelineDetails`, derive current availability from it, and make the expanded nullable snapshot the
content target. Render only the snapshot supplied to the animation lambda.

### Task 3: Verify integration

**Files:**
- Verify all files changed by Tasks 1 and 2.

**Step 1: Compile production and Android test Kotlin**

Run Debug Kotlin and Android test Kotlin compilation.

**Step 2: Run Agent JVM regressions**

Run activity, progress, presentation, navigation, timeline-follow, processing, approval, and ask-user tests.

**Step 3: Perform static and device checks**

Run the 120-column scan, `git diff --check`, and Android SDK `adb` device query. Execute instrumentation when available;
otherwise report the Compose contracts as compile-only.
