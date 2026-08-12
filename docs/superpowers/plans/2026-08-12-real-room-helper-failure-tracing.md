# Real-Room Helper Failure Tracing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add opt-in, privacy-safe failure-stage diagnostics to real-room helper `start` invocations so a failed physical verification retains actionable evidence after rollback.

**Architecture:** Add one private sourced diagnostics library that owns fixed-token state, secure destination validation, subprocess-safe state files, and atomic publication. The existing helper library reports sanitized errors and managed-ADB statuses into that seam; the public step script advances stages and publishes from its existing `EXIT` trap.

**Tech Stack:** Bash 5, Python 3 standard library, existing fake-`mdev` integration harness, POSIX permissions and hard-link publication.

## Global Constraints

- Change only `/home/muly/code/rikkahub`; never edit or recreate Agora2 `external/rikkahub`.
- Trace only `start`; other operations and option contracts remain unchanged.
- Accept only optional `start --diagnostic-record ABSOLUTE_PATH`.
- Require an absent destination, a real owner-owned `0700` parent, and a path distinct from `--state`.
- Publish exactly seven allowlisted lines atomically as a single-link mode-`0600` regular file.
- Never record or print commands, arguments, paths, identifiers, hashes, fixture bytes, transcripts, prompts, answers, credentials, URLs, stdout, stderr, exceptions, or timestamps.
- Preserve successful stdout byte-for-byte; add only `voice-step.diagnostic=stage:<token>,category:<token>` on failure.
- Preserve an original failure if diagnostic publication fails; publication failure is fatal only when the operation otherwise succeeded.
- Do not run a physical fixture, service start, build, install, deploy, or network call.

## File Structure

- Create `scripts/voice-agent-real-room-diagnostics.sh`: diagnostics state API, fixed error mapping, secure validator, atomic publisher.
- Modify `scripts/voice-agent-real-room-lib.sh`: connect `die` and `run_mdev_adb`; mark the start-only fixture staging substage.
- Modify `scripts/voice-agent-real-room-step.sh`: source diagnostics, declare state, parse the option, advance stages, integrate exit publication.
- Modify `scripts/test-voice-agent-real-room-step.sh`: focused tracing selection, real-helper assertions, failure injection, cleanup/publication races, privacy checks.
- Modify `docs/superpowers/specs/2026-08-12-real-room-helper-failure-tracing-design.md`: retain the source-order correction made during planning.

---

### Task 1: Secure Diagnostic Record Contract

**Files:**
- Create: `scripts/voice-agent-real-room-diagnostics.sh`
- Modify: `scripts/voice-agent-real-room-lib.sh:9-29`
- Modify: `scripts/voice-agent-real-room-step.sh:6-63,134-159,947-976`
- Modify: `scripts/test-voice-agent-real-room-step.sh:34-36,1381-1471,1757-1852,4954-4985`
- Test: `scripts/test-voice-agent-real-room-step.sh`

**Interfaces:**
- Consumes: existing local-temp lifecycle, destination validators, `die`, and `on_exit`.
- Produces: `diagnostic_initialize`, `diagnostic_set_stage`, `diagnostic_note_error`, `diagnostic_note_managed_exit`, `diagnostic_snapshot_private_state`, and `diagnostic_publish`.

- [ ] **Step 1: Write the failing success and destination tests**

Add a `tracing` test selector and `run_tracing_tests`. Add this real-record assertion:

```bash
assert_diagnostic_record() {
  local path="$1" stage="$2" outcome="$3" category="$4" child="$5" cleanup="$6"
  python3 - "$path" "$stage" "$outcome" "$category" "$child" "$cleanup" <<'PY'
import os, stat, sys
path, stage, outcome, category, child, cleanup = sys.argv[1:]
raw = open(path, "rb").read()
assert raw == (
    "version=1\noperation=start\n"
    f"stage={stage}\noutcome={outcome}\nerror_category={category}\n"
    f"child_exit_status={child}\ncleanup={cleanup}\n"
).encode()
metadata = os.lstat(path)
assert stat.S_ISREG(metadata.st_mode)
assert stat.S_IMODE(metadata.st_mode) == 0o600
assert metadata.st_nlink == 1
for secret in (b"DEVICE_SECRET_123", b"OWNER_SECRET_123", b"CONVERSATION_SECRET_123",
               b"fixture-1", b"trace-new", b"sha256:", b"ADB_STDOUT_SECRET",
               b"ADB_STDERR_SECRET"):
    assert secret not in raw
PY
}
```

