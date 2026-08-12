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

Three approaches were considered:

1. Add more Android and LiveKit logs. This does not cover failures before a
   command reaches Android, which is exactly where the latest failure occurred.
2. Keep only the helper's raw stderr. This can reveal the terminal error but
   remains fragile when cleanup deletes temporary files and is unsafe to copy
   into ordinary reports.
3. Add structured host-helper tracing with a private record and a sanitized
   terminal summary. This covers local, managed-ADB, Android-shell, receiver,
   service-start, and activation boundaries while keeping sensitive values out
   of the reportable output.

Use approach 3. Raw stderr may still be retained by an outer private runner,
but the helper tracing contract must not depend on it.

## Design

Add a small tracing seam to `scripts/voice-agent-real-room-lib.sh` and use it
from the `start` path in `scripts/voice-agent-real-room-step.sh`. It tracks only
fixed, source-defined tokens:

- operation;
- current stage;
- outcome;
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
messages map to `operation-failed`; free-form text never enters the record.
The managed-ADB boundary captures its numeric exit status in a private local
state file so failures inside command substitutions remain visible to the
parent's `EXIT` trap. It records no argv, stdout, or stderr. The `EXIT` trap
records the final outcome and cleanup result after cleanup has run.

## Diagnostic Record

Tracing is opt-in through a new `--diagnostic-record` option accepted by
`start`. The destination must satisfy the helper's existing absent-destination
checks plus an owner-only (`0700`) parent-directory check, and it must be
distinct from the state destination. The helper creates it atomically with mode
`0600` and publishes it only at exit.

The record is a strict line-oriented format containing only these keys:

```text
version=1
operation=<fixed-token>
stage=<fixed-token>
outcome=success|failure
error_category=<fixed-token>|none
child_exit_status=<0-255>|none
cleanup=complete|failed
```

No timestamps or correlation identifiers are needed: the caller already owns
the destination path for one invocation. The record contains no free-form
text. A publication failure must not replace the operation's original failure;
on an otherwise successful operation it becomes a helper failure.

The terminal remains concise. On failure the helper adds one sanitized line:

```text
voice-step.diagnostic=stage:<fixed-token>,category:<fixed-token>
```

It never prints the diagnostic path or child exit status. Successful operation
output remains unchanged.

## Cleanup and Privacy

The diagnostic record is intentionally outside `LOCAL_TEMP_DIR`, so normal
temporary cleanup cannot erase it. The outer operator owns its lifecycle and
must keep it private until diagnosis finishes, then remove it according to the
existing evidence policy.

The record must never include:

- commands or arguments;
- filesystem source paths other than the operator-selected destination, which
  is not written into the record;
- device, host, owner, package UID, conversation, run, comparison, fixture, or
  trace identifiers;
- fixture bytes, transcripts, prompts, answers, credentials, URLs, or logs; or
- raw exception, stdout, or stderr text.

Diagnostic probes and live attempts remain distinguishable because each helper
invocation writes only to its caller-selected record.

## Error Handling

- An invalid or pre-existing diagnostic destination fails during option
  validation before device access.
- Failure-record publication preserves the original nonzero operation result.
- A successful operation whose record cannot be published fails with the fixed
  `diagnostic publication failed` category.
- Signal handling and rollback use the stage already active at interruption.
- Cleanup failure is represented independently as `cleanup=failed`.
- The broad external orchestration label, such as `fixture-start`, remains
  unchanged; the diagnostic record supplies the actionable substage.

At exit, the helper first snapshots the original diagnostic error and managed
child state, then disables managed-status capture before rollback and package
restoration. It derives the cleanup result after local temporary cleanup,
applies signal and cleanup-status rules without replacing an existing operation
failure, and publishes the record last. A failed publication is ignored after
an original nonzero result; only after an otherwise successful operation does
it set the final failure category to `diagnostic-publication-failed`.

## Testing

Extend `scripts/test-voice-agent-real-room-step.sh` using its existing fake
managed-device failure injection. Tests must execute the real helper and prove:

- an injected failure at each failure-capable `start` boundary records the
  expected stage, while a successful run records `complete`;
- a managed-ADB child exit status is recorded without command text or private
  values;
- cleanup completion or failure is recorded after rollback;
- the diagnostic file survives helper temporary cleanup with mode `0600`;
- a pre-existing or insecure destination is rejected before managed-device
  access;
- publication failure does not mask an earlier operation failure;
- successful helper output remains byte-for-byte unchanged; and
- the diagnostic record contains only the exact allowlisted keys and values.

Verification consists of focused tracing tests, the complete real-room helper
suite, shell syntax checks, and a diff privacy review. No physical fixture,
service start, build, install, deploy, or network call is part of this work.

## Scope

Only the standalone RikkaHub helper, its fake-device test suite, and this design
documentation change. Agora2 workspace files, Android application code, the
installed APK, `mdev`, LiveKit services, other helper operations, and deployed
infrastructure remain unchanged.
