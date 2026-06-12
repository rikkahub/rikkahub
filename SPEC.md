# Spec: CI Warning Burn-Down + Code Quality & Performance Audit

Status: awaiting human review (spec-driven-development Phase 1 gate).
Source CI evidence: master run `27388701759` (commit `22d9c8f0`, success, 2026-06-12) cross-checked
against run `27372816039` — the deduplicated warning inventory is identical between the two
(line-number drift only), so this inventory is stable, not a one-run fluke.

## Objective

Eliminate every warning the CI pipeline currently emits on master (Kotlin compiler warnings,
deprecations, Android Lint baseline drift, GitHub Actions runner deprecation), then run a
material-only code-quality audit and a measure-first performance audit over the most-touched
code, and LAND all of it as reviewed, test-covered, individually-committed fixes.

Success looks like:

- A master CI run whose compile steps emit **zero** `w:` Kotlin warnings outside the vendored
  `material3/material-color-utilities` submodule.
- An Android Lint baseline with **zero** stale entries (currently 32 "listed but not found").
- No `##[warning]` runner annotations (Node 20 actions deprecation resolved before the
  2026-06-16 forced-Node-24 cutover).
- Every quality/perf change justified by evidence (git-log heat / measurement) in its commit body.
- **No public-behavior change**: schemas, serialized formats, web API routes, CLI of bundled
  tools, and DB migrations are bit-for-bit compatible.

User constraint honored: each fix is a separate task with its own verification; root-cause fixes
preferred over suppression; suppression only with a written reason at the suppression site.

## Tech Stack

- Kotlin / Android multi-module Gradle build, JetBrains Runtime 21 (pinned via
  `gradle/gradle-daemon-jvm.properties`), Jetpack Compose, Material 3.
- Modules: `:app` (flavored `play`/`sideload`), `:ai`, `:ai-runtime` (neutral, gated by CI
  invariants P1–P3/P6), `:common`, `:search`, `:speech`, `:document`, `:highlight`, `:material3`
  (vendors `material-color-utilities` as a git submodule), `:web` (+ `web-ui` pnpm frontend,
  excluded in CI), `:automation`, `:workspace`.
- Tests: JUnit JVM unit tests per module; AndroidX instrumented tests exist but CI runs JVM only.
- CI: `.github/workflows/ci.yml` — compile both flavors → ai-runtime boundary gates → P6 token
  gate → P-FLAVOR classpath gate → unit tests → `lintSideloadDebug` (baseline-gated).

## Commands

All commands run from repo root. `:web:buildWebUi` requires pnpm and is excluded everywhere,
mirroring CI.

```bash
# Compile (both app flavors + all library modules) — the step whose warnings we are killing
./gradlew compilePlayDebugKotlin compileSideloadDebugKotlin -x :web:buildWebUi --stacktrace

# Unit tests (sideload variant for :app + unflavored aggregate for library modules — keep BOTH)
./gradlew testSideloadDebugUnitTest testDebugUnitTest -x :web:buildWebUi --stacktrace

# Android Lint (baseline-gated)
./gradlew lintSideloadDebug -x :web:buildWebUi --stacktrace

# Regenerate lint baseline after intentionally clearing fixed items
./gradlew :app:updateLintBaseline

# Warning harvest for before/after proof (local)
./gradlew compilePlayDebugKotlin compileSideloadDebugKotlin -x :web:buildWebUi 2>&1 \
  | grep -E '^w: ' | sort -u > /tmp/warns-after.txt
```

CI log harvest (for the LAND verification step):

```bash
gh run list --branch master --limit 5
gh api repos/<owner>/rikkahub/actions/runs/<id>/logs > /tmp/logs.zip   # `gh run view --log` can exit 0 with empty output; use the API zip
```

## Project Structure

