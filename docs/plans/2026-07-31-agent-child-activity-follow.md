# Agent Child Activity Follow Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Treat newly delegated child runs as first-class Agent activity with native follow and unseen feedback.

**Architecture:** Combine namespaced child-run IDs and timeline stable keys before passing them through the existing
single follow reducer. Keep one scroll owner and animate the optional child-section heading as a keyed lazy item.

**Tech Stack:** Kotlin, Jetpack Compose LazyColumn, JUnit

---

### Task 1: Add failing activity-key contracts

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunTimelineFollowTest.kt`

**Step 1: Test key namespaces**

Pass the same raw ID as one child and one timeline item. Require distinct `child:` and `timeline:` keys in display
order.

**Step 2: Test paused unseen accumulation**

Add one child and one timeline key while not following. Require two added/unseen activities, then require a repeated
update to add none.

**Step 3: Run the focused JVM test to verify red**

Run `./gradlew :app:testDebugUnitTest --tests "*.AgentRunTimelineFollowTest" --console=plain` and expect unresolved
`agentRunActivityKeys`.

### Task 2: Unify child and timeline activity tracking

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Implement namespaced activity keys**

Add a pure helper that prefixes child and timeline identities before concatenating them.

**Step 2: Replace timeline-only effect input**

Remember combined keys from `presentation.children` and `presentation.timeline`, retain them by run ID, and feed them
to the existing follow reducer.

**Step 3: Animate the optional child heading**

Add `animateItem()` to the keyed child-section title so its insertion/removal shares the child card motion.

### Task 3: Verify integration

**Files:**
- Verify all files changed by Tasks 1 and 2.

**Step 1: Compile production and tests**

Run production, unit-test, and Android-test Kotlin compilation. Expect success apart from known Navigation3 warnings.

**Step 2: Run focused JVM regressions**

Run activity follow, grouping, ask-user draft, tool approval, Agent activity, progress, presentation, navigation, and
processing tests.

**Step 3: Run static and device checks**

Run changed-line length checks and `git diff --check`, then query `adb` and run instrumentation only if a device is
connected.
