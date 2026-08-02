# Agent Chain-of-Thought Expand Affordance Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make Agent reasoning and tool expansion affordances visually continuous and explicitly accessible.

**Architecture:** Keep the current immediate content lifecycle and size animation, but replace arrow swaps with one
Material-motion rotation. Put dynamic click labels and state descriptions on the stable clickable rows.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, AndroidX Compose UI Test, Android string resources

---

### Task 1: Add failing expansion semantics contracts

**Files:**
- Create: `app/src/androidTest/java/me/rerere/rikkahub/ui/components/ui/ChainOfThoughtTest.kt`

**Step 1: Test the chain-level control**

Render three steps with one collapsed-visible step. Require the “Show 2 more steps” node to expose the collapsed state,
click it, then require “Collapse” to expose the expanded state.

**Step 2: Test an individual step**

Render one expandable step. Require collapsed state semantics, click its label, then require expanded state semantics
and visible details.

**Step 3: Compile the Android tests to verify red**

Run `./gradlew :app:compileDebugAndroidTestKotlin --console=plain`; expect missing state-description semantics until the
production component is updated.

### Task 2: Implement the native affordance

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

**Step 1: Add localized state and step-action labels**

Add expanded, collapsed, expand-step, and collapse-step strings in default and Simplified Chinese resources.

**Step 2: Animate the chain-level indicator**

Drive `ArrowDown01` rotation from the current chain expansion state with the theme motion scheme. Add dynamic click
labels and state descriptions to the control row.

**Step 3: Animate individual step indicators**

Use the same rotation target and motion spec for expandable steps. Add the step-specific click label and shared state
description to the clickable label row.

**Step 4: Compile production and Android-test Kotlin**

Run `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain` and expect success apart from
the known Navigation3 opt-in warning.

### Task 3: Verify integration

**Files:**
- Verify all files changed by Tasks 1 and 2.

**Step 1: Run focused JVM tests**

Run ask-user draft, tool approval submission, Agent activity, progress, presentation, navigation, timeline, and
processing tests.

**Step 2: Run static checks**

Run the 120-column scan and `git diff --check`.

**Step 3: Check instrumentation availability**

Query the Android SDK `adb`; run the focused Compose test only with a connected device, otherwise report it as compiled
but not executed.
