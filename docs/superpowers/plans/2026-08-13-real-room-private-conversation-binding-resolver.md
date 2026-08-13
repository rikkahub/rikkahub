# Real-Room Private Conversation Binding Resolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a host-only `resolve-binding` operation that safely resolves the intended existing RikkaHub conversation from a stable private Room snapshot after call-service cleanup.

**Architecture:** Split the work into two narrow Python components and one Bash integration. A read-only selector validates and queries a host-local main-only or main+WAL snapshot; a terminal anonymous-inode publisher owns the atomic commit boundary; the existing real-room helper validates the managed device, captures and verifies the private snapshot, proves cleanup, and then `exec`s the publisher.

**Tech Stack:** Bash 5, Python 3 standard library (`argparse`, `base64`, `hashlib`, `os`, `signal`, `sqlite3`, `stat`, `uuid`), managed `mdev ... adb exec-out run-as`, SQLite Room files, existing shell fake-`mdev` test harness.

## Global Constraints

- Change only RikkaHub host helper scripts and host tests; do not change Android source, the APK, app data, providers, credentials, LiveKit worker state, or managed-device infrastructure.
- Do not build, install, deploy, restart, take over a lane, or use unmanaged ADB.
- The operation is exactly `resolve-binding --mdev-owner OWNER --package PACKAGE --binding-output PATH --created-after-epoch-ms INCLUSIVE --created-before-epoch-ms EXCLUSIVE`.
- Creation bounds are immutable decimal epoch-millisecond inputs with `INCLUSIVE < EXCLUSIVE` and `EXCLUSIVE - INCLUSIVE <= 1800000`; never derive, widen, or infer them.
- Capture exactly `databases/rikka_hub` alone or `databases/rikka_hub` plus `databases/rikka_hub-wal`; never read or transfer `databases/rikka_hub-shm`.
- Main size is `512..67108864` bytes, WAL size is `32..67108864` bytes, and aggregate size is at most `134217728` bytes.
- Bind source components with pre/post device topology, byte size, and SHA-256 digest checks and independently match decoded host size and digest.
- Query only `id` and `create_at` from `ConversationEntity`; never query titles, nodes, `update_at`, prompts, messages, or transcripts.
- Select the unique greatest integer `create_at` within `create_at >= INCLUSIVE AND create_at < EXCLUSIVE`; every in-window ID must be a lowercase canonical UUID and a tied maximum fails closed.
- Never print or record a UUID, private database path from the device, database bytes, managed-device output, or sensitive conversation fields.
- The binding destination must initially be absent beneath a canonical owner-controlled mode-`0700` directory; success creates a mode-`0600`, one-link regular file containing exactly one lowercase canonical UUID plus `\n`.
- All database, WAL, host SHM, Base64, and temporary artifacts must be closed and removed before publication; cleanup failure is terminal while the destination is absent.
- Publication uses a mode-`0600` anonymous `O_TMPFILE`; the descriptor-relative link through `/proc/self/fd` is the final fallible commit.
- Successful installation of `SIG_IGN` for HUP/INT/TERM immediately before the link is the cancellation boundary: handled or pending signals delivered before it fail generically, while signals delivered after it are ignored and cannot cancel publication. This is distinct from the later filesystem commit boundary.
- After a successful link, HUP/INT/TERM and output failure cannot change exit `0`; no cleanup, unlink, shell trap, or outcome-affecting operation follows the commit.
- Success stdout is best-effort and, when writable, is exactly `voice-step.status=ok\nvoice-step.operation=resolve-binding\nvoice-step.binding=resolved\n`; it never contains the UUID.
- Every pre-commit failure is nonzero, uses only the existing fixed sanitized helper error surface, leaves the destination absent, and never retries or invokes a call.
- Preserve unrelated untracked `scripts/__pycache__/` and do not include it in commits.

---

## File Structure