Run a normal fake start with `--diagnostic-record "$TMP_DIR/start-diagnostic.txt"`. Assert existing success stdout with `assert_exact_output`, then assert `complete/success/none/none/complete`, mode `0600`, and no `.voice-step-diagnostic.*` residue.

Add three validation cases using the same complete start argv:

```bash
# Existing destination: capture inode and bytes before, then prove both unchanged.
# Insecure parent: mkdir then chmod 755; prove failure before MDEV_LOG receives bytes.
# Alias: pass the same absent path to --state and --diagnostic-record; prove no file or ADB access.
```

Each case must call `assert_private_output_absent`.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `scripts/test-voice-agent-real-room-step.sh tracing`

Expected: FAIL because `start` rejects `--diagnostic-record` and creates no record.

- [ ] **Step 3: Implement the private diagnostics library and option validation**

Create a sourced-only diagnostics file. Start with this validator:

```bash
diagnostic_token_is_valid() { [[ "$1" =~ ^[a-z][a-z0-9-]{0,63}$ ]]; }

validate_private_diagnostic_destination() {
  python3 - "$1" 2>/dev/null <<'PY'
import os, stat, sys
path = sys.argv[1]
if not path or not os.path.isabs(path) or os.path.normpath(path) != path:
    raise SystemExit(1)
parent, name = os.path.dirname(path), os.path.basename(path)
if not name or name in {".", ".."} or os.path.realpath(parent) != parent:
    raise SystemExit(1)
metadata = os.lstat(parent)
if (stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode)
        or stat.S_IMODE(metadata.st_mode) != 0o700
        or metadata.st_uid != os.geteuid()):
    raise SystemExit(1)
try:
    os.lstat(path)
except FileNotFoundError:
    pass
else:
    raise SystemExit(1)
PY
}
```

`diagnostic_initialize start DESTINATION` sets enabled globals, creates and registers two mode-`0600` state files under `LOCAL_TEMP_DIR`, and sets `option-validation`. `diagnostic_set_stage` validates the literal, updates the global, and truncates the managed-status file.

Implement `diagnostic_publish OUTCOME CLEANUP` using one silent Python process. It must build only:

```python
payload = (
    "version=1\n"
    f"operation={operation}\n"
    f"stage={stage}\n"
    f"outcome={outcome}\n"
    f"error_category={category}\n"
    f"child_exit_status={child}\n"
    f"cleanup={cleanup}\n"
).encode("ascii")
```

Use `tempfile.mkstemp(prefix=".voice-step-diagnostic.", dir=parent)`, `os.fchmod(fd, 0o600)`, write/fsync, `os.link(temporary, destination, follow_symlinks=False)`, verify same inode/content/mode and link count 2, unlink the temporary, then verify destination link count 1. Catch failures, remove only the owned temporary, print nothing, and return nonzero.

Source diagnostics before the existing real-room library. Add diagnostics globals beside existing lifecycle globals. Extend only `start`:

```bash
parse_options '--state --diagnostic-record --mdev-owner --package --conversation-id --run-hash --comparison-hash --fixture' "$@"
```

When the option is present, require nonempty value, run the private validator, require it distinct from `--state`, then call `diagnostic_initialize start PATH`. Set `complete` immediately before successful local-temp cleanup. Publish the success record from `on_exit` after cleanup is known, without changing stdout.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
bash -n scripts/voice-agent-real-room-diagnostics.sh scripts/voice-agent-real-room-lib.sh \
  scripts/voice-agent-real-room-step.sh scripts/test-voice-agent-real-room-step.sh
scripts/test-voice-agent-real-room-step.sh tracing
```

Expected: syntax checks and focused tests pass.

- [ ] **Step 5: Commit**

```bash
git add scripts/voice-agent-real-room-diagnostics.sh scripts/voice-agent-real-room-lib.sh \
  scripts/voice-agent-real-room-step.sh scripts/test-voice-agent-real-room-step.sh