```
app/src/main/java/me/rerere/rikkahub/   → app UI, ViewModels, services, data layer
app/src/sideload/java/                  → sideload-only (shell/terminal tools) — must NEVER reach Play classpath
app/src/test/java/                      → :app JVM unit tests
ai/src/main/java/me/rerere/ai/          → provider SDK (OpenAI/Google/Claude), UIMessage model
ai-runtime/src/                         → neutral chat-turn runtime (P1–P3/P6 CI gates apply)
common/, search/, speech/, document/    → library modules, each with own src/test
material3/material-color-utilities/    → VENDORED SUBMODULE — read-only, never edit
.github/workflows/ci.yml               → the warning-emitting pipeline
app/lint-baseline.xml                  → lint gate baseline (32 stale entries to purge)
```

## Code Style

Match `.editorconfig` (Kotlin 4-space indent, max line 120) and existing idiom. Example of the
expected shape of a root-cause warning fix vs. a justified suppression:

```kotlin
// ROOT-CAUSE FIX (preferred) — Locale constructor deprecation:
//   before: Locale(langCode, countryCode)
//   after:
val locale = Locale.forLanguageTag("$langCode-$countryCode")

// JUSTIFIED SUPPRESSION (only when the API has no replacement on our minSdk) —
// AccessibilityNodeInfo.recycle() is a no-op from API 33 but still required below it;
// minSdk is 26, so the call must stay:
@Suppress("DEPRECATION") // recycle() required for API < 33 (no-op above); minSdk 26
node.recycle()
```

Commit shape (per repo history): `<area>: <imperative>` with a body stating root cause /
evidence. One logical change per commit. No drive-by reformatting.

## Scope: CI Warning Inventory (89 deduplicated `w:` lines + lint + runner)

Harvested from run 27388701759; line numbers as of `22d9c8f0`. Grouped by fix strategy.

### W1 — Code-smell warnings (mechanical, compiler-verified root-cause fixes)

| Site | Warning | Fix |
|---|---|---|
| `ai/.../ClaudeProvider.kt:122,125,166,169`, `GoogleProvider.kt:143,207,210`, `OpenAIProvider.kt:82,85,115,199,202,382`, `openai/ChatCompletionsAPI.kt:101,104`, `openai/ResponseAPI.kt:110`, `search/.../MetasoSearchService.kt:77`, `ZhipuSearchService.kt:76`, `speech/.../SystemTTSProvider.kt:112` | Unnecessary safe call on non-null `ResponseBody`/`TextToSpeech` | Drop `?.` (OkHttp 4/5 `response.body` is non-null) |
| `app/.../PreferencesStore.kt:405,684` | Unnecessary safe call on non-null `Uuid` | Drop `?.` |
| `app/.../WebDavClient.kt:378,382`, `ChatMessageReasoning.kt:209` | Unnecessary `!!` on non-null `String` | Drop `!!` |
| `ai/.../ErrorParser.kt:46`, `common/.../JsonExpression.kt:359` | Redundant `else` in exhaustive `when` | Remove `else` branch |
| `app/.../MigrationUtils.kt:85` | No cast needed | Remove cast |
| `app/.../ViewText.kt:190` | Redundant conversion call | Remove call |
| `app/.../EmbeddingMemoryRecaller.kt:110` | Condition always `false` (`vector == null` already implied by `usable`) | Restructure the `usable` predicate so the null-check appears once (early-`continue` on `vector == null`, then check the remaining conditions) |
| `app/src/test/.../RouteUtilsPathBoundaryTest.kt:82` | Java type mismatch `File?` vs `File` | Handle nullable `File` explicitly in the test |
| `ai-runtime/src/test/.../ChatTurnRuntimeDeterminismTest.kt:300` | Test name contains `*` (Windows-unsafe) | Rename test (JVM-only behavior unchanged) |
| `ai-runtime/src/test/.../ToolArgumentsLenientParseTest.kt:36` | Redundant `Json` default-format creation | Use `Json.Default` / shared instance |

### W2 — Deprecated platform & library APIs (root-cause migration; suppress only where the
replacement does not exist on minSdk 26)

