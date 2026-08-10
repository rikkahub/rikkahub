# Managed ADB Shell Script Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve multiline `run-as sh -c` scripts and positional arguments across RikkaHub's managed physical-phone ADB boundary.

**Architecture:** Add one private `run_as_script` seam that POSIX-quotes a script and its arguments into one remote command while preserving the caller's `shell` or `exec-out` transport. Route all eight existing app-private multiline scripts through that seam, and make the fake managed transport parse the same single command shape that the real Android shell receives.

**Tech Stack:** Bash, Python 3 test double, `mdev android adb`, Android `run-as`, existing real-room voice helper tests.

## Global Constraints

- Work only in `/home/muly/code/rikkahub` on branch `master`.
- Modify only `scripts/voice-agent-real-room-lib.sh` and `scripts/test-voice-agent-real-room-step.sh` for production and test behavior.
- Accept only the exact transport modes `shell` and `exec-out`.
- Preserve every existing script body, positional argument, input stream, output contract, ownership check, cleanup rule, and fixed error classification.
- Keep binary capture on `exec-out`; do not route it through `shell`.
- Do not change `mdev`, Android application code, broadcasts, the installed APK, or the deployed LiveKit worker.
- Do not use direct ADB.
- Do not build, install, deploy, push, or start a fixture-backed physical call.

---

## File Structure

- `scripts/voice-agent-real-room-lib.sh`: owns the managed ADB adapter, POSIX argument quoting, and every app-private multiline script call site.
- `scripts/test-voice-agent-real-room-step.sh`: owns the fake managed transport, start/capture behavior regressions, stdin verification, and transport-mode assertions.

### Task 1: Preserve managed run-as script boundaries

**Files:**
- Modify: `scripts/test-voice-agent-real-room-step.sh:130-1360,1363-1450,2545-2640,4260-4310`
- Modify: `scripts/voice-agent-real-room-lib.sh:22-29,799-825,863-1021,1238-1325,1484-1555,1585-1670`

**Interfaces:**
- Consumes: `adb_read TRANSPORT ARGS...`, globals `PACKAGE` and `ANDROID_USER_ID`, and the existing multiline script plus positional arguments at each call site.
- Produces: `run_as_script TRANSPORT SCRIPT [ARG...]`, which writes the remote command's stdout unchanged and returns its exit status unchanged.

- [ ] **Step 1: Make the fake transport model the real boundary**

Add `import shlex` to the generated fake `mdev` Python program. Immediately after `command = argv[7:]` and the existing `get-state` branch, reject split `run-as sh -c` only when the regression flag is active, then decode a one-argument command for the existing fake handlers:

```python
if (
    os.environ.get("FAKE_MDEV_REQUIRE_SINGLE_RUN_AS_SCRIPT") == "1"
    and len(command) > 2
    and command[0] in {"shell", "exec-out"}
    and command[1] == "run-as"
    and "sh" in command[2:]
    and "-c" in command[2:]
):
    raise SystemExit(65)

if len(command) == 2 and command[0] in {"shell", "exec-out"}:
    try:
        decoded = shlex.split(command[1], posix=True)
    except ValueError:
        raise SystemExit(64)
    if decoded[:1] == ["run-as"]:
        command = [command[0], *decoded]
```

Add `FAKE_MDEV_REQUIRE_SINGLE_RUN_AS_SCRIPT` to the variables cleared by `reset_fake`:

```bash
unset FAKE_MDEV_REQUIRE_SINGLE_RUN_AS_SCRIPT
```

In the first successful scenarios in both `run_start_tests` and `run_capture_tests`, set the flag after `reset_fake` and before `run_helper`:

```bash
export FAKE_MDEV_REQUIRE_SINGLE_RUN_AS_SCRIPT=1
```

After the existing start assertions, parse the original NUL-delimited `MDEV_LOG` records and assert that these markers each occur in exactly one `shell` command whose post-`--` shape has exactly two arguments: the transport and one command string:

```python
import shlex
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
expected_shell_markers = {
    "voice-step-protected-root",
    "voice-step-trace-probe",
    "voice-step-create-owned-directory",
    "voice-step-stage-owned-fixture",
}
for marker in expected_shell_markers:
    matches = [
        command for command in commands
        if any(marker.encode() in value for value in command)
    ]
    assert len(matches) == 1
    tail = matches[0][7:]
    assert len(tail) == 2 and tail[0] == b"shell"
    decoded = shlex.split(tail[1].decode(), posix=True)
    assert decoded[0:5] == [
        "run-as", "me.rerere.rikkahub.debug", "--user", "0", "sh",
    ]
    assert decoded[5] == "-c" and marker in decoded[6] and decoded[7] == "sh"
```