git commit -m "feat: add private real-room start diagnostics"
```

---

### Task 2: Failure Stages, Categories, and Managed Status

**Files:**
- Modify: `scripts/voice-agent-real-room-diagnostics.sh`
- Modify: `scripts/voice-agent-real-room-lib.sh:9-29,1053-1063`
- Modify: `scripts/voice-agent-real-room-step.sh:831-905,961-976`
- Modify: `scripts/test-voice-agent-real-room-step.sh:156-1365,1381-1471,2564-2887`
- Test: `scripts/test-voice-agent-real-room-step.sh`

**Interfaces:**
- Consumes: Task 1 diagnostics state and publisher.
- Produces: subprocess-safe error/status capture; documented start stages; fake controls `FAKE_ADB_EXIT_MATCH`, `FAKE_ADB_EXIT_STATUS`, `FAKE_ADB_START_DOES_NOT_ACTIVATE`, `FAKE_ADB_START_RETAINS_TRACE`.

- [ ] **Step 1: Write the failing stage matrix**

After fake command normalization, add generic managed failure injection:

```python
exit_match = os.environ.get("FAKE_ADB_EXIT_MATCH")
if exit_match and any(exit_match in value for value in argv):
    raise SystemExit(int(os.environ.get("FAKE_ADB_EXIT_STATUS", "73")))
```

Reset both variables. Add activation controls to the fake `.START` branch: one accepts START without setting `call_active`; the other sets call active but leaves `trace_id` unchanged.

Add `assert_traced_start_failure FIXTURE STATE RECORD STAGE CATEGORY CHILD [extra start argv]`. It must run the real helper, prove nonzero status and no state publication, call `assert_diagnostic_record ... failure ... complete`, assert the final stderr line equals the fixed diagnostic summary, and call `assert_private_output_absent`.

Exercise this literal matrix:

| Injection | Stage | Category | Child |
|---|---|---|---|
| invalid run hash | `option-validation` | `invalid-run-hash` | `none` |
| timeout configuration `0` | `runtime-validation` | `invalid-timeout-configuration` | `none` |
| hold concurrent start lock | `host-lock` | `host-operation-already-active` | `none` |
| fixture mode `0644` | `fixture-snapshot` | `invalid-fixture` | `none` |
| exit match `get-state` | `device-readiness` | `device-not-ready` | `73` |
| exit match `get-current-user` | `package-identity` | `android-user-readback-failed` | `73` |
| exit match `dumpsys package` | `package-contract` | `package-readback-failed` | `73` |
| exit match `.STATUS` | `status-read` | `unexpected-status-response` | `73` |
| exit match `voice-step-trace-probe` | `trace-read` | `trace-readback-failed` | `73` |
| exit match `voice-step-create-owned-directory` | `fixture-directory` | `fixture-staging-failed` | `73` |
| exit match `voice-step-stage-owned-fixture` | `fixture-stage` | `fixture-staging-failed` | `73` |
| exit match `.PREPARE` | `automation-prepare` | `adb-command-failed` | `73` |
| exit match `ARM_CAPTURE_FIXTURE` | `fixture-arm` | `adb-command-failed` | `73` |
| exit match `start-foreground-service` | `service-start` | `call-start-failed` | `73` |
| start does not activate | `call-activation` | `call-activation-timed-out` | `1` |
| start retains trace | `trace-activation` | `trace-activation-timed-out` | `none` |
| state hard-link race | `state-publication` | `state-publication-failed` | `none` |

Reuse the existing concurrent-start test setup for the host-lock case rather than adding a production test hook.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `scripts/test-voice-agent-real-room-step.sh tracing`

Expected: FAIL because records remain at `option-validation`, categories collapse to `operation-failed`, or child statuses remain `none`.

- [ ] **Step 3: Implement fixed category and subprocess-safe status capture**

Map exact `die` messages to fixed tokens in diagnostics. Required mappings are:

```bash
case "$1" in
  'invalid run hash') printf invalid-run-hash ;;
  'invalid timeout configuration') printf invalid-timeout-configuration ;;
  'host operation lock unavailable') printf host-operation-lock-unavailable ;;
  'host operation already active') printf host-operation-already-active ;;
  'invalid fixture') printf invalid-fixture ;;
  'device is not ready') printf device-not-ready ;;
  'Android user readback failed') printf android-user-readback-failed ;;
  'package readback failed') printf package-readback-failed ;;
  'package contract mismatch') printf package-contract-mismatch ;;
  'unexpected status response') printf unexpected-status-response ;;
  'automation is not ready') printf automation-not-ready ;;
  'trace readback failed') printf trace-readback-failed ;;
  'fixture ownership failed') printf fixture-ownership-failed ;;
  'fixture staging failed') printf fixture-staging-failed ;;
  'fixture staging verification failed') printf fixture-staging-verification-failed ;;
  'receiver rejected request') printf receiver-rejected-request ;;
  'ADB command failed') printf adb-command-failed ;;
  'unexpected receiver response') printf unexpected-receiver-response ;;
  'call start failed') printf call-start-failed ;;
  'ambiguous call readback') printf ambiguous-call-readback ;;
  'call activation timed out') printf call-activation-timed-out ;;
  'trace activation timed out') printf trace-activation-timed-out ;;
  'state publication failed') printf state-publication-failed ;;
  'cleanup failed') printf cleanup-failed ;;
  'interrupted') printf interrupted ;;
  'diagnostic publication failed') printf diagnostic-publication-failed ;;
  *) printf operation-failed ;;