- `scripts/voice-agent-real-room-binding-selector.py`: validate a private local SQLite snapshot and creation window, read only the permitted columns, and return the selected UUID on private stdout.
- `scripts/test-voice-agent-real-room-binding-selector.sh`: construct real main-only and main+WAL SQLite fixtures and test selection/validation without the managed-device layer.
- `scripts/voice-agent-real-room-binding-publisher.py`: own the anonymous-inode publication and irreversible success boundary.
- `scripts/voice-agent-real-room-signal-mask.py`: block HUP/INT/TERM before private resolver work and exec the Bash helper with the mask inherited.
- `scripts/test-voice-agent-real-room-binding-publisher.sh`: isolate filesystem, race, signal, short-write, and output-fault behavior at the commit boundary.
- `scripts/voice-agent-real-room-lib.sh`: add reusable creation-window, private-destination, snapshot capture/verification, and cleanup-proof helpers.
- `scripts/voice-agent-real-room-step.sh`: parse and dispatch `resolve-binding`, orchestrate the managed read-only flow, and terminally `exec` the publisher.
- `scripts/test-voice-agent-real-room-step.sh`: extend the fake managed-device transport and exercise the complete helper operation and regressions.

### Task 1: Read-Only Snapshot Selector

**Files:**
- Create: `scripts/voice-agent-real-room-binding-selector.py`
- Create: `scripts/test-voice-agent-real-room-binding-selector.sh`

**Interfaces:**
- Consumes: command line `MAIN_PATH WAL_PATH_OR_DASH CREATED_AFTER_EPOCH_MS CREATED_BEFORE_EPOCH_MS`; `-` means main-only topology.
- Produces: exit `0` and exactly `UUID\n` on stdout for one valid selection; otherwise a nonzero exit with empty stdout and stderr. The selector does not log, copy, delete, or publish files.

- [ ] **Step 1: Write the failing selector contract tests with real SQLite fixtures**

Create an executable Bash test that uses a private `mktemp -d`, a cleanup trap, and an embedded Python fixture builder. Build a main-only database by creating the schema in WAL mode, inserting rows, closing the last connection, and asserting `rikka_hub-wal` is absent. Build a main+WAL snapshot by keeping a writer connection open with `wal_autocheckpoint=0`, copying the live main and WAL bytes into a separate private directory, then closing the source connection. Use this exact schema and row fields so the tests prove the Room names while allowing `update_at` to differ:

```python
connection.execute("PRAGMA journal_mode=WAL")
connection.execute("PRAGMA wal_autocheckpoint=0")
connection.execute(
    "CREATE TABLE ConversationEntity ("
    "id TEXT NOT NULL PRIMARY KEY, "
    "create_at INTEGER NOT NULL, "
    "update_at INTEGER NOT NULL)"
)
connection.executemany(
    "INSERT INTO ConversationEntity(id, create_at, update_at) VALUES (?, ?, ?)",
    rows,
)
connection.commit()
```

Exercise exact cases with a window `[1776070800000, 1776072600000)`:

```text
main-only: older=11111111-1111-4111-8111-111111111111 at 1776070860000,
           intended=22222222-2222-4222-8222-222222222222 at 1776070920000
main+WAL: same expected selection
older-later-update: older update_at=1776072500000 and intended update_at=1776070920000; intended still wins
duplicate older timestamps then unique newest: two rows share 1776070860000 and one row has 1776070920000; the unique newest wins in both main-only and main+WAL snapshots
empty window: nonzero and no output
tied maximum: two canonical UUIDs at 1776070920000; nonzero and no output
malformed in-window UUID: uppercase or non-UUID; nonzero and no output
non-integer in-window create_at: store text through a schema without the NOT NULL integer constraint; nonzero and no output
invalid windows: negative, leading sign/space, equal bounds, reversed bounds, and 1800001 ms span; nonzero and no output
invalid snapshot: missing main, WAL without main, malformed main, symlink main, symlink WAL, and unexpected WAL argument for main-only; nonzero and no output
```

