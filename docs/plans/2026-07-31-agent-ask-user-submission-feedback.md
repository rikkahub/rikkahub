# Agent Ask-User Submission Feedback Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make interactive Agent answers duplicate-safe and visibly responsive while preserving approval renewal errors.

**Architecture:** Propagate a Job-returning answer handler to the interactive tool card. Reuse the existing submission
guard, present an animated native progress state, and run approval error observation before tool-specific rendering.

**Tech Stack:** Kotlin, coroutines, Jetpack Compose Animation, Material 3, JUnit 4, AndroidX Compose UI Test.

---

### Task 1: Define the asynchronous answer contract

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`

**Step 1: Change the tests to require a Job-returning handler**

Add a focused Compose test for the interactive submit button and retain the existing duplicate guard JVM coverage.

**Step 2: Compile to verify the old contract fails**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`.

Expected: FAIL until the Job-returning answer contract and submit component exist.

**Step 3: Propagate `ToolAnswerHandler`**

Add the type alias and use it in all message and chat composables. Return `ChatService.handleToolApproval(...)` from
`ChatVM.handleToolAnswer`.

### Task 2: Add duplicate-safe native submission feedback

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/components/message/ToolApprovalActionsTest.kt`

**Step 1: Add the answer submit control test**

Verify idle content, one accepted tap, `Confirm` haptic, progress semantics, and the absence of a second click action
while submitting.

**Step 2: Implement the control and submission lifecycle**

Use `ToolApprovalSubmissionState` around the returned Job. Disable chips and text fields while submitting and animate
the submit button content into a progress indicator.

**Step 3: Compile Android tests**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`.

Expected: PASS.

### Task 3: Restore shared approval error feedback

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/message/ToolApprovalSubmissionStateTest.kt`

**Step 1: Move the shared observer before tool dispatch**

Ensure `ask_user` and registered tools use the same stable identity and new-error-only haptic state.

**Step 2: Render the interactive error message**

Show `approvalStatusMessage` below the form using the Material error color without replaying historical haptics.

### Task 4: Regression and static verification

**Files:**
- Verify only.

**Step 1: Run focused unit regressions**

Run approval submission, Agent Run presentation, navigation, and timeline-follow tests.

**Step 2: Compile app and Android tests**

Run `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`.

**Step 3: Audit the patch**

Run `git diff --check`, enforce the 120-character line limit, and confirm `_apk_dl2/` remains untouched.
