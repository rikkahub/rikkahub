# Failure-Only Real-Room Diagnostic Publication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace success-record publication and post-publication deletion with one failure-only atomic commit from an unnamed inode.

**Architecture:** A private Python publisher reopens the previously validated parent, verifies its identity and privacy, constructs the exact failure record in an `O_TMPFILE` inode, and atomically gives that inode its only name with `os.link` through `/proc/self/fd`. The Bash diagnostics module retains only scalar parent identity and fixed diagnostic state; the exit trap finalizes status, cleanup, and signals before invoking the publisher only for a nonzero result.

**Tech Stack:** Bash 5.2, Python 3.11 standard library, Linux `O_TMPFILE`, `/proc/self/fd`, existing fake-`mdev` host integration harness.

## Global Constraints

- Change only `/home/muly/code/rikkahub`; never edit or recreate Agora2 `external/rikkahub`.
- Trace only `start`; other operations and option contracts remain unchanged.
- Accept only optional `start --diagnostic-record ABSOLUTE_PATH`.
- Require an absent destination, a canonical real owner-owned mode-`0700` parent, and a path distinct from `--state`.
- A successful `start` must leave the diagnostic destination absent and preserve stdout byte-for-byte.
- A failed `start` may publish exactly seven allowlisted lines as a single-link mode-`0600` regular file.
- The fixed record is `version=1`, `operation=<token>`, `stage=<token>`, `outcome=failure`, `error_category=<token>`, `child_exit_status=<0-255>|none`, and `cleanup=complete|failed`, in that order with a trailing newline.
- Never record or print commands, arguments, paths, identifiers, hashes, fixture bytes, transcripts, prompts, answers, credentials, URLs, stdout, stderr, exceptions, or timestamps.
- Preserve the original operation failure, category, and child status over cleanup, signal, and publication failures.
- Never overwrite or delete the diagnostic destination; never create a named diagnostic temporary or retain a diagnostic descriptor across helper child processes.
- Do not add a named-temporary fallback when `O_TMPFILE` or `/proc/self/fd` linking is unavailable.
- Do not run a physical fixture, service start, build, install, deploy, remote ADB, agent-device, or network call.
- Preserve the unrelated pre-existing untracked `scripts/__pycache__/`; do not remove or commit it.

## File Structure

- Create `scripts/voice-agent-real-room-diagnostic-publisher.py`: private one-shot parent verification, anonymous payload construction, descriptor verification, and atomic link commit.
- Create `scripts/test-voice-agent-real-room-diagnostic-publisher.sh`: focused host tests for the private publisher interface and filesystem race behavior.
- Modify `scripts/voice-agent-real-room-diagnostics.sh`: retain scalar parent identity only and expose `diagnostic_publish_failure CLEANUP`.
- Modify `scripts/voice-agent-real-room-step.sh`: remove success publication, identity transfer, destination retraction, and publication-failure category changes; finalize signals before failure-only publication.
- Modify `scripts/test-voice-agent-real-room-step.sh`: enforce success absence, failure-only records, publisher faults, namespace races, closed diagnostic descriptors, signal precedence, source invariants, and privacy.

---

### Task 1: One-Shot Anonymous Failure Publisher

**Files:**
- Create: `scripts/voice-agent-real-room-diagnostic-publisher.py`
- Create: `scripts/test-voice-agent-real-room-diagnostic-publisher.sh`

**Interfaces:**
- Consumes: positional arguments `DESTINATION EXPECTED_PARENT_IDENTITY OPERATION STAGE ERROR_CATEGORY CHILD_EXIT_STATUS CLEANUP`.
- Produces: exit `0` only after the final `os.link` commit; exit `1` with no stdout/stderr and no named residue on every pre-commit failure.
- Invariant: `os.link('/proc/self/fd/<unnamed-fd>', BASENAME, dst_dir_fd=PARENT_FD, follow_symlinks=True)` is the last fallible operation before `os._exit(0)`.

- [ ] **Step 1: Write the focused publisher test harness**

Create `scripts/test-voice-agent-real-room-diagnostic-publisher.sh` with a mode-`0700` `mktemp -d` root, exact cleanup trap, a `run_publisher` helper that captures status/stdout/stderr, and this record assertion:

```bash
PUBLISHER="$ROOT_DIR/scripts/voice-agent-real-room-diagnostic-publisher.py"

parent_identity() {
  stat -c '%d:%i' -- "$1"
}

run_publisher() {
  local destination="$1" identity="$2"
  shift 2
  set +e
  python3 "$PUBLISHER" "$destination" "$identity" "$@" \
    >"$TMP_DIR/stdout" 2>"$TMP_DIR/stderr"
  RUN_STATUS=$?
  set -e
}

assert_failure_record() {
  local path="$1" stage="$2" category="$3" child="$4" cleanup="$5"
  python3 - "$path" "$stage" "$category" "$child" "$cleanup" <<'PY'
import os
import stat
import sys

path, stage, category, child, cleanup = sys.argv[1:]
expected = (
    "version=1\n"
    "operation=start\n"
    f"stage={stage}\n"
    "outcome=failure\n"
    f"error_category={category}\n"
    f"child_exit_status={child}\n"
    f"cleanup={cleanup}\n"
).encode("ascii")
actual = open(path, "rb").read()
metadata = os.lstat(path)
assert actual == expected
assert stat.S_ISREG(metadata.st_mode)
assert stat.S_IMODE(metadata.st_mode) == 0o600
assert metadata.st_nlink == 1
PY
}
```

Add five cases, each incrementing the local assertion counter exactly once:

1. A normal call with `start fixture-arm adb-command-failed 73 failed` exits `0`, emits no output, and passes `assert_failure_record`.
2. A pre-existing destination containing `raced` makes the call nonzero and preserves those exact bytes.
3. A `sitecustomize.py` wrapper around `os.open` raises `OSError` only when `flags & os.O_TMPFILE`; the call remains silent, nonzero, and leaves the destination absent.
4. A `sitecustomize.py` wrapper around `os.link` atomically creates the destination containing `raced` immediately before calling the real `os.link`; the publisher returns nonzero and neither overwrites nor deletes `raced`.
5. A `sitecustomize.py` wrapper renames the validated parent and replaces its pathname with a symlink to another mode-`0700` directory when the publisher first opens the parent with `O_DIRECTORY`; publication returns nonzero, neither directory contains the destination, and both sentinel files remain unchanged.

For every case, assert both captured output files are empty and this glob finds no named temporary:

```bash
if compgen -G "$parent/.voice-step-diagnostic.*" >/dev/null; then
  fail "publisher test: named diagnostic temporary was created"
fi
```

End with:

```bash
printf 'PASS: voice-agent-real-room-diagnostic-publisher (%s assertions)\n' "$TEST_COUNT"
```

Make both new scripts owner-executable before running the RED:

```bash
chmod 700 scripts/voice-agent-real-room-diagnostic-publisher.py \
  scripts/test-voice-agent-real-room-diagnostic-publisher.sh
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
scripts/test-voice-agent-real-room-diagnostic-publisher.sh
```

Expected: nonzero with the first case reporting that the private publisher is missing; no destination or `.voice-step-diagnostic.*` path remains.

- [ ] **Step 3: Implement the private Python publisher**

Create `scripts/voice-agent-real-room-diagnostic-publisher.py`. Keep the following interface and commit sequence; do not add logging, environment-controlled production behavior, fallback files, or post-link verification:

```python
#!/usr/bin/env python3
import os
import re
import stat
import sys

TOKEN = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
CHILD = re.compile(r"^(?:none|[0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$")
IDENTITY = re.compile(r"^([0-9]+):([0-9]+)$")


def write_all(descriptor: int, payload: bytes) -> None:
    offset = 0
    while offset < len(payload):
        written = os.write(descriptor, payload[offset:])
        if written <= 0:
            raise OSError
        offset += written


def read_all(descriptor: int) -> bytes:
    os.lseek(descriptor, 0, os.SEEK_SET)
    chunks = []
    while True:
        chunk = os.read(descriptor, 4096)
        if not chunk:
            return b"".join(chunks)
        chunks.append(chunk)


def publish(arguments: list[str]) -> None:
    if len(arguments) != 7:
        raise ValueError
    destination, expected_identity, operation, stage, category, child, cleanup = arguments
    match = IDENTITY.fullmatch(expected_identity)
    if match is None:
        raise ValueError
    expected_parent = (int(match.group(1)), int(match.group(2)))
    if (
        not os.path.isabs(destination)
        or os.path.normpath(destination) != destination
        or operation != "start"
        or TOKEN.fullmatch(operation) is None
        or TOKEN.fullmatch(stage) is None
        or TOKEN.fullmatch(category) is None
        or CHILD.fullmatch(child) is None
        or cleanup not in {"complete", "failed"}
    ):
        raise ValueError
    parent = os.path.dirname(destination)
    name = os.path.basename(destination)
    if not name or name in {".", ".."} or os.path.realpath(parent) != parent:
        raise ValueError
    parent_fd = os.open(
        parent,
        os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC,
    )
    parent_metadata = os.fstat(parent_fd)
    if (
        not stat.S_ISDIR(parent_metadata.st_mode)
        or stat.S_IMODE(parent_metadata.st_mode) != 0o700
        or parent_metadata.st_uid != os.geteuid()
        or (parent_metadata.st_dev, parent_metadata.st_ino) != expected_parent
    ):
        raise OSError
    payload = (
        "version=1\n"
        f"operation={operation}\n"
        f"stage={stage}\n"
        "outcome=failure\n"
        f"error_category={category}\n"
        f"child_exit_status={child}\n"
        f"cleanup={cleanup}\n"
    ).encode("ascii")
    unnamed_fd = os.open(
        ".",
        os.O_RDWR | os.O_TMPFILE | os.O_CLOEXEC,
        0o600,
        dir_fd=parent_fd,
    )
    os.fchmod(unnamed_fd, 0o600)
    write_all(unnamed_fd, payload)
    os.fsync(unnamed_fd)
    metadata = os.fstat(unnamed_fd)
    if (
        not stat.S_ISREG(metadata.st_mode)
        or stat.S_IMODE(metadata.st_mode) != 0o600
        or metadata.st_nlink != 0
        or read_all(unnamed_fd) != payload
    ):
        raise OSError
    os.link(
        f"/proc/self/fd/{unnamed_fd}",
        name,
        dst_dir_fd=parent_fd,
        follow_symlinks=True,
    )
    os._exit(0)


def main() -> None:
    try:
        publish(sys.argv[1:])
    except (OSError, UnicodeError, ValueError):
        os._exit(1)


if __name__ == "__main__":
    main()
```

The open descriptors intentionally rely on process exit for pre-commit cleanup. Do not wrap the final `os.link` in a `finally` block: `os._exit(0)` must run immediately after it returns so no Python cleanup or validation can turn a committed record into a reported failure.

- [ ] **Step 4: Verify the publisher GREEN**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
path = Path('scripts/voice-agent-real-room-diagnostic-publisher.py')
compile(path.read_bytes(), str(path), 'exec')
print('PASS: publisher syntax')
PY
scripts/test-voice-agent-real-room-diagnostic-publisher.sh
git diff --check
```

Expected: Python syntax passes, the publisher test prints `PASS: voice-agent-real-room-diagnostic-publisher (5 assertions)`, and `git diff --check` emits nothing.

- [ ] **Step 5: Commit the publisher module**

```bash
git add scripts/voice-agent-real-room-diagnostic-publisher.py \
  scripts/test-voice-agent-real-room-diagnostic-publisher.sh
