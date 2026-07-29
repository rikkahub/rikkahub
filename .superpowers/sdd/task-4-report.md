## Current Result

- Status: DONE
- Code commit: `80aca989e73db6c85c768ea79f16810710c8acc7`
  (`refactor(voice): rename startup cleanup decision`)
- Summary: Startup failure cleanup now makes one atomic local-versus-external ownership claim. An external winner
  excludes local delegate work; a local winner publishes one in-flight attempt that a later caller-cancellation transfer
  joins before finishing only worker/call-job stages. The losing path never independently invokes the delegate. The
  first failed result therefore remains retryable only from a later `CleanupFailed` start and is supplied unchanged for
  suppression onto the caller's canonical cancellation. The private arbitration type is named
  `StartupLocalCleanupDecision`, avoiding the binding scan's deleted-ownership token. The Task 4 typed factory boundary
  and temporary interface-level legacy bridge are unchanged.

## Tests

- Focused verification: the exact Task 4 startup-plus-reducer command passed with `BUILD SUCCESSFUL in 10s`. Generated
  XML reports 38 selected tests, 0 skipped, 0 failures, and 0 errors: 18
  `VoiceAgentCallOrchestratorStartupTest` and 20 `VoiceAgentCallStateMachineTest`.

  ```text
  ./gradlew :app:testDebugUnitTest \
    --tests '*VoiceAgentCallStateMachineTest' \
    --tests '*VoiceAgentCallOrchestratorStartupTest'
  ```

- Targeted deleted-name verification returned exit status 1 with no matches:

  ```text
  rg --hidden -n "StartupLocalCleanup[C]laim" -g '!.git' .
  rg -n "Cleanup[C]laim" \
    app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartOperation.kt
  ```

- The deterministic race regression blocks local resource cleanup, cancels the caller, and releases one failed result.
  It proves exactly one delegate invocation, `CleanupFailed` with that first error, identity-preserved caller
  cancellation with the same error suppressed, and no remaining app-scope child job.
- Self-review: `git diff --cached --check` passed before the code commit. The completion review checked the Task 4
  requirements, both atomic-claim directions, exact-attempt result identity, clean/dirty retry admission, interface
  compatibility, and the focused test XML. Project instructions keep review work in the main thread, so the review
  checklist was applied directly rather than dispatched.

## Files Changed

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartOperation.kt` — atomically arbitrates local and external
  startup cleanup, publishes local attempts for exact joining, prevents duplicate delegate invocation, preserves
  later-start-only retry admission, and uses a binding-scan-safe private decision name.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestrator.kt` — uses the shared cancellation helpers
  for caller-cancellation handoff.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCancellation.kt` — narrowly owns canonical
  `CancellationException` unwrapping and identity-distinct suppressed-error attachment shared by startup and the
  orchestrator.
- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt` — retains interface-default compatibility
  for both typed Task 4 factories and unrelated legacy `create(...)` implementations through Task 6.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorTestFixtures.kt` — makes the Task 4 factory
  typed-only and adds an injectable final route-metadata read boundary.
- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallOrchestratorStartupTest.kt` — adds the blocked local
  cleanup versus caller-cancellation race regression.
- `.superpowers/sdd/task-4-report.md` — records the final Task 4 review-fix evidence.

## Concerns

- The focused build retains the repository's existing unresolved `ExperimentalNavigation3Api` opt-in warning. It does
  not affect compilation or the selected tests.

## Attempt Appendix

- Mechanical scan RED/GREEN: before the private-type rename, the targeted scan reported 13 occurrences of the prohibited
  helper-name token in `VoiceAgentStartOperation.kt`. After the code-only rename, the same scan returned exit status 1
  with no matches. No later-task legacy API was migrated.
- Superseded verification: the prior concurrency-fix focused run passed the same 38 tests in 9s; the final 10s run above
  verifies the binding-scan-safe rename.
- Re-review RED: With the deterministic blocked-cleanup race added before production changes, the exact focused command
  ran 38 tests and failed one assertion: the caller's canonical cancellation had no suppressed first cleanup failure.
  The local attempt had been invoked a second time and succeeded, demonstrating the check-then-act ownership race.
- Superseded verification: before this re-review fix, the prior final focused run passed 37 tests in 6s. Intermediate
  GREEN runs for the atomic-claim implementation were superseded by the final 38-test verification recorded above.
- Review-fix RED 1: With the two resource-cancellation regressions added before production changes, the exact focused
  command reported `ClassCastException` because the dirty resource cancellation returned `Superseded` instead of
  `Failed`; the pre-collector case also left the run waiting on the leaked call job, so the RED run was interrupted after
  the defect was established. Mapping locally owned cancellation to clean/dirty failure and moving the last resource
  read before collector creation made the focused command pass.
- Review-fix RED 2: After removing the legacy `create(...)` override from `OrchestratorFakeFactory`, the exact focused
  command failed in `:app:compileDebugUnitTestKotlin`: the fake did not implement the abstract legacy method. Giving that
  method an interface default preserved the temporary bridge without adding legacy behavior back to the Task 4 fake;
  the exact focused command then passed.
- Original Task 4 RED: After adding the happy-path and matching-start tests, the startup command failed in
  `:app:compileDebugUnitTestKotlin` because `VoiceAgentCallOrchestrator` did not exist. The compiler reported
  `Unresolved reference 'VoiceAgentCallOrchestrator'`; the dependent state-flow and inference diagnostics were cascades
  from that missing API.
- Original self-review RED/GREEN 1: A last-waiter cancellation test showed that the worker could run the first failed
  cleanup and the external cleanup effect could retry it immediately, losing `CleanupFailed`. The startup cleanup handle
  now marks cleanup transfer before canceling the worker; one external attempt publishes the first failure and suppresses
  it onto the caller's canonical cancellation.
- Original self-review RED/GREEN 2: Post-route and post-factory cancellation tests showed that synchronous transfer
  could enter the next external phase before the canceled caller dispatched `StartCancelled`. Cleanup ownership is now
  installed before an explicit cancellation yield at each transfer boundary, so the exact returned lease/session is
  cleaned and the factory/session start is not entered after cancellation.
