# Real-Room Helper Failure Tracing Design

## Goal

Make every failed real-room helper `start` invocation identify its current
boundary—the last stage it entered—without exposing device identifiers,
managed-owner values, hashes, conversation data, fixture contents, commands,
or raw stderr.

The immediate acceptance case is a failed `start`: instead of retaining only
the broad `fixture-start` orchestration result, the helper must publish a fixed
failure stage such as `trace-read` or `fixture-stage` and preserve a private
diagnostic record through cleanup.

## Approaches Considered

Three publication approaches were considered:

1. Retain raw Android, LiveKit, or helper stderr. This misses failures before a
   command reaches Android and is unsafe to copy into ordinary reports.
2. Publish success and failure records through named temporary files. This
   requires post-publication retraction when a deferred signal changes a
   successful result. POSIX does not provide an atomic "unlink this name only
   if it still identifies inode X" operation, so retraction cannot both remove
   the owned record and guarantee that a concurrent replacement is preserved.
3. Publish only failure records from an unnamed inode. The final failure state
   is known before publication, and one atomic link is the only namespace
   commit. Pre-commit failure closes the unnamed inode; post-commit deletion or
   identity transfer is unnecessary.

Use approach 3. A long-lived publisher process could preserve success records,
but its process, signal, and control-channel lifecycle would add complexity that
does not serve the goal of retaining evidence for failed verification.

## Design

Add a tracing seam to `scripts/voice-agent-real-room-lib.sh` and use it from the
`start` path in `scripts/voice-agent-real-room-step.sh`. It tracks only fixed,
source-defined tokens:

- operation;
- current stage;
- fixed error category;
- managed-ADB child exit status when a managed command fails; and
- cleanup outcome.

The helper advances the current stage immediately before each meaningful
boundary. `start` uses these stages:

```text
option-validation
runtime-validation
host-lock
fixture-snapshot
device-readiness
package-identity
package-contract
status-read
trace-read
fixture-directory
fixture-stage
automation-prepare
fixture-arm
service-start
call-activation
trace-activation
state-publication
complete
```

Stage and error-category values are validated against conservative token
syntax and are assigned only from literals in the helper. Caller-provided
values never enter tracing fields. Other helper operations are unchanged and
can adopt the same seam later if evidence warrants it.

The existing `die` function maps its known, fixed error messages to fixed error
categories before printing the existing `voice-step.error=...` line. Unknown
messages map to `operation-failed`; free-form text never enters the record. The
managed-ADB seam captures its numeric exit status in a private local state file
so failures inside command substitutions remain visible to the parent's `EXIT`
trap. It records no argv, stdout, or stderr. The `EXIT` trap records the final
failure and cleanup result after cleanup has run. A successful invocation
creates no diagnostic record.

## Diagnostic Record

Tracing is opt-in through `--diagnostic-record`, accepted only by `start`. The
destination must be absolute and normalized, must be absent, must have a
canonical real owner-owned mode-`0700` parent, and must be distinct from the
state destination. Validation captures the parent's device and inode as
private scalar state, then closes every validation descriptor. No diagnostic
descriptor remains inherited across managed-device or helper child processes.

After an invocation has a final nonzero status, a private Python publisher
reopens the canonical parent with `O_DIRECTORY|O_NOFOLLOW` and requires its
device, inode, owner, type, and mode to match the validation snapshot. It
creates an unnamed mode-`0600` inode in that directory with `O_TMPFILE`, writes
and fsyncs the payload, and verifies through the open descriptor that the inode
is a regular file with mode `0600`, link count zero, and the exact expected
bytes.

The publisher commits with one host-local `linkat` call. Its source is the
publisher process's fixed `/proc/self/fd/<fd>` reference with
`AT_SYMLINK_FOLLOW`; its destination is the absent basename relative to the
verified parent descriptor. The link fails rather than overwriting an existing
destination. A successful link gives the inode exactly one name and is the last
fallible publication operation. The publisher performs no post-link
verification, deletion, retraction, or identity transfer. Before that commit,
closing the descriptor removes the unnamed inode without a cleanup pathname.

This mechanism requires a Linux host filesystem that supports `O_TMPFILE` and
linking its unnamed inode through `/proc/self/fd`. Lack of either capability is
a failure-publication error, not a reason to use a named temporary fallback.

The record is a strict line-oriented format containing exactly these keys:

```text
version=1
operation=<fixed-token>
stage=<fixed-token>
outcome=failure
error_category=<fixed-token>
child_exit_status=<0-255>|none
cleanup=complete|failed
```

No timestamps or correlation identifiers are needed: the caller already owns
the destination path for one invocation. The record contains no free-form
text. A publication failure does not replace the operation's original failure.
The sanitized terminal summary remains available when a failure record cannot
be published. A successful operation leaves the destination absent.

The terminal remains concise. On failure the helper adds one sanitized line:

```text
voice-step.diagnostic=stage:<fixed-token>,category:<fixed-token>
```

It never prints the diagnostic path or child exit status. Successful operation
output remains unchanged.