| Site | Deprecated API | Strategy |
|---|---|---|
| `app/.../ChatMessageTranslation.kt:72,74,90,91`, `TranslatorPage.kt:218,219,241,242` | `java.util.Locale(String[,String])` constructor | `Locale.forLanguageTag(...)` / `Locale.Builder` — needs a unit test pinning tag mapping (incl. legacy codes `iw/in/ji` Locale normalization) |
| `common/.../AcceptLang.kt:61` | `field locale: Locale!` (deprecated accessor) | Use the non-deprecated accessor; pin with unit test on Accept-Language output |
| `app/.../ContextUtil.kt:155,208` | `Intent.ACTION_MEDIA_SCANNER_SCAN_FILE` | `MediaScannerConnection.scanFile(...)` |
| `app/.../GenerationForegroundService.kt:147` | `WifiManager.WIFI_MODE_FULL_HIGH_PERF` | API 29+: `WIFI_MODE_FULL_LOW_LATENCY`; keep old constant behind SDK check below 29 (minSdk 26) with suppression+reason on that branch |
| `app/.../NsdServiceRegistrar.kt:118,119` | `WifiManager.connectionInfo` / `WifiInfo.ipAddress` | `ConnectivityManager` + `LinkProperties` IPv4 lookup; unit-testable address-selection helper extracted with test |
| `app/.../AccessibilityRuntime.kt:204–498 (recycle ×8), 513 (isChecked)` | `AccessibilityNodeInfo.recycle()` no-op ≥ API 33; `isChecked` deprecated in API 36 | `recycle()`: suppression with written reason (required < API 33; no replacement). `isChecked`: migrate to the `getChecked()`-replacement only if available at compileSdk 37 without behavior change, else suppress with reason |
| `app/src/sideload/.../WorkspaceTerminalSession.kt:191` | `InputMethodManager.SHOW_IMPLICIT` | Migrate to non-deprecated show API if equivalent on minSdk; else suppress with reason |
| `speech/.../SystemTTSProvider.kt:79` | Overrides deprecated `UtteranceProgressListener.onError(String)` w/o `@Deprecated` | Mark override `@Deprecated` (it must stay — platform calls it on API < 21-era path; the non-deprecated `onError(String,Int)` overload must also be implemented if not already) |

### W3 — Deprecated Compose / Material3 APIs (mechanical migrations)

| Site | Deprecated | Replacement |
|---|---|---|
| `app/.../TTSProviderConfigure.kt` (11, 67, 241, 411, 452, 674, 715, 807, 890, 950), `TranslatorPage.kt:33` | `MenuAnchorType` typealias | `ExposedDropdownMenuAnchorType` |
| `app/.../Tooltip.kt:18` | `rememberTooltipPositionProvider(spacing)` | new overload with `TooltipAnchorPosition.Above` (same behavior per deprecation note) |
| `app/.../ChatPage.kt:113` | `currentWindowDpSize()` (removal pending) | `LocalWindowInfo` |
| `app/.../ImageGalleryScreen.kt:66`, `SettingWebPage.kt:79` | `LocalClipboardManager` | `LocalClipboard` (suspend API — wrap call sites in the existing scope) |

### W4 — Internal deprecations & opt-ins (suppress-with-reason or annotate)

| Site | Warning | Strategy |
|---|---|---|
| `ai/.../Message.kt:446,447,613–615` | references to own deprecated `ToolCall`/`ToolResult`/`Search` (self-references inside the deprecated class + exhaustive `when` over the sealed hierarchy) | `@Suppress("DEPRECATION")` scoped to the declaration/branch with reason "legacy wire-format kept for DB/preference migration compatibility" — these subclasses CANNOT be removed (serialized in user DBs; see `MigrationUtils.kt:34–38`) |
| `app/src/test/.../Migration_15_16DecisionTest.kt:18,30` | constructs deprecated parts | `@Suppress("DEPRECATION")` — the test intentionally builds legacy payloads |
| `app/.../McpManager.kt:585` | needs `ExperimentalSerializationApi` opt-in | Explicit `@OptIn` (decision made consciously, not silenced globally) |
| `app/.../RouteActivity.kt:175,177` | needs `ExperimentalCoilApi` | Explicit `@OptIn` |
| `app/.../ModelList.kt:622`, `SearchVM.kt:47` | `FlowPreview` (`debounce`) | Explicit `@OptIn(FlowPreview::class)` |

