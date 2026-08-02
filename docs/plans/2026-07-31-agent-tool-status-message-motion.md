# Agent Tool Status Message Motion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Give every Agent tool status message a stable, accessible native transition that survives replacement and
removal.

**Architecture:** A shared nullable-state composable owns captured message frames and polite live-region semantics.
Regular tools and ask-user render it as a sibling below their step so the animation owner is never removed by the same
state change it needs to animate.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, AndroidX Compose UI Test

---

### Task 1: Add the failing transition contract

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/components/message/ToolApprovalActionsTest.kt`

**Step 1: Render a mutable nullable tool status**

Use `ToolStatusMessage` under `MaterialTheme` with a paused Compose clock.

**Step 2: Verify replacement frame ownership**

Change `First error` to `Second error`, advance one frame, and require both captured frames to be visible while the
stable container exposes `LiveRegionMode.Polite`.

**Step 3: Verify null exit ownership**

Clear the message, require `Second error` during the first exit frame, then advance beyond the transition and require it
to disappear.

**Step 4: Run the Android-test Kotlin compiler**

Run `./gradlew :app:compileDebugAndroidTestKotlin --console=plain` and expect unresolved `ToolStatusMessage` before the
implementation exists.

### Task 2: Implement and integrate the status surface

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`

**Step 1: Implement nullable frame transitions**

Add `ToolStatusMessage(message, modifier)` using directional expand/shrink plus fade and a polite live region on the
stable `AnimatedContent` owner.

**Step 2: Move regular tool status out of expandable details**

Remove `approvalStatusMessage` from `hasExtraContent`, remove the direct error `Text`, and render the shared component
after the regular tool step with the existing detail indentation.

**Step 3: Move ask-user status to the shared surface**

Remove its local fade-only block and render the same shared component after the ask-user step.

**Step 4: Compile production and Android-test Kotlin**

Run `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain` and expect success apart from
the repository's known Navigation3 opt-in warning.

### Task 3: Verify integration

**Files:**
- Verify all files changed in Tasks 1 and 2.

**Step 1: Run focused JVM tests**

Run ask-user draft, tool approval submission, Agent activity, progress, presentation, navigation, timeline, and
processing tests.

**Step 2: Run static checks**

Run the 120-column scan and `git diff --check`.

**Step 3: Check instrumentation availability**

Query the Android SDK `adb`; execute the focused Compose test only when a device is connected, otherwise report it as
compiled but not executed.
