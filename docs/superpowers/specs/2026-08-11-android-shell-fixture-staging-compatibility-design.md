# Android Shell Fixture Staging Compatibility Design

## Goal

Make the real-room voice helper's app-private scripts compatible with the
Android system shell used by the managed physical-phone lane. The fix must let
fixture creation and staging retain their descriptor-bound ownership checks
without changing the app, APK, LiveKit service, or call lifecycle.

## Root Cause

The helper's remote scripts assume two Bash behaviors that Android `sh` does
not provide:

1. External utilities invoked by the script resolve `/proc/self/fd/*` against
   the utility process, not the shell that opened the descriptor. The shell's
   descriptors are available to those utilities through `/proc/$$/fd/*`.
2. The marker-validation pattern uses the ANSI-C expression `$'\n'`. Android
   `sh` does not expand it to the required newline in this context.

An exact managed-phone replica of directory creation, marker validation,
descriptor-owned fixture staging, and cleanup completes when the descriptor
paths use the shell PID and the newline is constructed with POSIX shell syntax.
The same checks fail at their respective assertions with the current forms.

## Design

Change only scripts executed by Android `run-as sh -c` in
`scripts/voice-agent-real-room-lib.sh`:

- Refer to shell-owned descriptors as `/proc/$$/fd/*` everywhere an Android
  utility must inspect or use them.
- Construct one newline with POSIX `printf` plus parameter expansion before
  validating the ownership marker, then match the marker against that variable.

Do not change the host-side `/proc/self/fd/*` lock. It runs under host Bash and
correctly refers to the process performing the lock operation.

All existing path constraints, inode comparisons, modes, hard-link counts,
ownership receipts, exclusive creation, input streaming, and rollback behavior
remain unchanged. No dispatch-time rewriting or weakened validation is added.

## Data Flow

```text
validated host fixture snapshot
  -> managed ADB shell transport
  -> run-as package Android sh
  -> open directory/file descriptor in Android sh
  -> external Android utility inspects /proc/<shell-pid>/fd/<number>
  -> ownership and integrity checks
  -> publish fixture receipt or perform exact rollback
```

The newline variable exists only inside the marker-validation script. It is not
returned, logged, or included in state.

## Error Handling and Cleanup

The helper retains its existing fixed failure classifications. Any failed
descriptor, ownership, metadata, or content assertion still aborts the
operation and runs the existing exact cleanup path. The compatibility change
does not introduce fallback paths, direct ADB access, recursive broad cleanup,
or relaxed acceptance criteria.

## Testing

Extend `scripts/test-voice-agent-real-room-step.sh` first with a regression that
inspects the real scripts delivered through the managed transport. The test
must fail while an Android-remote script still contains `/proc/self/fd/` or the
ANSI-C newline expression, while allowing the separate host-lock use.

After the regression fails for the expected compatibility reason, apply the
minimal helper change and verify:

1. the new regression passes;
2. the complete real-room helper test suite passes;
3. an exact staging-only managed-phone probe completes and removes its unique
   fixture directory; and
4. the phone remains automation-idle with no call service or helper residue.

No verification step starts a service, sends a fixture broadcast, contacts
LiveKit, builds or installs an APK, deploys a worker, or consumes a live E2E
attempt. A later live E2E requires separate explicit authorization.

## Scope

Only the RikkaHub real-room helper, its shell-transport regression tests, and
this design artifact are in scope. Agora2 workspace files, Android application
sources, installed packages, device configuration, and deployed services are
out of scope.