For every failure, assert both captured stdout and stderr are empty. Also inspect the selector source and assert it contains no `update_at`, title, node, prompt, message, or transcript SQL token.

- [ ] **Step 2: Run the selector tests to verify they fail**

Run: `bash scripts/test-voice-agent-real-room-binding-selector.sh`

Expected: FAIL because `scripts/voice-agent-real-room-binding-selector.py` does not exist.

- [ ] **Step 3: Implement strict argument, snapshot, and row validation**

Create the selector with this public structure and no diagnostic output:

```python
#!/usr/bin/env python3
import os
import sqlite3
import stat
import sys
import uuid
from pathlib import Path

MAX_WINDOW_MS = 1_800_000
MAIN_MIN_BYTES = 512
MAIN_MAX_BYTES = 64 * 1024 * 1024
WAL_MIN_BYTES = 32
WAL_MAX_BYTES = 64 * 1024 * 1024
AGGREGATE_MAX_BYTES = 128 * 1024 * 1024

def parse_epoch_ms(value: str) -> int:
    if not value.isascii() or not value.isdecimal():
        raise ValueError
    return int(value, 10)

def canonical_uuid(value: object) -> str:
    if not isinstance(value, str):
        raise ValueError
    parsed = uuid.UUID(value)
    if str(parsed) != value:
        raise ValueError
    return value

def validate_regular(path: Path, minimum: int, maximum: int) -> int:
    metadata = path.lstat()
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
        raise ValueError
    if metadata.st_size < minimum or metadata.st_size > maximum:
        raise ValueError
    return metadata.st_size
```

Require exactly four arguments, validate the bounds and aggregate size, require absolute normalized component paths in the same canonical mode-`0700` parent, and reject an on-disk `MAIN_PATH-wal` when the topology argument is `-`. Open main-only with URI `file:<quoted path>?mode=ro&immutable=1`; open main+WAL with `file:<quoted path>?mode=ro`, after verifying the passed WAL is exactly `MAIN_PATH + "-wal"`. Set `PRAGMA query_only=ON`, execute only:

```sql
SELECT id, create_at
FROM ConversationEntity
WHERE create_at >= ? AND create_at < ?
```

Reject Python `bool`, non-`int` timestamps, malformed UUIDs, empty results, and a non-unique greatest timestamp. Always close the connection in `finally`. Write the UUID with one `os.write` loop to fd 1 only after selection has completely succeeded. Wrap `main()` with `try: ... except (OSError, ValueError, sqlite3.Error): os._exit(1)` so failure remains silent.

- [ ] **Step 4: Run the selector tests and syntax check**

Run: `python3 -m py_compile scripts/voice-agent-real-room-binding-selector.py && bash scripts/test-voice-agent-real-room-binding-selector.sh`

Expected: PASS; both snapshot topologies select `22222222-2222-4222-8222-222222222222`, all fail-closed cases emit no bytes, and no SQLite sidecar remains outside the private fixture directory.

- [ ] **Step 5: Commit the selector**

```bash
git add scripts/voice-agent-real-room-binding-selector.py scripts/test-voice-agent-real-room-binding-selector.sh
git commit -m "feat: add private conversation snapshot selector"
```

### Task 2: Terminal Anonymous-Inode Binding Publisher

**Files:**
- Create: `scripts/voice-agent-real-room-binding-publisher.py`
- Create: `scripts/test-voice-agent-real-room-binding-publisher.sh`

**Interfaces:**
- Consumes: command line `DESTINATION EXPECTED_PARENT_IDENTITY CONVERSATION_ID`, where parent identity is decimal `st_dev:st_ino` and the UUID is lowercase canonical.
- Produces: before commit, nonzero failure, absent destination, empty stdout, and exactly `voice-step.error=operation failed\n` on stderr; after descriptor-relative link succeeds, permanent mode-`0600` file plus exit `0`. Writable stdout receives exactly the three fixed success lines; stdout failure after link is ignored.

