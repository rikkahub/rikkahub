# Instrumentation Test Debt Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every debug instrumentation source compile and every debug instrumentation test pass on the connected Galaxy S23 FE without changing production behavior.

**Architecture:** Repair only test code: make the app package assertion variant-aware, remove the dependency-less generated `highlight` placeholder, verify oversized migration state through scalar SQL metadata, and use the stable activity-hosted Compose rule. Each defect has its own red-green device cycle and commit, followed by repository-wide device and non-device gates.

**Tech Stack:** Kotlin, Android Gradle Plugin, AndroidX Test/JUnit4, Room migration testing, SQLite, Jetpack Compose UI testing, ADB.

## Global Constraints

- Do not change production database migration, UI, or application behavior unless new test-first evidence identifies a production defect.
- Do not increase the process-global CursorWindow size to make a test pass.
- Do not disable, ignore, or condition tests on the Samsung device model.
- Do not add AndroidX instrumentation dependencies solely to retain the generated `highlight` placeholder test.
- Run device tests against `RZCX71NXRPB` through `ADB_SERVER_SOCKET=tcp:100.69.79.32:5037`.
- Preserve the existing assertions that the trace card renders and Copy delivers the active trace ID.

---

### Task 1: Make instrumentation package coverage meaningful and compilable

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ExampleInstrumentedTest.kt:3-20`
- Delete: `highlight/src/androidTest/java/me/rerere/highlight/ExampleInstrumentedTest.kt`

**Interfaces:**
- Consumes: generated `me.rerere.rikkahub.BuildConfig.APPLICATION_ID: String` and `InstrumentationRegistry.getInstrumentation().targetContext`.
- Produces: a variant-aware app target-package assertion; no `highlight` instrumentation source set requiring undeclared AndroidX/JUnit dependencies.

- [ ] **Step 1: Confirm both red baselines**

Run:

```bash
./gradlew :highlight:compileDebugAndroidTestKotlin --console=plain
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell am instrument -w -r \
  -e class me.rerere.rikkahub.ExampleInstrumentedTest \
  me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: the Gradle command fails with unresolved AndroidX/JUnit references in `highlight/ExampleInstrumentedTest.kt`; the device test fails because expected `me.rerere.rikkahub` differs from actual `me.rerere.rikkahub.debug`.

- [ ] **Step 2: Replace the hard-coded app ID with the generated variant ID**

Change the imports and assertion in `app/src/androidTest/java/me/rerere/rikkahub/ExampleInstrumentedTest.kt` to:

```kotlin
package me.rerere.rikkahub

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
    }
}
```

- [ ] **Step 3: Remove the generated highlight placeholder**

Delete exactly this file and leave `highlight/build.gradle.kts` unchanged:

```text
highlight/src/androidTest/java/me/rerere/highlight/ExampleInstrumentedTest.kt
```

- [ ] **Step 4: Build and install the updated app instrumentation APK**

Run:

```bash
./gradlew :highlight:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB install -r \
  app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB install -r \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Expected: Gradle reports `BUILD SUCCESSFUL`; both installs report `Success`.

- [ ] **Step 5: Verify the package test is green**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell am instrument -w -r \
  -e class me.rerere.rikkahub.ExampleInstrumentedTest \
  me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (1 test)` and `INSTRUMENTATION_CODE: -1` with no failure stack.

- [ ] **Step 6: Commit the package and placeholder repairs**

```bash
git add app/src/androidTest/java/me/rerere/rikkahub/ExampleInstrumentedTest.kt \
  highlight/src/androidTest/java/me/rerere/highlight/ExampleInstrumentedTest.kt
git commit -m "test: make instrumentation package checks variant-aware"
```

