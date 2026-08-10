# Managed ADB Shell Script Transport Design

## Goal

Make the existing real-room voice helper preserve multiline `run-as sh -c`
scripts and their positional arguments when it uses the managed physical-phone
lane. This removes the transport failure that stops `start` at the trace-pointer
readback before fixture staging, automation preparation, or LiveKit startup.

## Root Cause

`voice-agent-real-room-lib.sh` forwards each ADB argument separately to
`mdev android adb`. The Windows-hosted remote ADB shell does not preserve the
multiline script and positional-argument boundaries used by calls such as:

```text
shell run-as PACKAGE --user USER sh -c SCRIPT sh ARGUMENT
```

The first affected operation in `start` is `read_trace_pointer`. Its exact
read-only invocation exits with status 127 and returns none of the required
`absent`, `present`, or `invalid` tokens. The same operation succeeds when the
complete `run-as ... sh -c ...` invocation is POSIX-quoted and sent as one
`adb shell` command argument.

The existing test double accepts a perfectly preserved argument array, so it
models the intended command rather than the real managed transport boundary.

## Design

Add one private `run_as_script` function to
`scripts/voice-agent-real-room-lib.sh`. The function owns three behaviors:

1. Accept a shell script followed by its positional arguments.
2. POSIX-quote the package, Android user, script, fixed `sh` argument zero, and
   every positional argument without evaluating their contents locally.
3. Pass the resulting complete `run-as ... sh -c ...` command as one argument
   after `adb shell` through the existing managed `adb_read` boundary.

Route every multiline app-private `run-as sh -c` operation in the library
through this function. The scripts, input streams, output contracts, ownership
checks, cleanup rules, and error messages remain unchanged.

The quoting helper rejects NUL bytes, which cannot be represented in a shell
argument. Existing validation remains responsible for the semantic constraints
on packages, user ids, paths, hashes, and ownership receipts.

## Data Flow

```text
helper operation
  -> run_as_script(script, args...)
  -> POSIX-quoted single command string
  -> adb_read shell COMMAND
  -> mdev logical phone lane
  -> Android shell
  -> run-as debug package sh -c SCRIPT sh args...
```

Standard input remains connected end to end. This is required by fixture
staging, which streams the already validated PCM snapshot to the script while
the script itself remains part of the command argument.

## Error Handling

- Quoting failure stops before invoking `mdev`.
- A managed transport or Android-shell failure retains the caller's existing
  fixed error classification.
- Script output continues to be parsed by the existing strict contracts.
- No fallback to direct ADB is permitted.
- No command-specific compatibility normalization is added.

## Testing

Extend `scripts/test-voice-agent-real-room-step.sh` with a transport regression
mode that rejects multiline `sh -c` passed as split ADB arguments. The existing
successful `start` scenario must fail under that mode before production changes
and pass after the central transport seam is used.

The fake managed transport must also assert that the single command preserves:

- the script marker;
- argument zero and all positional arguments;
- spaces and shell metacharacters as data rather than syntax; and
- stdin for the fixture-staging path.

Verification consists of the focused regression, the complete real-room helper
test suite, and the exact read-only physical trace-pointer probe through the
logical `phone` lane. This change does not authorize a fixture-backed call.

## Scope

Only the RikkaHub helper and its tests change. `mdev`, Android application code,
the installed APK, the deployed LiveKit worker, broadcasts, and production
voice behavior remain unchanged. There is no build, install, deploy, push, or
physical audio attempt in this task.