## Publication Trust Model

The publisher protects against parent-path replacement, symlink substitution,
destination creation races, and accidental concurrent namespace changes. It
never overwrites or deletes a destination and never creates a named temporary.

A malicious process with the same effective user and authority to inspect or
modify another process is inside the caller's trust boundary. Defending against
that process would require a separate security principal; mode bits, private
descriptors, and unpredictable names cannot isolate two mutually hostile
processes running as the same user.

## Cleanup and Privacy

The failure record is intentionally outside `LOCAL_TEMP_DIR`, so normal
temporary cleanup cannot erase it. The outer operator owns its lifecycle and
must keep it private until diagnosis finishes, then remove it according to the
existing evidence policy. A successful invocation leaves no record for the
outer operator to remove.

The record must never include:

- commands or arguments;
- filesystem source paths other than the operator-selected destination, which
  is not written into the record;
- device, host, owner, package UID, conversation, run, comparison, fixture, or
  trace identifiers;
- fixture bytes, transcripts, prompts, answers, credentials, URLs, or logs; or
- raw exception, stdout, or stderr text.

Diagnostic probes and live attempts remain distinguishable because each helper
invocation uses one caller-selected destination.

## Error Handling

- An invalid or pre-existing diagnostic destination fails during option
  validation before device access.
- Failure-record publication preserves the original nonzero operation result.
- Successful operation performs no diagnostic publication and leaves the
  destination absent.
- Signal handling and rollback use the stage already active at interruption.
- Cleanup failure is represented independently as `cleanup=failed`.
- The broad external orchestration label, such as `fixture-start`, remains
  unchanged; the diagnostic record supplies the actionable substage.

At exit, the helper first snapshots the original diagnostic error and managed
child state, then disables managed-status capture before rollback and package
restoration. It derives the cleanup result after local temporary cleanup and
applies signal and cleanup-status rules without replacing an existing operation
failure. A snapshotted non-`none` category also proves that a specific error was
already emitted inside a command substitution, so the parent trap does not add
a duplicate generic error.

Before a zero-status exit, the helper restores default `HUP`, `INT`, and `TERM`
handling and checks the deferred-signal flag one final time. A signal already
observed by the trap changes the result to `complete/failure/interrupted`; a
later signal receives default nonzero termination. Only a final nonzero result
enters the publisher. A signal received during failure publication cannot turn
success into failure because success never invokes the publisher, and it does
not replace an operation failure already selected by precedence.

## Module Structure

- `scripts/voice-agent-real-room-diagnostics.sh` owns the sourced Bash state
  interface: initialization, stage changes, fixed error mapping, managed child
  status, private-state snapshotting, and `diagnostic_publish_failure`.
- `scripts/voice-agent-real-room-diagnostic-publisher.py` owns the private
  one-shot filesystem implementation: parent verification, unnamed inode
  construction, exact payload verification, and atomic publication. It emits
  no stdout or stderr.
- `scripts/voice-agent-real-room-step.sh` owns operation ordering, rollback,
  final status precedence, the one sanitized terminal summary, and the single
  failure-publication call.

The Python publisher is an implementation behind the Bash diagnostics
interface, not a new public command. Tests exercise it through the real helper;
focused implementation tests may replace private Python functions without
adding production environment controls.

## Testing

Extend `scripts/test-voice-agent-real-room-step.sh` using its existing fake
managed-device failure injection. Tests must execute the real helper and prove:

- an injected failure at each failure-capable `start` boundary records the
  expected stage, while a successful run leaves the destination absent;
- a managed-ADB child exit status is recorded without command text or private
  values;
- cleanup completion or failure is recorded after rollback;
- the diagnostic file survives helper temporary cleanup with mode `0600` and
  link count one;
- a pre-existing or insecure destination is rejected before managed-device
  access;
- an unavailable or faulted `O_TMPFILE` or atomic link leaves no named residue
  and does not mask an earlier operation failure;
- parent rename and symlink replacement cannot redirect publication or delete
  anything in the original or replacement namespace;
- a destination created at the atomic commit race is neither overwritten nor
  deleted;
- a deferred signal before final success produces a
  `complete/failure/interrupted` record and nonzero exit;
- no long-lived diagnostic parent or identity descriptor is inherited by a
  managed-device child;
- successful helper output remains byte-for-byte unchanged; and
- the diagnostic record contains only the exact allowlisted keys and values.

Source invariants reject named diagnostic temporaries, destination unlinking,
success publication, published-inode identity channels, and retained diagnostic
parent descriptors. Verification consists of focused tracing tests, the
complete real-room helper suite, shell and Python syntax checks, and a diff
privacy review. No physical fixture, service start, build, install, deploy, or
network call is part of this work.

## Scope

Only the standalone RikkaHub helper, its private publisher, its fake-device
test suite, and this design documentation change. Agora2 workspace files,
Android application code, the installed APK, `mdev`, LiveKit services, other
helper operations, and deployed infrastructure remain unchanged.
