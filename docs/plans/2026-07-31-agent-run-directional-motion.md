# Agent Run Directional Detail Motion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make nested Run navigation communicate hierarchy with direction-aware native Compose motion.

**Architecture:** Project the immutable navigation path depth into `AgentRunDetailState`, select motion by comparing
source and target identity/depth, and keep same-Run loading/content changes on the existing subtle phase transition.

**Tech Stack:** Kotlin, StateFlow, Jetpack Compose `AnimatedContent`, JUnit 4.

---

### Task 1: Specify direction selection

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunNavigationTest.kt`

**Step 1: Add failing tests**

Cover same-Run phase changes, a deeper child destination, a shallower parent destination, and a different destination at
the same depth.

**Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest"`

Expected: FAIL because the motion model and selector do not exist yet.

### Task 2: Project hierarchy and render directional motion

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunVM.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Expose immutable navigation depth**

Add path depth to `AgentRunNavigation` and every emitted detail phase without changing path mutation behavior.

**Step 2: Add the pure motion selector**

Return phase, forward, or back motion from source and target Run identities and depths.

**Step 3: Apply native hierarchy transitions**

Use partial horizontal transitions for forward and back navigation. Retain the short vertical fade for loading, missing,
closed, same-depth, and same-Run phase changes.

### Task 3: Regression and audit

**Files:**
- Verify only.

**Step 1: Run focused regressions and compile Android tests**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunTimelineFollowTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Audit the patch**

Run `git diff --check`, inspect touched Kotlin and Markdown lines against the 120-character limit, and leave `_apk_dl2/`
untouched.