### W5 — Out of scope (vendored)

`material3/material-color-utilities/kotlin/dynamiccolor/ColorSpec2025.kt:904,1107,1253`
(unnecessary `!!`) — vendored git submodule, never edited. Documented won't-fix.

### W6 — Lint & workflow warnings

1. **Lint baseline drift**: 32 baseline entries no longer match → regenerate via
   `./gradlew :app:updateLintBaseline` so the gate only filters real pre-existing findings.
2. **78 unbaselined lint warnings**: not in the console log (lint reports only upload on
   failure) — generate locally, triage: fix the material ones (correctness/perf categories),
   leave purely-stylistic ones baselined with the regenerated baseline. Triage table goes in the
   PR body.
3. **Node 20 actions runner deprecation** (forced Node 24 on 2026-06-16 — four days away):
   bump `actions/checkout`, `actions/setup-java`, `gradle/actions/setup-gradle` to their
   Node-24-ready major versions if available; otherwise set
   `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true` at workflow level and verify green. This is a CI
   config change — allowed because the task explicitly includes CI warning fixes; gate
   semantics (boundary checks, P-FLAVOR, tests, lint) must remain byte-identical.

## Scope: Code Quality Audit (lens: code-review-and-quality + code-simplification)

Target = git-log heat, last 6 months (commits touching file):
`ChatService.kt` (87, 1919 LOC), `PreferencesStore.kt` (52), `ChatInput.kt` (40),
`RouteActivity.kt` (40), `GenerationHandler.kt` (33), `ClaudeProvider.kt` (32),
`ChatCompletionsAPI.kt` (29), `ChatPage.kt`/`ChatList.kt`/`ChatVM.kt`/`ChatMessage.kt` (26–28).

Rules of engagement (from the two lens skills):

- **Material findings only** — correctness hazards, duplicated logic with drift risk, dead
  code, functions doing N jobs that the next change will trip over. NOT naming bikesheds,
  NOT reformatting, NOT "could be 2 lines shorter".
- **Preserve behavior exactly** — every simplification must keep all existing tests green
  unmodified; each shipped finding carries its own regression test where behavior-adjacent.
- Findings that are real but out of scope get filed as issues, not bundled.
- Approval standard: change definitely improves code health; perfection not required.

Audit deliverable BEFORE fixes: a findings list (file:line, severity, why material, proposed
fix) committed to the PR description — fixes land only for accepted-material findings, one
commit each.

## Scope: Performance Audit (lens: performance-optimization — measure first)

**Hard rule: no optimization lands without a before/after measurement in the commit body.**
Environment constraint: no Android device/emulator in the loop — measurements must be
JVM-reproducible (JUnit micro-benchmarks of pure code: `measureTime`/iteration counts/allocation
counters) or Compose-compiler-report based. Device-only claims (frame timing) are out of scope
and noted as such.

Candidate hot paths (grounded, to be MEASURED before touching):

1. **`HighlightCodeVisualTransformation.filter` — `runBlocking` on the UI thread**
   (`app/.../richtext/HighlightCodeBlock.kt:522`): `VisualTransformation.filter` runs
   synchronously on the composition/layout path for every text change and blocks on
   `highlighter.highlight(...)`. Measure: JVM benchmark of `highlight()` latency for
   representative code sizes (1 KB / 10 KB / 100 KB). If material (> a few ms at realistic
   sizes), restructure to pre-computed/async highlighting state outside the transformation;
   the fallback `catch (e: Exception) → plain text` must keep identical behavior.