- [ ] **Step 1: Write isolated failing publication-boundary tests**

Create an executable Bash test modeled on `test-voice-agent-real-room-diagnostic-publisher.sh`. Each test creates a private mode-`0700` parent, computes `stat -Lc '%d:%i'`, invokes the publisher, and never passes a real conversation ID to logs. Cover:

```text
normal: exact fixed stdout, empty stderr, byte-exact UUID newline, regular mode 0600, nlink 1
invalid UUID and invalid/mismatched parent identity: nonzero, empty stdout, exact fixed error stderr, destination absent
pre-existing destination: nonzero, exact fixed error stderr, and existing bytes unchanged
O_TMPFILE failure: inject a Python sitecustomize wrapper around os.open; nonzero, exact fixed error stderr, and destination absent
short write: inject an os.write wrapper that writes a prefix once; publisher's write_all completes exact bytes
short write then error before link: nonzero and destination absent
destination race at os.link: raced file remains unchanged; publisher nonzero
parent replacement before link: replacement parent receives no file; publisher nonzero
HUP, INT, and TERM before successful installation of the final ignore disposition: nonzero and destination absent
HUP, INT, and TERM injected by the os.link wrapper immediately before the real link: ignored after the cancellation boundary; exact destination and exit 0
HUP, INT, and TERM immediately after successful link: destination remains exact and exit 0
stdout EPIPE/write error after link: destination remains exact and exit 0
```

The injection wrapper must hook the publisher's single `os.link` call without adding a named temporary file. It must inject the cancellation-boundary signal immediately before calling the real link, proving honestly that a wall-clock pre-link signal after `SIG_IGN` is ignored. Preserve separate pre-ignore and pending-signal tests that fail generically. For post-cancellation and post-link signal/output cases, assert no `unlink`, `remove`, or cleanup hook runs after their marker.

- [ ] **Step 2: Run publisher tests to verify they fail**

Run: `bash scripts/test-voice-agent-real-room-binding-publisher.sh`

Expected: FAIL because `scripts/voice-agent-real-room-binding-publisher.py` does not exist.

- [ ] **Step 3: Implement the publisher with one final fallible commit**

Implement these exact boundaries:

```python
SUCCESS = (
    b"voice-step.status=ok\n"
    b"voice-step.operation=resolve-binding\n"
    b"voice-step.binding=resolved\n"
)

def write_all(fd: int, payload: bytes) -> None:
    offset = 0
    while offset < len(payload):
        written = os.write(fd, payload[offset:])
        if written <= 0:
            raise OSError
        offset += written
```

Validate exactly three arguments, absolute normalized destination, absent basename without `/`, canonical owner-controlled non-symlink mode-`0700` parent, expected `dev:ino`, and lowercase canonical UUID. Open the parent with `O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW`, revalidate identity/owner/mode from `fstat`, and create the inode with:

```python
fd = os.open(".", os.O_RDWR | os.O_CLOEXEC | os.O_TMPFILE, 0o600, dir_fd=parent_fd)
os.fchmod(fd, 0o600)
write_all(fd, payload)
os.fsync(fd)
os.lseek(fd, 0, os.SEEK_SET)
if os.read(fd, len(payload) + 1) != payload:
    raise OSError
metadata = os.fstat(fd)
```

Require a regular inode, mode `0600`, `st_nlink == 0`, and exact size. Revalidate the parent and absent destination through the pinned fd. Immediately before link, successfully install `SIG_IGN` for `SIGHUP`, `SIGINT`, and `SIGTERM`. This is the cancellation boundary: earlier handled or pending signals fail generically; later signals are ignored and cannot cancel publication. Then make the descriptor-relative link the final fallible filesystem commit and the last outcome-affecting operation:

```python
os.link(
    f"/proc/self/fd/{fd}",
    destination.name,
    dst_dir_fd=parent_fd,
    follow_symlinks=True,
)
```

After that call returns, catch and ignore every exception from `write_all(1, SUCCESS)`, then call `os._exit(0)` unconditionally. The post-link path must not stat, fsync, close, unlink, format dynamic data, restore signals, or return to a general exception handler. Any exception before link makes one best-effort `write_all(2, b"voice-step.error=operation failed\n")` attempt and then calls `os._exit(1)` regardless of stderr success; anonymous fds may be kernel-cleaned on process exit. No exception details or dynamic values are emitted.

- [ ] **Step 4: Run publisher syntax and boundary tests**

Run: `python3 -m py_compile scripts/voice-agent-real-room-binding-publisher.py && bash scripts/test-voice-agent-real-room-binding-publisher.sh`

Expected: PASS, including generic failure for signals delivered before the cancellation boundary, ignored signals injected immediately before the real link after that boundary, and committed success under every post-link signal and stdout fault.

- [ ] **Step 5: Commit the publisher**

```bash
git add scripts/voice-agent-real-room-binding-publisher.py scripts/test-voice-agent-real-room-binding-publisher.sh
git commit -m "feat: add terminal private binding publisher"
```

### Task 3: Managed Resolver Integration and Complete Regression Suite

**Files:**
- Create: `scripts/voice-agent-real-room-signal-mask.py`
- Modify: `scripts/voice-agent-real-room-binding-publisher.py`
- Modify: `scripts/test-voice-agent-real-room-binding-publisher.sh`
- Modify: `scripts/voice-agent-real-room-lib.sh`
- Modify: `scripts/voice-agent-real-room-step.sh`
- Modify: `scripts/test-voice-agent-real-room-step.sh`

**Interfaces:**
- Consumes: Task 1 executable `voice-agent-real-room-binding-selector.py MAIN WAL_OR_DASH AFTER BEFORE`; Task 2 executable `voice-agent-real-room-binding-publisher.py DESTINATION PARENT_DEV_INO UUID`.
- Produces: public helper operation `resolve-binding --mdev-owner OWNER --package PACKAGE --binding-output PATH --created-after-epoch-ms INCLUSIVE --created-before-epoch-ms EXCLUSIVE`, with terminal success/failure semantics from the Global Constraints.

The signal-mask launcher consumes `HELPER_PATH` followed by the unchanged
helper argv, calls `signal.pthread_sigmask(signal.SIG_BLOCK, {SIGHUP, SIGINT,
SIGTERM})`, sets one fixed private re-exec marker, and replaces itself with the
helper through `os.execve`. The helper re-execs through it only for
`resolve-binding` and only before validation or private work. The publisher
installs its pre-link failure handlers, calls `signal.pthread_sigmask` to
unblock exactly those signals, and only then proceeds toward its final link.
Successful installation of the final `SIG_IGN` dispositions is the cancellation
boundary; the later descriptor-relative link remains the final fallible
filesystem commit.

- [ ] **Step 1: Add failing end-to-end fake-managed-device tests**

Extend the selector allow-list and dispatch at the end of `test-voice-agent-real-room-step.sh` with `resolve-binding` and `run_resolve_binding_tests`. Extend `reset_fake` to construct `REMOTE_APP_DATA_ROOT/databases` privately. Add a resolver-specific fake `exec-out run-as ... sh -c` marker that executes fixed metadata and Base64 actions against real fixture files, records requested component names, and exposes injection flags at three phases: before metadata, after encoded transfer, and before post-metadata.

Add focused tests which call:

```bash
run_helper resolve-binding \
  --binding-output "$binding_output" \
  --created-after-epoch-ms 1776070800000 \
  --created-before-epoch-ms 1776072600000
```