git commit -m "feat: add anonymous real-room diagnostic publisher"
```

---

### Task 2: Failure-Only Bash Lifecycle and Real-Helper Verification

**Files:**
- Modify: `scripts/voice-agent-real-room-diagnostics.sh:11-93,103-404`
- Modify: `scripts/voice-agent-real-room-step.sh:6-10,64-77,150-239`
- Modify: `scripts/test-voice-agent-real-room-step.sh:10-14,96-124,1391-1470,1501-1640,1934-1990,3081-3447`
- Test: `scripts/test-voice-agent-real-room-diagnostic-publisher.sh`
- Test: `scripts/test-voice-agent-real-room-step.sh`

**Interfaces:**
- Consumes: Task 1 private CLI `voice-agent-real-room-diagnostic-publisher.py DESTINATION EXPECTED_PARENT_IDENTITY OPERATION STAGE ERROR_CATEGORY CHILD_EXIT_STATUS CLEANUP`.
- Produces: Bash `validate_private_diagnostic_destination PATH`, `diagnostic_initialize start PATH`, existing fixed-state functions, and `diagnostic_publish_failure CLEANUP`.
- Removes: `diagnostic_publish OUTCOME CLEANUP`, `diagnostic_take_published_identity`, `diagnostic_remove_owned_destination`, `DIAGNOSTIC_PARENT_FD`, both `DIAGNOSTIC_IDENTITY_*_FD` globals, `DIAGNOSTIC_PUBLISHED_IDENTITY`, and `diagnostic-publication-failed`.

- [ ] **Step 1: Rewrite tracing assertions for the failure-only contract**

Change `assert_diagnostic_record` to accept `PATH STAGE CATEGORY CHILD CLEANUP` and hardcode `outcome=failure`:

```bash
assert_diagnostic_record() {
  local path="$1" stage="$2" category="$3" child="$4" cleanup="$5"
  python3 - "$path" "$stage" "$category" "$child" "$cleanup" <<'PY'
import os, stat, sys
path, stage, category, child, cleanup = sys.argv[1:]
raw = open(path, "rb").read()
assert raw == (
    "version=1\noperation=start\n"
    f"stage={stage}\noutcome=failure\nerror_category={category}\n"
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

Update `assert_traced_start_failure` to call the five-field assertion. Replace the success case with:

```bash
run_helper start --state "$state" --diagnostic-record "$diagnostic" \
  --mdev-owner OWNER_SECRET_123 --package me.rerere.rikkahub.debug \
  --conversation-id CONVERSATION_SECRET_123 --run-hash "$hash_a" \
  --comparison-hash "$hash_b" --fixture "$fixture"
assert_exact_output $'voice-step.status=ok\nvoice-step.operation=start\nvoice-step.call=active'
[[ ! -e "$diagnostic" ]] ||
  fail "tracing-success test: successful start published a diagnostic record"
pass
```

Retain all destination-validation and failure-stage matrix cases. Replace the obsolete success-publication, post-link mutation, identity-retraction, and signal-after-link cases with exact cases for:

- `O_TMPFILE` failure during an injected `fixture-arm` failure: original nonzero status, exactly one original `voice-step.error=`, exactly one original diagnostic summary, absent record, and no named residue.
- atomic destination creation during the publisher's `os.link`: destination bytes remain `raced`, original failure and summary remain unchanged, and no unlink occurs.
- parent rename plus symlink replacement immediately before publisher parent open: publication fails closed, both sentinels survive, and neither namespace receives a record.
- successful start while the `O_TMPFILE` fault site is active: no fault marker is created, proving success never invoked the publisher.

- [ ] **Step 2: Add closed-descriptor and final-signal regressions**

At harness setup, save `REAL_RM="$(command -v rm)"` before prepending `BIN_DIR` to `PATH`, then add a transparent fake `rm`:

```python
#!/usr/bin/env python3
import os
import signal
import sys

match = os.environ.get("FAKE_RM_SIGNAL_MATCH")
if match and any(match in argument for argument in sys.argv[1:]):
    os.kill(os.getppid(), signal.SIGTERM)
os.execv(os.environ["REAL_RM"], [os.environ["REAL_RM"], *sys.argv[1:]])
```

Export `REAL_RM`, unset `FAKE_RM_SIGNAL_MATCH` in `reset_fake`, and add a successful-start setup with `FAKE_RM_SIGNAL_MATCH=diagnostic-managed-status`. Assert nonzero exit, a `complete/failure/interrupted/none/complete` record, exactly one `voice-step.error=interrupted`, exactly one matching diagnostic summary, and no rollback of already committed state.

Add fake-`mdev` control `FAKE_ASSERT_CLOSED_DIAGNOSTIC_PARENT`. Before processing its command, enumerate `/proc/self/fd`, resolve each descriptor, and exit `96` if any resolves to that exact parent. Run a normal traced success with this control set to the diagnostic parent; assert success, unchanged stdout, and absent diagnostic destination.

- [ ] **Step 3: Run the real-helper tests and verify RED**

Run:

```bash
bash -n scripts/voice-agent-real-room-diagnostics.sh \
  scripts/voice-agent-real-room-step.sh scripts/test-voice-agent-real-room-step.sh
scripts/test-voice-agent-real-room-step.sh tracing
```

Expected: syntax passes; tracing fails first because the current implementation publishes a success record or exposes an inherited diagnostic parent descriptor. The RED is invalid if it is a fixture setup failure, Python traceback, private output, or phone/network access.

- [ ] **Step 4: Reduce validation and initialization to scalar state**

Keep the existing Python destination validator's absolute, normalized, canonical-parent, owner, mode, and absence checks. It must print only `device:inode`. Remove the Bash `exec {DIAGNOSTIC_PARENT_FD}<...` block entirely.

Change `diagnostic_initialize` so it requires only `DIAGNOSTIC_PARENT_IDENTITY`, creates/registers the existing two mode-`0600` state files, and sets these globals:

```bash
DIAGNOSTIC_ENABLED=1
DIAGNOSTIC_OPERATION="$operation"
DIAGNOSTIC_DESTINATION="$destination"
DIAGNOSTIC_STAGE='option-validation'
DIAGNOSTIC_ERROR_CATEGORY='none'
DIAGNOSTIC_CHILD_EXIT_STATUS='none'
DIAGNOSTIC_CAPTURE_MANAGED_EXIT=1
```

Delete the identity-channel `mktemp`, both descriptor opens, the unlink, `diagnostic_take_published_identity`, and `diagnostic_remove_owned_destination`. Delete the corresponding globals from `voice-agent-real-room-step.sh`.

- [ ] **Step 5: Add the Bash failure-publication interface**

Define this private publisher path before sourcing diagnostics:

```bash
REAL_ROOM_DIAGNOSTIC_PUBLISHER="$ROOT_DIR/scripts/voice-agent-real-room-diagnostic-publisher.py"
```

Replace `diagnostic_publish` with:

```bash
diagnostic_publish_failure() {
  local cleanup="$1"
  [[ "${DIAGNOSTIC_ENABLED:-0}" == 1 ]] || return 0
  diagnostic_token_is_valid "$DIAGNOSTIC_OPERATION" || return 1
  diagnostic_token_is_valid "$DIAGNOSTIC_STAGE" || return 1
  diagnostic_token_is_valid "$DIAGNOSTIC_ERROR_CATEGORY" || return 1
  [[ "$DIAGNOSTIC_ERROR_CATEGORY" != none ]] || return 1
  [[ "$cleanup" == complete || "$cleanup" == failed ]] || return 1
  [[ "$DIAGNOSTIC_CHILD_EXIT_STATUS" == none ||
     "$DIAGNOSTIC_CHILD_EXIT_STATUS" =~ ^([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$ ]] || return 1
  [[ "${DIAGNOSTIC_PARENT_IDENTITY:-}" =~ ^[0-9]+:[0-9]+$ ]] || return 1
  python3 "$REAL_ROOM_DIAGNOSTIC_PUBLISHER" \
    "$DIAGNOSTIC_DESTINATION" "$DIAGNOSTIC_PARENT_IDENTITY" \
    "$DIAGNOSTIC_OPERATION" "$DIAGNOSTIC_STAGE" \
    "$DIAGNOSTIC_ERROR_CATEGORY" "$DIAGNOSTIC_CHILD_EXIT_STATUS" "$cleanup" \
    </dev/null >/dev/null 2>&1
}
```

Remove the `diagnostic publication failed` mapping from `diagnostic_error_category`; no successful path can produce that category.

- [ ] **Step 6: Rewrite `on_exit` around a final status decision**

Keep this order:

```text
capture entry status
snapshot original category and child status when entry status is nonzero
disable managed-status recording
run rollback and package restoration
run local temporary cleanup
derive cleanup=complete|failed
apply a previously deferred signal when status was zero
apply cleanup failure when status was zero
map operation-failed only when no fixed category exists
emit a missing fixed error line without duplicating a subshell error
for a still-zero result, restore default HUP/INT/TERM handling and recheck the deferred flag
for a final nonzero result, call diagnostic_publish_failure exactly once
emit exactly one sanitized diagnostic summary
exit final status
```

Delete every success-publication, published-identity, retraction, republish, and `diagnostic_publication_status` branch. The terminal portion must reduce to this shape:

```bash
if (( status == 0 )); then
  trap - HUP INT TERM
  if (( EXIT_CLEANUP_SIGNAL == 1 )); then
    status=1
    diagnostic_note_error 'interrupted'
    if (( ERROR_REPORTED == 0 )); then
      printf 'voice-step.error=interrupted\n' >&2
      ERROR_REPORTED=1
    fi
  fi
fi
if (( status != 0 && DIAGNOSTIC_ENABLED == 1 )); then
  diagnostic_publish_failure "$cleanup" || true
  printf 'voice-step.diagnostic=stage:%s,category:%s\n' \
    "$DIAGNOSTIC_STAGE" "$DIAGNOSTIC_ERROR_CATEGORY" >&2
fi
exit "$status"
```

Preserve the existing earlier logic that uses a snapshotted non-`none` category to suppress a duplicate generic `voice-step.error=` after a `die` inside command substitution.

- [ ] **Step 7: Add source invariants and verify GREEN**

Add a Python source assertion to `run_tracing_tests` that reads the three production files and requires:

```python
assert "O_TMPFILE" in publisher
assert 'f"/proc/self/fd/{unnamed_fd}"' in publisher
assert "follow_symlinks=True" in publisher
assert "os._exit(0)" in publisher
assert publisher.index("os.link(") < publisher.index("os._exit(0)")
for forbidden in (
    ".voice-step-diagnostic.",
    "os.unlink(",
    "diagnostic_publish success",
    "diagnostic_remove_owned_destination",
    "diagnostic_take_published_identity",
    "DIAGNOSTIC_PARENT_FD",
    "DIAGNOSTIC_IDENTITY_READ_FD",
    "DIAGNOSTIC_IDENTITY_WRITE_FD",
    "DIAGNOSTIC_PUBLISHED_IDENTITY",
    "diagnostic-publication-failed",
):
    assert forbidden not in diagnostics + step + publisher
```

Run fresh verification:

```bash
python3 - <<'PY'
from pathlib import Path
path = Path('scripts/voice-agent-real-room-diagnostic-publisher.py')
compile(path.read_bytes(), str(path), 'exec')
print('PASS: publisher syntax')
PY
bash -n scripts/voice-agent-real-room-diagnostics.sh \
  scripts/voice-agent-real-room-lib.sh scripts/voice-agent-real-room-step.sh \
  scripts/test-voice-agent-real-room-diagnostic-publisher.sh \
  scripts/test-voice-agent-real-room-step.sh
scripts/test-voice-agent-real-room-diagnostic-publisher.sh
scripts/test-voice-agent-real-room-step.sh tracing
scripts/test-voice-agent-real-room-step.sh start
scripts/test-voice-agent-real-room-step.sh
git diff --check
```

Expected:

- publisher syntax passes;
- focused publisher tests pass with 5 assertions;
- tracing passes with 30 assertions;
- start passes with 14 assertions;
- the complete host-only suite passes with 294 assertions;
- no traceback, warning, diagnostic path, private value, raw managed output, or `FAIL:` line appears;
- `git diff --check` emits nothing.

- [ ] **Step 8: Run the privacy and scope audit**

Run:

```bash
git diff --name-only da17acf9
git diff da17acf9 -- scripts/voice-agent-real-room-diagnostic-publisher.py \
  scripts/voice-agent-real-room-diagnostics.sh scripts/voice-agent-real-room-step.sh \
  scripts/test-voice-agent-real-room-diagnostic-publisher.sh \
  scripts/test-voice-agent-real-room-step.sh
git status --short
```

Expected: relative to the approved-design commit `da17acf9`, only the five planned scripts are changed; no Agora2, Android, package, service, or deployment file is present. The only unrelated status entry remains `?? scripts/__pycache__/`. Inspect the diff without printing private test output and confirm the record builder is the sole place where payload bytes are assembled.

- [ ] **Step 9: Commit the lifecycle replacement**

```bash
git add scripts/voice-agent-real-room-diagnostics.sh \
  scripts/voice-agent-real-room-step.sh scripts/test-voice-agent-real-room-step.sh
git commit -m "fix: publish real-room diagnostics only on failure"
```

- [ ] **Step 10: Run the post-commit final gate**

Run from the committed HEAD:

```bash
scripts/test-voice-agent-real-room-diagnostic-publisher.sh
scripts/test-voice-agent-real-room-step.sh
git diff --check HEAD~2..HEAD
git status --short
```

Expected: publisher tests pass with 5 assertions, the full helper suite passes with 294 assertions, the two-commit diff is whitespace-clean, and only the unrelated pre-existing `scripts/__pycache__/` is untracked. Do not start physical-phone verification; hand the reviewed host result back for a separate authorization decision.