2. **Streaming chunk merge** (`ai/.../Message.kt:37 appendChunk` + throttled publish in
   `ChatService.kt` ~221): per-chunk list rebuild during streaming. Measure allocations/op and
   ns/op across a 1k-chunk synthetic stream via a JVM benchmark in `:ai` tests. Existing
   throttle may already make this immaterial — if so, record "measured, not material, no
   change" in the audit notes and DON'T touch it.
3. **`ChatList.kt:617 filteredMessages`** recomputed via `remember(conversation.messageNodes,
   searchQuery)`: verify key stability (does `messageNodes` identity churn per chunk?) with a
   recomposition-count probe in a Compose JVM/robolectric-free test if feasible; otherwise
   document as device-bound and skip.
4. **Regex/JSON hygiene**: already verified good (regexes and `Json { }` instances are
   top-level `val`s across `richtext/`, providers, and caches) — one test-only redundant `Json`
   (W1). No production change expected; recorded as audited-clean.

Guard step (skill step 5): each landed perf fix adds a regression test or benchmark assertion
(loose threshold, CI-stable — e.g. "no allocation growth per chunk", not wall-clock).

## Testing Strategy

- Framework: JUnit (JVM) per module under `<module>/src/test/java`; `:app` tests run via
  `testSideloadDebugUnitTest`, library modules via `testDebugUnitTest`.
- Every behavior-adjacent fix ships a test in the same commit (Locale tag mapping,
  Accept-Language header output, NSD IPv4 selection helper, EmbeddingMemoryRecaller predicate,
  perf benchmarks-as-tests).
- Purely mechanical compiler-verified fixes (dropping a redundant `?.`/`!!`/`else`) are
  verified by: clean compile with the warning gone + the existing test suite green. Adding a
  test that "the safe call is absent" would be testing the compiler — not done.
- Full verification per milestone: compile (both flavors) + both test tasks + lint, all with
  `-x :web:buildWebUi`.

## Boundaries

- **Always:**
  - Run compile + tests + lint locally before each commit; one logical change per commit.
  - Re-harvest the warning list from the actual build output before and after each milestone
    (before/after proof in the PR body).
  - Keep `:ai-runtime` neutral (CI gates P1–P3, P6) and the Play classpath free of
    shell/write/terminal symbols (P-FLAVOR).
  - Write the reason at the suppression site for every `@Suppress`.
  - Branch from master as `fix/ci-warnings-quality-perf` (already checked out); PR per milestone.
- **Ask first:**
  - Any new Gradle dependency (incl. a benchmark library — prefer plain JUnit timing first).
  - Changing CI gate semantics beyond the actions/Node-24 bump (e.g. adding a
    warnings-as-errors ratchet — see Open Questions).
  - Removing the deprecated `ToolCall`/`ToolResult`/`Search` classes (serialized-format risk).
  - Any fix whose only correct form changes visible UI behavior (e.g. clipboard suspend API
    altering toast timing).
- **Never:**
  - Edit `material3/material-color-utilities` (vendored submodule).
  - Change public behavior: web API routes/schemas, DB schema/migration outputs, serialized
    preference/conversation formats, exported intents — no breaking changes.
  - Suppress a warning without a written reason; blanket module-level suppressions.
  - Delete or weaken a failing test to get green; commit secrets or `google-services.json`.

## Roadmap

Sequential milestones; each is a shippable increment (own PR, CI green, mergeable alone).

- **M1 — Mechanical compiler-warning burn-down (W1):** All code-smell Kotlin warnings
  (unnecessary safe calls/`!!`, redundant `when`-`else`/casts/conversions, always-false
  condition, test hygiene) fixed at root cause; compile log shows zero W1-class warnings.
