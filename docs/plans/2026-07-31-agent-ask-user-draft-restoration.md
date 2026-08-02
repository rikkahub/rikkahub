# Agent Ask-User Draft Restoration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Preserve unsubmitted Agent question drafts across normal Android state restoration without database writes.

**Architecture:** Replace composition-only maps with an immutable serializable draft and a bounded custom Saver keyed
by stable tool execution facts. The existing form reads and copies this draft for every user interaction.

**Tech Stack:** Kotlin, kotlinx.serialization, Jetpack Compose `rememberSaveable`, JUnit 4.

---

### Task 1: Define the draft model and codec

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/components/message/AskUserAnswerDraftTest.kt`

**Step 1: Write failing draft tests**

Cover text replacement, multi-option toggling, special characters, corrupted JSON fallback, and the saved-state size
limit.

**Step 2: Run the focused test and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.AskUserAnswerDraftTest"`.

Expected: FAIL because the draft model and codec do not exist.

**Step 3: Implement the immutable model and bounded codec**

Use kotlinx serialization and return an empty draft for invalid input. Reject, rather than truncate, encoded drafts over
32 KiB when saving.

**Step 4: Re-run the focused test**

Expected: PASS.

### Task 2: Wire saved state into the real form

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`

**Step 1: Add a stable remember helper**

Key it with tool execution ID, call ID, name, and immutable input. Exclude renewable approval fields.

**Step 2: Replace mutable maps with draft copies**

Update text, single, multi, completion validation, and payload generation to read from the immutable draft.

**Step 3: Compile app and Android tests**

Run `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`.

Expected: PASS.

### Task 3: Regression and static verification

**Files:**
- Verify only.

**Step 1: Run focused JVM regressions**

Run draft, approval submission, processing status, Agent Run presentation, navigation, and timeline-follow tests.

**Step 2: Audit the patch**

Run `git diff --check`, enforce the 120-character line limit, and confirm `_apk_dl2/` remains untouched.