esac
```

`diagnostic_note_error` sets the parent global and writes the token to the private error file so a `die` inside command substitution survives. `diagnostic_snapshot_private_state` accepts only the token regex and a decimal status 0-255 before copying file values into parent globals.

Call `diagnostic_note_error "$message"` inside `die` without changing its current error line. Rewrite `run_mdev_adb` to capture the exact status around `timeout`, call `diagnostic_note_managed_exit` only on nonzero, and return that status while preserving stdout.

- [ ] **Step 4: Add stage transitions without reordering operations**

Instrument this exact source order:

```text
option-validation (start option branch)
runtime-validation
host-lock
fixture-snapshot
device-readiness
package-identity
package-contract
status-read
trace-read
fixture-directory
fixture-stage (inside stage_snapshot after directory creation)
automation-prepare
fixture-arm
service-start
call-activation
trace-activation
state-publication
complete
```

Place each transition immediately before its existing operation. Do not move validations, locking, snapshotting, device calls, fixture mutations, broadcasts, service calls, waits, state publication, or cleanup.

- [ ] **Step 5: Verify GREEN and commit**

Run:

```bash
bash -n scripts/voice-agent-real-room-diagnostics.sh scripts/voice-agent-real-room-lib.sh \
  scripts/voice-agent-real-room-step.sh scripts/test-voice-agent-real-room-step.sh
scripts/test-voice-agent-real-room-step.sh tracing
scripts/test-voice-agent-real-room-step.sh start
```

Expected: all commands pass.

```bash
git add scripts/voice-agent-real-room-diagnostics.sh scripts/voice-agent-real-room-lib.sh \
  scripts/voice-agent-real-room-step.sh scripts/test-voice-agent-real-room-step.sh
git commit -m "test: cover real-room start failure stages"
```

---

### Task 3: Cleanup and Publication Failure Semantics

**Files:**
- Modify: `scripts/voice-agent-real-room-diagnostics.sh`
- Modify: `scripts/voice-agent-real-room-step.sh:134-159`
- Modify: `scripts/test-voice-agent-real-room-step.sh:156-1365,1381-1471,2564-2887`
- Modify: `docs/superpowers/specs/2026-08-12-real-room-helper-failure-tracing-design.md`
- Test: `scripts/test-voice-agent-real-room-step.sh`

**Interfaces:**
- Consumes: Task 2 state plus existing rollback/local cleanup.
- Produces: original-failure precedence, independent cleanup status, deterministic publication-failure behavior, no diagnostic temp residue.

- [ ] **Step 1: Write failing cleanup and publication-race tests**

Add fake-only permission mutation:

```python
chmod_match = os.environ.get("FAKE_ADB_CHMOD_MATCH")
chmod_path = os.environ.get("FAKE_ADB_CHMOD_PATH")
if chmod_match and chmod_path and any(chmod_match in value for value in argv):
    os.chmod(chmod_path, 0o500)