- **M2 — Deprecation & opt-in burn-down (W2+W3+W4):** Platform/Compose/internal deprecations
  migrated to replacement APIs with tests where behavior-adjacent; remaining deprecations
  suppressed only with written reasons; zero deprecation/opt-in warnings outside the vendored
  submodule.
- **M3 — CI workflow + lint hygiene (W6):** Node-24-ready actions, lint baseline regenerated
  (0 stale entries), 78 lint warnings triaged with material ones fixed; CI run shows no
  `##[warning]` annotations and an accurate baseline.
- **M4 — Code-quality audit + material fixes:** Findings list over the git-log-hot files
  (ChatService, PreferencesStore, ChatInput, GenerationHandler, providers, Chat* UI) published;
  each accepted material finding landed as its own tested commit; zero cosmetic churn.
- **M5 — Performance audit + proven optimizations:** JVM-measurable hot paths benchmarked
  (highlight-on-UI-thread, streaming chunk merge, ChatList recomposition keys); only
  measured-material fixes landed, each with before/after numbers in the commit body and a
  regression guard; non-material candidates documented as "measured, no change".

## Success Criteria

1. Post-merge master CI run: `grep -cE '^w: '` over the compile steps returns 0 for
   non-vendored paths (vendored `ColorSpec2025.kt` lines are the only permitted remainder, and
   only if `:material3` compilation cannot exclude them without editing the submodule).
2. Lint summary line shows no "listed in the baseline file but not found" entries.
3. No `##[warning]` runner annotation in the CI run.
4. `testSideloadDebugUnitTest testDebugUnitTest` and `lintSideloadDebug` green; all four CI
   boundary gates (P1–P3, P6, P-FLAVOR) pass untouched.
5. Every suppression in the diff has an inline written reason; `git grep -n '@Suppress' -- ':!material3'`
   over changed files shows no bare suppressions.
6. Each perf commit body contains a before/after measurement; each quality commit body names
   the material defect it removes.
7. Zero changes to serialized formats: migration tests (`Migration_15_16DecisionTest`,
   `MigrationIdempotenceTest`, conservation property tests) pass unmodified.

## Assumptions

1. "CI run terakhir di master" = the latest **completed** run (27388701759, success); its
   warning inventory was verified stable against the previous run (27372816039).
2. "Setiap perbaikan = task terpisah dengan test" is read as: every task carries an explicit
   verification step, and every behavior-adjacent fix carries a dedicated test — purely
   mechanical, compiler-verified one-token removals are covered by the existing suite plus the
   warning's disappearance, not by synthetic tests of compiler output.
3. The vendored `material-color-utilities` submodule is out of scope even though it emits 3
   warnings; editing vendor code violates the repo's own CI comment and update path.
4. The deprecated `UIMessagePart.ToolCall`/`ToolResult`/`Search` classes must be RETAINED
   (their `@SerialName`s live in user databases; migration maps depend on them) — fixing those
   warnings means scoped suppression with reason, not removal.
5. Performance evidence must be JVM-reproducible because no device/emulator is assumed in the
   delivery loop; frame-level Compose claims are documented but not benchmarked on-device.
6. The Node-24 actions migration counts as an in-scope CI warning fix (it IS a CI warning),
   with the constraint that gate semantics stay identical.
7. Lint's 78 warnings are triaged fix-material/baseline-rest rather than fix-all: the lint gate
   is baseline-based and warnings do not fail CI; fixing all 78 (many stylistic) would violate
   the "material, not cosmetic" instruction.
8. Quality/perf audits cover the git-log-hot set listed above, not the whole repo — the user
   scoped the audit to "area yang paling sering disentuh".

## Open Questions

1. Should a **warning ratchet** be added to CI after M1–M3 (fail the build if new Kotlin
   compiler warnings appear, e.g. count-based or `-Werror` on clean modules)? It prevents
   regrowth but is a gate-semantics change — flagged as ask-first.
2. `LocalClipboardManager → LocalClipboard` migration changes the call shape to suspend; if any
   call site's user-visible timing changes (clipboard toast on some OEMs), is a minor UX timing
   difference acceptable, or should those two sites be suppressed-with-reason instead?