After the existing successful capture assertions, locate `voice-step-capture-bundle` and assert the same two-argument shape uses `exec-out`, then decode it and verify the three source paths remain positional arguments. The already published byte-for-byte artifacts remain the independent stdin/stdout behavior assertion:

```python
import shlex
import sys

data = open(sys.argv[1], "rb").read()
commands = [chunk.split(b"\0") for chunk in data.split(b"\0\0") if chunk]
bundles = [
    command for command in commands
    if any(b"voice-step-capture-bundle" in value for value in command)
]
assert len(bundles) == 1
tail = bundles[0][7:]
assert len(tail) == 2 and tail[0] == b"exec-out"
decoded = shlex.split(tail[1].decode(), posix=True)
assert decoded[0:6] == [
    "run-as", "me.rerere.rikkahub.debug", "--user", "0", "sh", "-c",
]
assert "voice-step-capture-bundle" in decoded[6]
assert decoded[7] == "sh"
assert [path.rsplit("/", 1)[-1] for path in decoded[8:]] == [
    "automation-events.jsonl",
    "voice-experience-private.ndjson",
    "voice-experience-events.ndjson",
]
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
scripts/test-voice-agent-real-room-step.sh start capture
```

Expected: FAIL in the first start scenario because the current helper passes the protected-root multiline script as split ADB arguments and the fake exits 65. This is the intended regression failure; it must not fail from Python syntax, fixture setup, or an unrelated assertion.

- [ ] **Step 3: Add the central quoting and transport seam**

Add these private functions immediately after `adb_read` in `scripts/voice-agent-real-room-lib.sh`:

```bash
quote_remote_shell_argument() {
  local value="$1"
  printf "'%s'" "${value//\'/\'\\\'\'}"
}

run_as_script() {
  local transport="$1"
  local script="$2"
  shift 2
  local command
  local argument
  local quoted
  case "$transport" in
    shell|exec-out) ;;
    *) die 'invalid ADB script transport' ;;
  esac
  command="run-as $(quote_remote_shell_argument "$PACKAGE")"
  command+=" --user $(quote_remote_shell_argument "$ANDROID_USER_ID")"
  command+=" sh -c $(quote_remote_shell_argument "$script")"
  command+=" $(quote_remote_shell_argument sh)"
  for argument in "$@"; do
    quoted="$(quote_remote_shell_argument "$argument")"
    command+=" $quoted"
  done
  adb_read "$transport" "$command"
}
```

This seam receives only Bash strings, so NUL cannot enter the function; every representable byte is enclosed in POSIX single quotes, and embedded single quotes use the literal `'\''` sequence.

- [ ] **Step 4: Route all multiline app-private scripts through the seam**

Change exactly these eight callers, keeping the text between the opening and closing script quotes byte-for-byte unchanged:

```text
verify_package_contract                -> run_as_script shell SCRIPT
read_trace_pointer                     -> run_as_script shell SCRIPT "$LATEST_TRACE_PATH"
create_owned_remote_directory          -> run_as_script shell SCRIPT "$remote_directory" "$owner_hash"
stage_owned_snapshot                   -> run_as_script shell SCRIPT "$remote_directory" "$remote_path" "$owner_hash"
remove_owned_remote_directory_quiescent -> run_as_script shell SCRIPT "$remote_directory" "$FIXTURE_PARENT_IDENTITY" "$FIXTURE_DIRECTORY_IDENTITY" "$FIXTURE_OWNERSHIP_NONCE" "$PACKAGE_UID"
read_capture_bundle_snapshots          -> run_as_script exec-out SCRIPT "$automation_source" "$private_source" "$sanitized_source"
read_source_metadata                   -> run_as_script shell SCRIPT "$source_path"
read_checkpoint_artifact_snapshots     -> run_as_script shell SCRIPT "$automation_path" "$private_path" "$sanitized_path"
```

For example, `read_trace_pointer` becomes:

```bash
probe="$(run_as_script shell '
: voice-step-trace-probe
if [ -L "$1" ]; then
  printf invalid
elif [ -e "$1" ]; then
  [ -f "$1" ] || { printf invalid; exit; }
  printf present
else
  printf absent
fi
' "$LATEST_TRACE_PATH" 2>/dev/null)" || die 'trace readback failed'
```

The fixture-staging caller must retain its existing stdin redirection:

```bash
' "$remote_directory" "$remote_path" "$owner_hash" \
    < "$fixture_snapshot")" || die 'fixture staging failed'
```

The capture-bundle caller must retain `exec-out` and its existing stdout redirection into the private local bundle.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run:

```bash
scripts/test-voice-agent-real-room-step.sh start capture
```

Expected: exit 0 with one final `PASS: voice-agent-real-room-step (...)` line. The start regression proves all four pre-call scripts cross the fake managed boundary as one `shell` argument, fixture staging still consumes stdin, and capture remains on `exec-out` with byte-for-byte artifact publication.

- [ ] **Step 6: Check the diff and commit the root fix**

Run:

```bash
git diff --check
git diff -- scripts/voice-agent-real-room-lib.sh scripts/test-voice-agent-real-room-step.sh
git status --short
```

Expected: no whitespace errors and only the two planned files modified.

Commit:

```bash
git add scripts/voice-agent-real-room-lib.sh scripts/test-voice-agent-real-room-step.sh
git commit -m "fix: preserve managed adb shell scripts"
```

### Task 2: Verify the complete helper and physical read-only boundary

**Files:**
- Read-only: `scripts/voice-agent-real-room-lib.sh`
- Read-only: `scripts/test-voice-agent-real-room-step.sh`

**Interfaces:**
- Consumes: committed `run_as_script shell|exec-out SCRIPT [ARG...]` and the assigned managed logical `phone` lane.
- Produces: full helper-suite evidence plus one sanitized trace-pointer token; no device or repository mutation.

- [ ] **Step 1: Run the complete real-room helper suite**

Run:

```bash
scripts/test-voice-agent-real-room-step.sh
```

Expected: exit 0 with one final `PASS: voice-agent-real-room-step (...)` line and no `FAIL:` line.

- [ ] **Step 2: Reverify the managed physical lane without changing it**

Run from the existing Herdr/tmux pane:

```bash
test -n "${HERDR_PANE_ID:-}"
timeout 20s mdev android status --json | python3 -c '
import json, sys
value = json.load(sys.stdin)
checks = {item["name"]: item["status"] for item in value["checks"]}
assert checks["adb-tailnet"] == "pass"
assert checks["platform-tools"] == "pass"
assert checks["assignment"] == "pass"
assert checks["phone"] in {"pass", "warn"}
print("managed_status=acceptable")
'
timeout 15s mdev android adb --device phone --owner "$HERDR_PANE_ID" -- shell true >/dev/null
printf 'managed_round_trip=pass\n'
```

Expected:

```text
managed_status=acceptable
managed_round_trip=pass
```

- [ ] **Step 3: Exercise the production transport seam with the exact read-only trace probe**

Run:

```bash
TRACE_RESULT="$({
  MDEV=mdev
  MDEV_OWNER="$HERDR_PANE_ID"
  PACKAGE=me.rerere.rikkahub.debug
  ANDROID_USER_ID=0
  source scripts/voice-agent-real-room-lib.sh
  run_as_script shell '
: voice-step-trace-probe
if [ -L "$1" ]; then
  printf invalid
elif [ -e "$1" ]; then
  [ -f "$1" ] || { printf invalid; exit; }
  printf present
else
  printf absent
fi
' no_backup/voice-e2e/latest-trace-id.txt
} 2>/dev/null)"
case "$TRACE_RESULT" in
  absent|present) printf 'trace_pointer=%s\n' "$TRACE_RESULT" ;;
  *) printf 'trace_pointer=invalid\n' >&2; exit 1 ;;
esac
unset TRACE_RESULT
```

Expected: exit 0 and exactly `trace_pointer=absent` or `trace_pointer=present`. Do not read or print the trace identifier when the pointer is present.

- [ ] **Step 4: Verify repository scope and finish**

Run:

```bash
test "$(git branch --show-current)" = master
git diff --quiet
git diff --cached --quiet
test -z "$(git status --short)"
git log -2 --oneline
```

Expected: clean RikkaHub `master`; the latest commits are the implementation commit and the plan commit. Do not build, install, deploy, push, or start a voice call.