### Task 2: Verify oversized migration rows without loading the blob

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/Migration_11_12_Test.kt:518-550`

**Interfaces:**
- Consumes: the existing `largeNodesMigrated: Int`, `largeConversationId: String`, and Room test database returned by `runMigrationsAndValidate`.
- Produces: scalar verification values `largeConvNodesCleared: Boolean` and `largeConvNodesLength: Long`; the test never calls `Cursor.getString` on the oversized `nodes` column.

- [ ] **Step 1: Reconfirm the migration red baseline**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell am instrument -w -r \
  -e class me.rerere.rikkahub.data.db.migrations.Migration_11_12_Test#migrate11To12_handlesVeryLargeConversations \
  me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: FAIL with `SQLiteBlobTooBigException` at the verification call to `largeConvCursor.getString(0)`.

- [ ] **Step 2: Replace oversized text retrieval with scalar SQL assertions**

Add this import with the other JUnit assertions:

```kotlin
import org.junit.Assert.assertFalse
```

Replace the block beginning with `val largeConvCursor = db.query(` through its diagnostic `Log.i` call with:

```kotlin
        val largeConvCursor = db.query(
            "SELECT nodes = '[]', length(nodes) FROM conversationentity WHERE id = ?",
            arrayOf(largeConversationId)
        )
        assertTrue(largeConvCursor.moveToFirst())
        val largeConvNodesCleared = largeConvCursor.getInt(0) == 1
        val largeConvNodesLength = largeConvCursor.getLong(1)
        largeConvCursor.close()

        if (largeNodesMigrated == 0) {
            assertFalse("Skipped large conversation should preserve nodes", largeConvNodesCleared)
            assertTrue("Preserved nodes should contain the original JSON", largeConvNodesLength > 2L)
        } else {
            assertEquals("All large nodes should migrate together", largeNodes.size, largeNodesMigrated)
            assertTrue("Migrated large conversation nodes should be cleared", largeConvNodesCleared)
            assertEquals("Cleared nodes should be []", 2L, largeConvNodesLength)
        }

        val normalConvCursor = db.query(
            "SELECT nodes FROM conversationentity WHERE id = ?",
            arrayOf(normalConversationId)
        )
        assertTrue(normalConvCursor.moveToFirst())
        val normalConvNodes = normalConvCursor.getString(0)
        assertEquals("Normal conversation nodes should be cleared", "[]", normalConvNodes)
        normalConvCursor.close()

        Log.i(
            "Migration_11_12_Test",
            "Large conversation migration result: $largeNodesMigrated nodes migrated, " +
                "nodes field: ${if (largeConvNodesCleared) "cleared" else "preserved"}"
        )
```

- [ ] **Step 3: Rebuild and install the instrumentation APK**

Run:

```bash
./gradlew :app:assembleDebugAndroidTest --console=plain
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB install -r \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Expected: Gradle reports `BUILD SUCCESSFUL`; install reports `Success`.

- [ ] **Step 4: Verify the migration test is green on Samsung SQLite**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell am instrument -w -r \
  -e class me.rerere.rikkahub.data.db.migrations.Migration_11_12_Test#migrate11To12_handlesVeryLargeConversations \
  me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (1 test)` with no `SQLiteBlobTooBigException`. On this Galaxy the skipped branch must preserve a scalar length greater than `2`.

- [ ] **Step 5: Commit the migration test repair**

```bash
git add app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/Migration_11_12_Test.kt
git commit -m "test: avoid loading oversized migration blobs"
```

### Task 3: Host the voice status-card test in a Compose activity

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/voiceagent/VoiceAgentStatusCardsTest.kt:3-15`

**Interfaces:**
- Consumes: `androidx.compose.ui.test.junit4.createComposeRule`, existing `debugImplementation(libs.androidx.ui.test.manifest)`, `VoiceAgentStatusCards`, and `VoiceAgentUiState(traceId: String)`.
- Produces: an activity-hosted Compose hierarchy in which the existing display and click assertions execute.

- [ ] **Step 1: Reconfirm the Compose red baseline**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell am instrument -w -r \
  -e class me.rerere.rikkahub.voiceagent.VoiceAgentStatusCardsTest \
  me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: FAIL with `IllegalStateException: No compose hierarchies found in the app`.

- [ ] **Step 2: Switch from the experimental v2 rule to the stable hosted rule**

Replace:

```kotlin
import androidx.compose.ui.test.junit4.v2.createComposeRule
```

with:

```kotlin
import androidx.compose.ui.test.junit4.createComposeRule
```

Keep the rule declaration and test body unchanged:

```kotlin
    @get:Rule
    val composeRule = createComposeRule()
```

- [ ] **Step 3: Rebuild and install the instrumentation APK**

Run:

```bash
./gradlew :app:assembleDebugAndroidTest --console=plain
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB install -r \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Expected: Gradle reports `BUILD SUCCESSFUL`; install reports `Success`.

- [ ] **Step 4: Verify rendering and Copy behavior on the Galaxy**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s RZCX71NXRPB shell am instrument -w -r \
  -e class me.rerere.rikkahub.voiceagent.VoiceAgentStatusCardsTest \
  me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (1 test)`; the test finds `Trace ID`, `trace-123`, and `Copy`, and the callback assertion passes.

- [ ] **Step 5: Commit the Compose test repair**

```bash
git add app/src/androidTest/java/me/rerere/rikkahub/voiceagent/VoiceAgentStatusCardsTest.kt
git commit -m "test: host voice status cards in compose activity"
```

### Task 4: Run the repository-wide release-readiness gates

**Files:**
- Verify only: all Gradle modules and the connected Galaxy S23 FE.

**Interfaces:**
- Consumes: the three preceding commits and the connected device `RZCX71NXRPB`.
- Produces: fresh repository-wide evidence for instrumentation, unit tests, lint, and debug assembly.

- [ ] **Step 1: Run every module's debug instrumentation suite**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 ./gradlew connectedDebugAndroidTest --console=plain
```

Expected: `BUILD SUCCESSFUL`, with no instrumentation compilation errors, failed tests, or unavailable device errors.

- [ ] **Step 2: Run the complete non-device gate**

Run:

```bash
./gradlew test lint assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL` with zero failed unit tests and zero lint errors.

- [ ] **Step 3: Inspect repository state and commits**

Run:

```bash
git status --short --branch
git log -5 --oneline --decorate
git diff origin/master...HEAD --check
```

Expected: no uncommitted files; the design commit and three focused test commits are present; `git diff --check` emits no output.

- [ ] **Step 4: Record completion without creating an empty commit**

Do not create a verification-only commit. Report the exact Gradle exit results, connected test count, device model, and remaining branch divergence. Push only if the user explicitly requests synchronization or the active branch-finishing workflow requires it.

### Task 5: Remove the stale speech package placeholder

**Files:**
- Delete: `speech/src/androidTest/java/me/rerere/tts/ExampleInstrumentedTest.kt`

**Interfaces:**
- Consumes: the Task 4 Galaxy failure proving the generated test expects `me.rerere.tts.test` while the module target package is `me.rerere.speech.test`.
- Produces: no speech instrumentation source set requiring a package-name-only test; no dependency or production change.

- [ ] **Step 1: Confirm the red evidence**

Read `.superpowers/sdd/task-4-report.md` and confirm the Galaxy connected gate records `expected me.rerere.tts.test` and actual `me.rerere.speech.test` for `speech/ExampleInstrumentedTest.kt`.

- [ ] **Step 2: Delete the generated placeholder**

Delete exactly:

```text
speech/src/androidTest/java/me/rerere/tts/ExampleInstrumentedTest.kt
```

Do not modify `speech/build.gradle.kts`; the deleted test only asserts a generated package name and covers no speech behavior.

- [ ] **Step 3: Verify the speech instrumentation source set is clean**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :speech:compileDebugAndroidTestKotlin --console=plain
```

Expected: `:speech:compileDebugAndroidTestKotlin NO-SOURCE` and `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add speech/src/androidTest/java/me/rerere/tts/ExampleInstrumentedTest.kt
git commit -m "test: remove stale speech instrumentation placeholder"
```

### Task 6: Prove the final integrated branch on the Galaxy

**Files:**
- Verify only: all Gradle modules and `RZCX71NXRPB`.

**Interfaces:**
- Consumes: the complete branch through Task 5, the loopback ADB proxy technique proven in Task 4, and the debug `showWhenLocked` host override.
- Produces: fresh connected and non-device release-readiness evidence for the final head.

- [ ] **Step 1: Establish the exact device precondition**

Route Gradle/ddmlib through a loopback-only TCP proxy to `100.69.79.32:5037`. Send `KEYCODE_WAKEUP` without any unlock gesture, PIN, or keyguard dismissal. Poll until all conditions are true for three consecutive observations:

```text
mWakefulness=Awake
screenState=SCREEN_STATE_ON
interactiveState=INTERACTIVE_STATE_AWAKE
isKeyguardShowing=true
deviceLocked=1
```

Expected: proxy inventory lists only `RZCX71NXRPB`; the phone is awake and still securely locked.

- [ ] **Step 2: Run the repository-wide connected gate**

Run with the proxy port exported through `ANDROID_ADB_SERVER_PORT`, `ANDROID_ADB_SERVER_ADDRESS=127.0.0.1`, and `ADB_SERVER_SOCKET`:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew connectedDebugAndroidTest --console=plain
```

Expected: `BUILD SUCCESSFUL`; generated XML/textproto identifies `SM-S711B` and `RZCX71NXRPB`; all instrumentation tests pass.

- [ ] **Step 3: Run the fresh non-device gate**

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew test lint assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`, zero unit-test failures, and zero lint errors.

- [ ] **Step 4: Record final state**

Report exact test counts, device identity, lint summary, clean worktree state, and branch divergence. Tear down only the temporary loopback proxy. Do not create a verification-only commit.