3. For lint findings already filtered by the baseline (188 warnings + 36 errors): leave fully
   baselined (status quo, assumed), or open a follow-up backlog issue enumerating them?
4. If the `runBlocking` highlight path proves material, the proper fix is an async
   pre-highlighted state — slightly visible behavior (briefly unhighlighted text while typing
   in code-edit fields). Acceptable, or treat as ask-first UX change?

## Plan (Phase 2 summary)

Dependency order: M1 → M2 → M3 are independent of audits and shrink diff noise first; M4 then
audits a warning-clean tree; M5 last because quality fixes may move the code it measures.
Within each milestone, tasks are one-commit-each and independently revertable. Verification
checkpoint at every milestone boundary = full local CI-equivalent run (compile + tests + lint).

## Tasks (Phase 3 — initial breakdown)

M1 (one commit per row of the W1 table, grouped per file where lines are adjacent):
- [ ] Task: drop unnecessary safe calls in `:ai` providers (Claude/Google/OpenAI/ChatCompletions/Response)
  - Acceptance: zero "Unnecessary safe call" warnings from `:ai`; Verify: compile + `:ai` tests
  - Files: 5 provider files
- [ ] Task: same for `:search`, `:speech`, `:app` (`PreferencesStore`, `WebDavClient`, `ChatMessageReasoning`)
- [ ] Task: redundant `when`-`else` (`ErrorParser`, `JsonExpression`), no-cast (`MigrationUtils`), redundant conversion (`ViewText`)
- [ ] Task: restructure `EmbeddingMemoryRecaller.kt:110` usable-predicate + unit test pinning recall partitioning
- [ ] Task: test hygiene — `RouteUtilsPathBoundaryTest` nullability, determinism-test rename, shared `Json` in lenient-parse test

M2 (per W2/W3/W4 rows; each behavior-adjacent row ships its test in the same commit):
- [ ] Locale constructor migration (`ChatMessageTranslation`, `TranslatorPage`) + tag-mapping test
- [ ] `AcceptLang` accessor + header-output test; `ContextUtil` MediaScannerConnection
- [ ] `GenerationForegroundService` wifi-lock SDK split; `NsdServiceRegistrar` LinkProperties + IPv4-helper test
- [ ] `AccessibilityRuntime` recycle/isChecked (suppress-with-reason / migrate), `WorkspaceTerminalSession` IME, `SystemTTSProvider` override annotation
- [ ] Compose migrations: `MenuAnchorType`→`ExposedDropdownMenuAnchorType` (2 files), `Tooltip`, `ChatPage` window size, clipboard ×2
- [ ] Internal deprecation suppressions with reasons (`Message.kt`, migration test) + explicit `@OptIn`s (`McpManager`, `RouteActivity`, `ModelList`, `SearchVM`)

M3:
- [ ] Actions Node-24 bump (or workflow env) — verify gate steps byte-identical; CI run shows no runner warning
- [ ] Regenerate lint baseline (0 stale); triage report of the 78 warnings; fix material subset, one commit per finding

M4:
- [ ] Produce findings list (file:line, severity, materiality) over hot files; then one tested commit per accepted finding

M5:
- [ ] Benchmark highlight latency; land async fix only if material (+ guard test)
- [ ] Benchmark `appendChunk` stream merge; land only if material (+ allocation guard)
- [ ] Verify `ChatList` remember-key stability; document or fix
- [ ] Audit-notes commit recording measured-not-material results

## Verification (skill gate)

- [x] Six core areas covered (objective, commands, structure, style, testing, boundaries)
- [ ] Human has reviewed and approved the spec  ← PENDING (this gate blocks implementation)
- [x] Success criteria specific and testable
- [x] Boundaries defined (always / ask first / never)
- [x] Spec saved to a file in the repository (`SPEC.md`, uncommitted by instruction)