The helper test must cover both stable main-only and main+WAL fixtures while the fake call service is inactive. On success assert exact fixed stdout, no UUID in output/logs, exact private payload, regular mode `0600`, and one link. Assert the command log contains reads only for `rikka_hub` and optional `rikka_hub-wal`, and contains no `rikka_hub-shm` string.

Invoke the helper through both `scripts/voice-agent-real-room-step.sh` and `./scripts/voice-agent-real-room-step.sh` from the repository root and require normal resolver success. The helper must derive and pass its normalized absolute self path to the absolute-path-only signal launcher; these regressions reuse ordinary small main-only fixtures and must not duplicate the 64 MiB matrix.

Add failure matrices for invalid/missing arguments; insecure/pre-existing/symlink destinations; no candidate, tied maximum, malformed UUID/timestamp, older row with later `update_at`; missing main, WAL-only, component symlink/nonregular; every individual and aggregate size bound; host decoded size/digest mismatch; pre/post topology delta; pre/post content delta; malformed Base64; and selector failure. Each failure must be nonzero, match the existing fixed `voice-step.error=operation failed` surface, leave the destination absent, and leave no resolver artifact in the private temp root.

Instrument cleanup ordering with `resolver-cleanup-complete` and publisher-link markers. Inject removal failure and assert cleanup failure precedes and prevents publisher invocation. At the publication boundary, reuse the existing state-publication sitecustomize race helpers for O_TMPFILE failure, short write, parent replacement, destination race, pre-ignore and pending signals, signals injected immediately before the real link after the cancellation boundary, post-link signals, and stdout failure. Assert post-cancellation signal cases proceed to an exact file and exit `0`, and preserve generic failure before the cancellation boundary.

- [ ] **Step 2: Run the focused helper test to verify it fails**

Run: `bash scripts/test-voice-agent-real-room-step.sh resolve-binding`

Expected: FAIL because the helper rejects `resolve-binding` as an unknown operation.

- [ ] **Step 3: Add binding-specific validation and capture primitives to the library**

Add globals initialized by the caller: `BINDING_PARENT_IDENTITY`, `BINDING_SNAPSHOT_MAIN`, `BINDING_SNAPSHOT_WAL`, and `BINDING_SNAPSHOT_TOPOLOGY`. Implement:

```bash
validate_creation_window AFTER BEFORE
validate_private_binding_destination PATH
capture_private_binding_snapshot PACKAGE AFTER BEFORE
cleanup_private_binding_snapshot
```

`validate_creation_window` accepts ASCII digits only, uses base-10 arithmetic without octal interpretation, enforces nonnegative values, ordering, and the `1800000` maximum span. `validate_private_binding_destination` follows `validate_private_diagnostic_destination`: require an absolute normalized path, absent leaf, canonical non-symlink parent, effective-user ownership, mode `0700`, and record `stat -Lc '%d:%i'` as `BINDING_PARENT_IDENTITY`.

`capture_private_binding_snapshot` must use `run_as_script exec-out` with fixed positional arguments and a constant marker. The on-device script must open only `databases/rikka_hub` and, if present, `databases/rikka_hub-wal`; use `test -L`, `stat`, `sha256sum`, and `base64`; delimit only nonsensitive metadata with fixed tags; never expand device output into a shell command or print it. Validate pre-metadata, decode each Base64 payload under `LOCAL_TEMP_DIR`, validate host size/digest, then capture and compare post-metadata. Reject all topology except main or main+WAL and enforce all exact byte bounds before and after transfer.

Register every snapshot and encoded intermediate with `register_temp_file`. Do not register or construct a path outside `LOCAL_TEMP_DIR`. Ensure managed child fds are closed except stdio. `cleanup_private_binding_snapshot` closes/removes main, WAL, any host-created `-shm`, encoded files, and all other registered files through the established cleanup functions, then proves the private directory has no entries. It returns nonzero on any failure and runs while the destination is still absent.

