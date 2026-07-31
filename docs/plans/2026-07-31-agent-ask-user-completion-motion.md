# Agent Ask-User Completion Motion Implementation Plan

**Goal:** Give Agent ask-user completion a directional native transition without leaving retained form controls active.

**Architecture:** Keep question labels stable, animate immutable response frames per question, and gate every retained
editor from the latest response mode. Animate the submit row independently so layout height settles naturally.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, AndroidX Compose UI Test

---

### Task 1: Define and test the response transition contract

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/components/message/ToolApprovalActionsTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`

**Step 1: Add immutable response modes and frames**

Model editable, answered, and read-only response presentation without retaining a reference to mutable tool state.

**Step 2: Add a paused-clock Compose test**

Require an outgoing editor to be visible but disabled on the first completion frame while the saved answer enters.

**Step 3: Confirm transition cleanup**

Advance past the animation and require the old editor to disappear.

### Task 2: Integrate native ask-user motion

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`

**Step 1: Stabilize question labels**

Leave each question outside the response transition and render only its editor or captured answer inside it.

**Step 2: Apply directional completion and renewal motion**

Use vertical slide plus fade for editable/answered changes and fade for other state changes.

**Step 3: Animate and gate the submit row**

Use expand/fade and shrink/fade while immediately disabling the exiting submit action from the latest response mode.

### Task 3: Verify integration

**Files:**
- Verify all files changed by Tasks 1 and 2.

**Step 1: Compile production and Android test Kotlin**

Run Debug Kotlin and Android-test Kotlin compilation.

**Step 2: Run focused and Agent JVM tests**

Run ask-user draft, tool approval, Agent activity, progress, presentation, navigation, timeline, and processing tests.

**Step 3: Run static and device checks**

Run the 120-column scan and `git diff --check`, then query `adb` and execute instrumentation only when a device is
available.