```

Reset both variables. Add three real-helper cases:

1. Fail `fixture-arm` with managed status 73 and set `FAKE_ADB_FAIL_CLEANUP_BROKER=1`; assert the record retains `fixture-arm/adb-command-failed/73` and records `cleanup=failed`.
2. Use a dedicated diagnostic parent, chmod it to `0500` when the failing ARM command runs, and prove the original result and summary survive when record publication fails. Restore `0700` afterward.
3. Chmod a dedicated diagnostic parent to `0500` during an otherwise successful service start; prove final status is nonzero and stderr ends with `voice-step.error=diagnostic publication failed` plus `voice-step.diagnostic=stage:complete,category:diagnostic-publication-failed`. Existing success stdout may already be present, but the invocation is failed.

For each case, assert private-output absence and no `.voice-step-diagnostic.*` residue.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `scripts/test-voice-agent-real-room-step.sh tracing`

Expected: FAIL because rollback overwrites child evidence, cleanup is not independent, or publication failure changes the wrong result.

- [ ] **Step 3: Finalize `on_exit` ordering**

Implement this order exactly:

```text
capture original status
snapshot diagnostic error and child state
disable managed-status recording
run rollback and package restoration
run local-temp cleanup
derive cleanup=complete|failed
apply deferred-signal and existing cleanup status rules
use operation-failed only when no mapped category exists
publish diagnostic
if publication failed and status was zero, set status 1 and diagnostic-publication-failed
emit exactly one sanitized diagnostic summary when final status is nonzero
exit final status
```

Never call `die` from `on_exit` or `diagnostic_publish`. Ignore publication failure after an original nonzero result; make it fatal after an original zero result. Set `DIAGNOSTIC_CAPTURE_MANAGED_EXIT=0` before rollback so cleanup commands cannot replace operation evidence.

- [ ] **Step 4: Run full verification and privacy review**

Run:

```bash
bash -n scripts/voice-agent-real-room-diagnostics.sh scripts/voice-agent-real-room-lib.sh \
  scripts/voice-agent-real-room-step.sh scripts/test-voice-agent-real-room-step.sh
scripts/test-voice-agent-real-room-step.sh tracing
scripts/test-voice-agent-real-room-step.sh
git diff --check
git diff -- scripts/voice-agent-real-room-diagnostics.sh scripts/voice-agent-real-room-lib.sh \
  scripts/voice-agent-real-room-step.sh scripts/test-voice-agent-real-room-step.sh \
  docs/superpowers/specs/2026-08-12-real-room-helper-failure-tracing-design.md
```

Expected: syntax and all helper assertions pass; no whitespace errors. Review the complete diff to prove exact keys/order, fixed or numeric values only, start-only option scope, unchanged operation ordering, and no Android/Gradle/Agora2/`mdev` changes.

- [ ] **Step 5: Commit**

```bash
git add scripts/voice-agent-real-room-diagnostics.sh scripts/voice-agent-real-room-lib.sh \
  scripts/voice-agent-real-room-step.sh scripts/test-voice-agent-real-room-step.sh \
  docs/superpowers/specs/2026-08-12-real-room-helper-failure-tracing-design.md
git commit -m "fix: preserve real-room start failure evidence"
```

## Final Verification Gate

After the last commit, run fresh evidence:

```bash
bash -n scripts/voice-agent-real-room-diagnostics.sh scripts/voice-agent-real-room-lib.sh \
  scripts/voice-agent-real-room-step.sh scripts/test-voice-agent-real-room-step.sh
scripts/test-voice-agent-real-room-step.sh
git diff --check HEAD^ HEAD
git status --short
```

Expected: syntax checks exit 0; the full suite prints one final `PASS: voice-agent-real-room-step (...)`; committed diff has no whitespace errors; tracked/index state is clean. Do not run a physical-phone start.
