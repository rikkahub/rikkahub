# Agent Chain-of-Thought Step Identity Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep Agent step expansion and native animation state attached to the correct item during list updates.

**Architecture:** Preserve each thinking step's original message-part index and require every `ChainOfThought` caller to
provide a stable key selector. Apply Compose `key` at the iteration boundary and remove nested call-site ownership.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Compose UI Test, JUnit

---

### Task 1: Add failing identity contracts

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/components/ui/ChainOfThoughtTest.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/components/message/ChatMessageCotTest.kt`

**Step 1: Test state ownership across prepend**

Render steps B and C with explicit IDs, expand B, prepend A, and require only B details to remain visible.

**Step 2: Test original source indices**

Group text, reasoning, and tool message parts and require the reasoning/tool steps to retain their original list
positions.

**Step 3: Verify red**

Run Android-test and unit-test Kotlin compilation. Expect missing `stepKey` and `sourceIndex` APIs.

### Task 2: Implement identity at the list boundary

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt`

**Step 1: Preserve source indices**

Add `sourceIndex` to both thinking-step data classes and populate it from `fastForEachIndexed`.

**Step 2: Require and apply step keys**

Add the required `stepKey` parameter and wrap `scope.content(step)` with `key(stepKey(step))` in the visible-step loop.

### Task 3: Migrate every caller

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/Export.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/components/ui/ChainOfThoughtTest.kt`

**Step 1: Use source-index identity in live chat and export**

Pass `stepKey = ThinkingStep::sourceIndex` and remove nested chat-only key wrappers.

**Step 2: Give preview and fixtures explicit keys**

Use the preview label and fixture IDs as stable selectors.

**Step 3: Compile production and tests**

Run production, unit-test, and Android-test Kotlin compilation and expect success apart from the known Navigation3
opt-in warning.

### Task 4: Verify integration

**Files:**
- Verify all files changed by Tasks 1 through 3.

**Step 1: Run focused JVM tests**

Run grouping, ask-user draft, tool approval submission, Agent activity, progress, presentation, navigation, timeline,
and processing tests.

**Step 2: Run static checks**

Run changed-line 120-column checks and `git diff --check`.

**Step 3: Check instrumentation availability**

Query the Android SDK `adb`; execute the focused Compose test only with a connected device, otherwise report it as
compiled but not executed.