- [ ] **Step 4: Add the terminal `run_resolve_binding` orchestration and dispatch**

Add constants:

```bash
REAL_ROOM_BINDING_SELECTOR="$ROOT_DIR/scripts/voice-agent-real-room-binding-selector.py"
REAL_ROOM_BINDING_PUBLISHER="$ROOT_DIR/scripts/voice-agent-real-room-binding-publisher.py"
```

Parse the five exact operation options into local values and reject duplicates/unknowns with the existing parser. In `run_resolve_binding`, use this order:

```text
validate_runtime
validate managed owner and exact debug package
acquire_host_operation_lock
ensure_device_and_package
resolve_package_identity
verify_package_contract
validate_creation_window
validate_private_binding_destination
ensure_local_temp_dir
capture_private_binding_snapshot
UUID=$(python3 selector MAIN WAL_OR_DASH AFTER BEFORE)
validate UUID again without emitting it
cleanup_private_binding_snapshot and prove destination remains absent
disable EXIT/HUP/INT/TERM shell traps
exec python3 publisher DESTINATION BINDING_PARENT_IDENTITY UUID
```

Capture selector stdout only in a command-local variable and never export, print, diagnose, or place it in a file. On selector failure, clear the variable before the fixed `die` path. Immediately before `exec`, require `OWNED_TEMP_FILES` empty and the private temp directory absent or empty; clear `LOCAL_TEMP_DIR` so no Bash EXIT cleanup owns anything. `exec` is mandatory: successful publication must never return through `on_exit`.

- [ ] **Step 5: Run focused component and integration tests**

Run:

```bash
python3 -m py_compile \
  scripts/voice-agent-real-room-binding-selector.py \
  scripts/voice-agent-real-room-binding-publisher.py
bash scripts/test-voice-agent-real-room-binding-selector.sh
bash scripts/test-voice-agent-real-room-binding-publisher.sh
bash scripts/test-voice-agent-real-room-step.sh resolve-binding
```

Expected: PASS for both component suites and every focused resolver assertion.

- [ ] **Step 6: Run the complete host-only helper regression suite and privacy checks**

Run:

```bash
bash scripts/test-voice-agent-real-room-step.sh
git diff --check
if rg -n 'rikka_hub-shm|SELECT[^;]*(update_at|title|node|prompt|message|transcript)' \
  scripts/voice-agent-real-room-binding-selector.py \
  scripts/voice-agent-real-room-{lib,step}.sh; then
  exit 1
fi
```

Expected: the complete helper suite passes with all prior assertions unchanged; `git diff --check` is silent; the privacy query scan is silent. The test harness may contain `rikka_hub-shm` only in negative assertions, so exclude `scripts/test-voice-agent-real-room-step.sh` from that production-source scan.

- [ ] **Step 7: Commit the managed resolver integration**

```bash
git add scripts/voice-agent-real-room-lib.sh scripts/voice-agent-real-room-step.sh scripts/test-voice-agent-real-room-step.sh
git commit -m "feat: resolve private real-room conversation binding"
```

## Final Verification

After all task reviews are approved, run from `/home/muly/code/rikkahub`:

```bash
python3 -m py_compile \
  scripts/voice-agent-real-room-binding-selector.py \
  scripts/voice-agent-real-room-binding-publisher.py
bash scripts/test-voice-agent-real-room-binding-selector.sh
bash scripts/test-voice-agent-real-room-binding-publisher.sh
bash scripts/test-voice-agent-real-room-step.sh resolve-binding
bash scripts/test-voice-agent-real-room-step.sh
git diff --check
git status --short
```

Expected: every command through `git diff --check` succeeds; status contains only the known unrelated untracked `scripts/__pycache__/` plus no uncommitted resolver changes. A fresh broad review must confirm the commit boundary, snapshot stability checks, privacy surface, and unchanged existing operations before any physical-phone verification round.
