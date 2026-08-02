# Agent Run Terminal Acknowledgement Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Show the matching Agent Run terminal state before the active card performs its native exit animation.

**Architecture:** Derive a pure lifecycle stage from active, retained, latest, and eligible Run identities. A dedicated
Compose host owns the brief terminal hold and existing Material exit transition, while the card rejects stop actions in
terminal states.

**Tech Stack:** Kotlin, Jetpack Compose Animation, Material 3, JUnit 4, AndroidX Compose UI Test.

---

### Task 1: Define the lifecycle state contract

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentation.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentationTest.kt`

**Step 1: Write the failing tests**

Cover an active presentation, a matching terminal latest presentation, a latest presentation from another Run, and an
initial historical terminal presentation with no eligible active identity.

**Step 2: Run the focused test and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest"`

Expected: FAIL because the lifecycle stage contract does not exist.

**Step 3: Implement the pure transition selector**

Add a four-stage enum and a transition result containing the presentation that may remain visible. Match terminal
snapshots by exact Run ID and reject live or unrelated latest snapshots.

**Step 4: Re-run the focused test**

Expected: PASS.

### Task 2: Own terminal acknowledgement in the card host

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Add Compose behavior tests**

Verify that a terminal card does not expose Stop and that an initially active host presents the matching terminal state
before it exits. Verify that an initially historical terminal latest Run does not create an active card.

**Step 2: Implement the host**

Move retained-card lifecycle state from `ChatPage` into `AgentRunActiveCardHost`. Cancel pending exits when a new active
Run appears, wait briefly for independently emitted terminal data, hold a matching terminal state, then collapse it.

**Step 3: Filter stale stop actions**

Render Stop only while the presentation is live. Clear retained stopping feedback as soon as a terminal presentation is
shown.

**Step 4: Compile Android tests**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: PASS.

### Task 3: Regression and static verification

**Files:**
- Verify only.

**Step 1: Run focused JVM regressions**

Run the Agent Run presentation, navigation, timeline-follow, and approval submission unit tests.

Expected: PASS.

**Step 2: Compile the app and Android tests**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`.

Expected: PASS.

**Step 3: Inspect the patch**

Run `git diff --check`, check the 120-character line limit, and confirm `_apk_dl2/` remains untouched.
