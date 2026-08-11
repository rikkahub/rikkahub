# Android Shell Fixture Staging Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make RikkaHub's real-room helper preserve its descriptor-bound fixture safety checks under Android `sh`.

**Architecture:** Keep the existing managed `run_as_script` transport and every ownership, inode, mode, hard-link, streaming, and rollback check. Change only Android-remote shell syntax: external utilities refer to the shell's open descriptors through `/proc/$$/fd/*`, and marker parsing constructs its newline with POSIX `printf` plus parameter expansion. The host Bash lock remains on `/proc/self/fd/*`.

**Tech Stack:** Bash, embedded POSIX Android `sh` scripts, Python 3 fake managed-ADB transport, `mdev android adb`, the existing real-room helper test suite.

## Global Constraints

- Work only in `/home/muly/code/rikkahub` on branch `master`.
- Modify production and test behavior only in `scripts/voice-agent-real-room-lib.sh` and `scripts/test-voice-agent-real-room-step.sh`.
- Do not change the host-side `/proc/self/fd/*` lock in `scripts/voice-agent-real-room-lib.sh`.
- Preserve every existing path constraint, inode comparison, mode check, hard-link count, ownership receipt, exclusive create, input stream, output contract, rollback rule, and fixed failure classification.
- Use only the assigned logical `phone` lane through `mdev` for physical-device verification; never use direct ADB.
- Do not expose device identifiers, host addresses, owner values, credentials, trace identifiers, private prompts, private events, or raw logs.
- Do not change Android application source, build or install an APK, deploy a worker, push commits, start a service, send a fixture broadcast, contact LiveKit, or consume a live E2E attempt.
- Keep the phone assignment after verification unless the user explicitly asks to release it.

---

## File Structure

- `scripts/voice-agent-real-room-lib.sh`: owns the host lock and the quoted Android-remote scripts for owned-directory creation, fixture staging, cleanup brokering, and capture bundling.
- `scripts/test-voice-agent-real-room-step.sh`: owns the fake managed-ADB transport and assertions over the exact Android scripts delivered through that transport.
- `docs/superpowers/specs/2026-08-11-android-shell-fixture-staging-compatibility-design.md`: approved source of truth; do not modify during implementation.
- `docs/superpowers/plans/2026-08-11-android-shell-fixture-staging-compatibility.md`: this execution checklist; do not add runtime findings or private device data.

### Task 1: Resolve Android shell descriptor paths

**Files:**
- Modify: `scripts/test-voice-agent-real-room-step.sh:817-824,2641-2667,2892-2920,4338-4360,4737-4753`
- Modify: `scripts/voice-agent-real-room-lib.sh:892-968,1000-1039,1267-1319,1513-1556`
- Test: `scripts/test-voice-agent-real-room-step.sh`

**Interfaces:**
- Consumes: the existing `run_as_script shell SCRIPT [ARG...]` and `run_as_script exec-out SCRIPT [ARG...]` transport records.
- Produces: the same four remote script contracts, with shell-owned descriptors exposed to Android utilities as `/proc/$$/fd/3`, `/proc/$$/fd/4`, and `/proc/$$/fd/5`. No function signature or receipt changes.

- [ ] **Step 1: Change the transport assertions to require shell-PID descriptor paths**

In the fake create-directory rollback check, require the post-fix descriptor spelling:

```python
cleanup_markers = (
    "exec 5< .",
    "exec 4< .",
    'stat -Lc %d:%i /proc/$$/fd/4',
    'stat -c %d:%i "$name"',
    'rmdir -- "$name"',
    'stat -Lc %h /proc/$$/fd/4',
)
```

Replace the start transport assertion block with the following form so it retains the existing framing checks and records the decoded remote scripts:

```python
expected_shell_markers = {
    "voice-step-protected-root": 1,
    "voice-step-trace-probe": 2,
    "voice-step-create-owned-directory": 1,
    "voice-step-stage-owned-fixture": 1,
}
scripts_by_marker = {}
for marker, expected_count in expected_shell_markers.items():
    matches = [
        command for command in commands
        if any(marker.encode() in value for value in command)
    ]
    assert len(matches) == expected_count
    scripts_by_marker[marker] = []
    for match in matches:
        tail = match[7:]
        assert len(tail) == 2 and tail[0] == b"shell"
        decoded = shlex.split(tail[1].decode(), posix=True)
        assert decoded[0:5] == [
            "run-as", "me.rerere.rikkahub.debug", "--user", "0", "sh",
        ]
        assert decoded[5] == "-c" and marker in decoded[6] and decoded[7] == "sh"
        scripts_by_marker[marker].append(decoded[6].encode())

create_script = scripts_by_marker["voice-step-create-owned-directory"][0]
assert b"/proc/self/fd/" not in create_script
for descriptor in (3, 4, 5):
    assert f"/proc/$$/fd/{descriptor}".encode() in create_script

stage_script = scripts_by_marker["voice-step-stage-owned-fixture"][0]
assert b"/proc/self/fd/" not in stage_script
assert b"/proc/$$/fd/3" in stage_script
```

In the inject-path assertion, replace the old positive check with:

```python
assert b"voice-step-descriptor-owned-stage" in stream_script
assert b"/proc/self/fd/" not in stream_script
assert b"/proc/$$/fd/3" in stream_script
assert b"mktemp" not in stream_script and b'cat > "$temporary"' not in stream_script
```

In the capture-bundle assertion, immediately after validating `decoded[8:]`, add:

```python
bundle_script = decoded[6].encode()
assert b"/proc/self/fd/" not in bundle_script
for descriptor in (3, 4, 5):
    assert f"/proc/$$/fd/{descriptor}".encode() in bundle_script
```

In the end cleanup-broker assertion, replace its descriptor checks with:

```python
script = next(value for value in broker if b"voice-step-cleanup-broker" in value)
assert b"/proc/self/fd/" not in script
assert b"/proc/$$/fd/4" in script
assert b"stat -Lc %h /proc/$$/fd/4" in script
assert b'rm -rf -- "$directory"' not in script
```

- [ ] **Step 2: Run the focused regression and verify RED**

Run:

```bash
scripts/test-voice-agent-real-room-step.sh start inject capture end
```

Expected: FAIL with `start-script-transport test: app-private scripts were not one managed shell argument` because `voice-step-create-owned-directory` and `voice-step-stage-owned-fixture` still contain `/proc/self/fd/`. A Python syntax error, fixture setup error, or unrelated helper failure is not the intended RED result.

- [ ] **Step 3: Change only Android-remote descriptor references**

Within the four quoted scripts identified by these markers, replace every exact `/proc/self/fd/` prefix with `/proc/$$/fd/`:

```text
voice-step-create-owned-directory  21 replacements, descriptors 3, 4, and 5
voice-step-stage-owned-fixture      1 replacement,  descriptor 3
voice-step-cleanup-broker          12 replacements, descriptors 3, 4, and 5
voice-step-capture-bundle          12 replacements, descriptors 3, 4, and 5
```

The resulting source must retain these representative operations unchanged apart from the descriptor prefix:

```bash
[ "$(stat -Lc %d:%i /proc/$$/fd/5)" = "$parent_inode" ] || exit 1
marker_inode=$(stat -Lc %d:%i /proc/$$/fd/3) || exit 1
descriptor=/proc/$$/fd/3
[ "$(identity /proc/$$/fd/4)" = "$expected_directory" ] || exit 1
cat /proc/$$/fd/3 /proc/$$/fd/4 /proc/$$/fd/5 || exit 1
```

Do not perform a repository-wide replacement. The host lock must remain:

```bash
lock_path="/proc/self/fd/$HOST_LOCK_ROOT_FD/$lock_name"
```

- [ ] **Step 4: Audit descriptor scope before running tests**

Run:

```bash
rg -n '/proc/self/fd|/proc/\$\$/fd' scripts/voice-agent-real-room-lib.sh
```

Expected: the only `/proc/self/fd` result is the host lock near line 246. All descriptor references inside `voice-step-create-owned-directory`, `voice-step-stage-owned-fixture`, `voice-step-cleanup-broker`, and `voice-step-capture-bundle` use `/proc/$$/fd`.

- [ ] **Step 5: Run the focused regression and verify GREEN**

Run:

```bash
scripts/test-voice-agent-real-room-step.sh start inject capture end
```

Expected: PASS with no Python traceback, shell warning, private output, or generated tracked file.

- [ ] **Step 6: Commit the descriptor compatibility change**

```bash
git add scripts/voice-agent-real-room-lib.sh scripts/test-voice-agent-real-room-step.sh
git commit -m "fix: use Android shell descriptor paths"
```

### Task 2: Parse the ownership marker with POSIX shell syntax

**Files:**
- Modify: `scripts/test-voice-agent-real-room-step.sh:2641-2675`
- Modify: `scripts/voice-agent-real-room-lib.sh:1008-1015`
- Test: `scripts/test-voice-agent-real-room-step.sh`

**Interfaces:**
- Consumes: `marker_payload` containing exactly `sha256:<64-hex>\n<32-hex>` after command substitution removes the marker's trailing newline.
- Produces: local remote-script variable `newline` containing one newline and the same ownership-pattern acceptance rule. No output, receipt, or host variable changes.

- [ ] **Step 1: Add the failing Android-shell syntax assertion**

After `stage_script` is captured in the start transport test, append:

```python
assert b"$'\\n'" not in stage_script
assert b'newline=$(printf "\\nx") || exit 1' in stage_script
assert b'newline=${newline%x}' in stage_script
assert b'"$owner$newline"[0-9a-f]' in stage_script
```

- [ ] **Step 2: Run the start regression and verify RED**

Run:

```bash
scripts/test-voice-agent-real-room-step.sh start
```

Expected: FAIL with `start-script-transport test: app-private scripts were not one managed shell argument` because the stage script still contains `$'\n'` and does not contain the POSIX `newline` construction. The descriptor assertions from Task 1 must already pass.

- [ ] **Step 3: Implement the minimal POSIX marker match**

Replace:

```bash
marker_payload=$(cat "$marker") || exit 1
case "$marker_payload" in "$owner"$'\n'[0-9a-f][0-9a-f][0-9a-f][0-9a-f]*) ;; *) exit 1 ;; esac
```

with:

```bash
marker_payload=$(cat "$marker") || exit 1
newline=$(printf "\nx") || exit 1
newline=${newline%x}
case "$marker_payload" in "$owner$newline"[0-9a-f][0-9a-f][0-9a-f][0-9a-f]*) ;; *) exit 1 ;; esac
```

The `x` prevents command substitution from stripping the intended newline; `${newline%x}` removes only that sentinel. Keep the nonce pattern and all surrounding marker metadata checks unchanged.

- [ ] **Step 4: Run the start regression and verify GREEN**

Run:

```bash
scripts/test-voice-agent-real-room-step.sh start
```

Expected: PASS with no Python traceback, shell warning, private output, or generated tracked file.

- [ ] **Step 5: Commit the marker compatibility change**

```bash
git add scripts/voice-agent-real-room-lib.sh scripts/test-voice-agent-real-room-step.sh
git commit -m "fix: parse fixture marker with POSIX shell"
```

### Task 3: Verify the complete helper and staging-only phone path

**Files:**
- Verify: `scripts/voice-agent-real-room-lib.sh`
- Verify: `scripts/test-voice-agent-real-room-step.sh`
- Verify: `docs/superpowers/specs/2026-08-11-android-shell-fixture-staging-compatibility-design.md`

**Interfaces:**
- Consumes: the two committed compatibility changes, the assigned `phone` lane, `$HERDR_PANE_ID` as the already-established mdev owner, and installed package `me.rerere.rikkahub.debug` for Android user `0`.
- Produces: fixed categorical evidence only: helper suite pass, `stage_probe=complete`, `stage_probe_cleanup=complete`, `call_service=inactive`, `automation=idle`, and `fixture_residue=absent`. It produces no persistent fixture, state file, APK, deployment, or live call.

- [ ] **Step 1: Run the complete helper suite**

Run:

```bash
scripts/test-voice-agent-real-room-step.sh
```

Expected:

```text
PASS: voice-agent-real-room-step (264 assertions)
```

If the test creates `scripts/__pycache__`, inspect that exact directory, confirm it contains only generated Python cache files, then remove only that directory:

```bash
find scripts/__pycache__ -maxdepth 1 -type f -name '*.pyc' -print
rm -r -- scripts/__pycache__
```

- [ ] **Step 2: Confirm the managed phone lane without exposing inventory**

Run `mdev android status` and an owner-bound round trip through the existing assignment. Keep raw status output private and emit only these fixed categories:

```bash
(umask 077; mdev android status >/tmp/rikkahub-mdev-status.txt)
round_trip=$(mdev android adb --owner "$HERDR_PANE_ID" -- get-state 2>/dev/null || :)
if [ "$round_trip" = device ]; then
  printf '%s\n' 'phone_lane=healthy'
else
  printf '%s\n' 'phone_lane=unhealthy'
  exit 1
fi
```

Retain `/tmp/rikkahub-mdev-status.txt` only until the exact cleanup in Step 5.
Privately confirm the current caller remains assigned to `phone` and the status
is healthy; do not print the file.

- [ ] **Step 3: Run the exact staging-only compatibility probe**

Run the following from `/home/muly/code/rikkahub`. It creates one unique app-private directory, exercises the helper's corrected descriptor and newline semantics, and removes only that owned directory from its exit trap:

```bash
set -uo pipefail

quote_arg() {
  local arg=$1
  printf "'%s'" "$(printf '%s' "$arg" | sed "s/'/'\\\\''/g")"
}

seed="android-shell-stage-$(date +%s)-$$"
hash=$(printf '%s' "$seed" | sha256sum | cut -d ' ' -f 1)
owner_digest=$(printf '%s' "owner-$seed" | sha256sum | cut -d ' ' -f 1)
directory="files/voice-real-room/$hash"
destination="$directory/request-$hash.pcm"
owner="sha256:$owner_digest"
expected_size=8
probe_record=/tmp/rikkahub-stage-probe-directory
(umask 077; printf '%s' "$directory" > "$probe_record")
remote_script='
set -u
checkpoint=setup
cleanup_result=not-run
created=0
package_root=$(pwd) || exit 60
directory=$1
destination=$2
owner=$3
expected_size=$4
parent=${directory%/*}
name=${directory##*/}
nonce=0123456789abcdef0123456789abcdef
newline=$(printf "\nx") || exit 61
newline=${newline%x}

cleanup() {
  cleanup_result=fail
  cd "$package_root" || return
  if [ "$created" = 1 ]; then
    marker="$directory/.voice-step-owner"
    if [ -e "$destination" ] || [ -L "$destination" ]; then
      [ -f "$destination" ] && [ ! -L "$destination" ] || return
      rm -f -- "$destination" || return
    fi
    if [ -e "$marker" ] || [ -L "$marker" ]; then
      [ -f "$marker" ] && [ ! -L "$marker" ] || return
      marker_payload=$(cat "$marker") || return
      case "$marker_payload" in
        "$owner$newline"[0-9a-f][0-9a-f][0-9a-f][0-9a-f]*) ;;
        *) return ;;
      esac
      rm -f -- "$marker" || return
    fi
    rmdir -- "$directory" || return
  fi
  cleanup_result=complete
}

report() {
  saved_status=$?
  trap - EXIT HUP INT TERM
  cleanup
  printf "checkpoint=%s\n" "$checkpoint"
  printf "cleanup_result=%s\n" "$cleanup_result"
  exit "$saved_status"
}
trap report EXIT HUP INT TERM

checkpoint=parent
[ "$parent" = files/voice-real-room ] || exit 62
[ ! -e "$directory" ] && [ ! -L "$directory" ] || exit 63
[ ! -L "$parent" ] || exit 64
if [ ! -e "$parent" ]; then mkdir -m 700 -- "$parent" || exit 65; fi
[ -d "$parent" ] && [ ! -L "$parent" ] || exit 66
[ "$(readlink -f "$parent")" = "$(readlink -f files)/voice-real-room" ] || exit 67

checkpoint=create-directory
cd -- "$parent" || exit 68
mkdir -m 700 -- "$name" || exit 69
created=1
cd -- "$name" || exit 70
exec 4< . || exit 71
[ "$(stat -Lc %d:%i /proc/$$/fd/4)" = "$(stat -c %d:%i .)" ] || exit 72
[ "$(stat -c %a .)" = 700 ] || exit 73

checkpoint=create-marker
set -C
umask 077
exec 3> .voice-step-owner || exit 74
set +C
printf "%s\n%s\n" "$owner" "$nonce" >&3 || exit 75
[ "$(stat -Lc %a /proc/$$/fd/3)" = 600 ] || exit 76
exec 3>&-
exec 4<&-

checkpoint=stage-validate
cd "$package_root" || exit 77
marker="$directory/.voice-step-owner"
[ -d "$directory" ] && [ ! -L "$directory" ] &&
  [ "$(stat -c %a "$directory")" = 700 ] || exit 78
[ -f "$marker" ] && [ ! -L "$marker" ] &&
  [ "$(stat -c %a "$marker")" = 600 ] || exit 79
marker_payload=$(cat "$marker") || exit 80
case "$marker_payload" in
  "$owner$newline"[0-9a-f][0-9a-f][0-9a-f][0-9a-f]*) ;;
  *) exit 81 ;;
esac
case "$destination" in "$directory"/*.pcm) ;; *) exit 82 ;; esac
[ ! -e "$destination" ] && [ ! -L "$destination" ] || exit 83

checkpoint=stage-open
set -C
umask 077
exec 3> "$destination" || exit 84
set +C
descriptor=/proc/$$/fd/3
descriptor_inode=$(stat -Lc %d:%i "$descriptor") || exit 85

checkpoint=stage-copy
cat >&3 || exit 86
[ -f "$descriptor" ] && [ "$(stat -Lc %a "$descriptor")" = 600 ] &&
  [ "$(stat -Lc %h "$descriptor")" = 1 ] || exit 87
[ -f "$destination" ] && [ ! -L "$destination" ] &&
  [ "$(stat -c %d:%i "$destination")" = "$descriptor_inode" ] || exit 88
[ "$(stat -Lc %s "$descriptor")" = "$expected_size" ] || exit 89
exec 3>&-

checkpoint=complete
exit 0
'

remote_command="run-as me.rerere.rikkahub.debug --user 0 sh -c $(quote_arg "$remote_script") sh"
remote_command+=" $(quote_arg "$directory") $(quote_arg "$destination")"
remote_command+=" $(quote_arg "$owner") $(quote_arg "$expected_size")"
raw_output=$(printf 'fixture\n' |
  mdev android adb --owner "$HERDR_PANE_ID" -- shell "$remote_command" 2>&1)
status=$?
checkpoint=$(printf '%s\n' "$raw_output" | sed -n 's/^checkpoint=//p' | tail -n 1)
cleanup=$(printf '%s\n' "$raw_output" | sed -n 's/^cleanup_result=//p' | tail -n 1)

if [ "$status" -eq 0 ] && [ "$checkpoint" = complete ]; then
  printf '%s\n' 'stage_probe=complete'
else
  printf '%s\n' 'stage_probe=failed'
  exit 1
fi
if [ "$cleanup" = complete ]; then
  printf '%s\n' 'stage_probe_cleanup=complete'
else
  printf '%s\n' 'stage_probe_cleanup=failed'
  exit 1
fi
```

Expected:

```text
stage_probe=complete
stage_probe_cleanup=complete
```

Do not print `raw_output`. If either category fails, stop and return to systematic debugging; do not start a call and do not retry this probe after layering another speculative fix.

- [ ] **Step 4: Verify idle service, automation, and exact fixture cleanup**

Read and validate the mode-0600 path record created by Step 3. Capture all raw
command output and emit only fixed categories:

```bash
directory=$(< /tmp/rikkahub-stage-probe-directory)
[[ "$directory" =~ ^files/voice-real-room/[0-9a-f]{64}$ ]] || exit 1
quote_arg() {
  local arg=$1
  printf "'%s'" "$(printf '%s' "$arg" | sed "s/'/'\\\\''/g")"
}

service_dump=$(mdev android adb --owner "$HERDR_PANE_ID" -- shell \
  "dumpsys activity services me.rerere.rikkahub.debug" 2>/dev/null || :)
case "$service_dump" in
  *me.rerere.rikkahub.voiceagent.VoiceAgentCallService*)
    printf '%s\n' 'call_service=active'
    exit 1
    ;;
  *) printf '%s\n' 'call_service=inactive' ;;
esac

automation_raw=$(mdev android adb --owner "$HERDR_PANE_ID" -- shell \
  "am broadcast --user 0 -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.debug.VoiceAutomationControlReceiver -a me.rerere.rikkahub.voiceagent.automation.STATUS" \
  2>/dev/null || :)
case "$automation_raw" in
  *run_state=idle*) printf '%s\n' 'automation=idle' ;;
  *)
    printf '%s\n' 'automation=not-idle'
    exit 1
    ;;
esac

residue_command="run-as me.rerere.rikkahub.debug --user 0 sh -c"
residue_script='
if [ ! -e "$1" ] && [ ! -L "$1" ]; then printf absent; else printf present; fi
'
residue_command+=" $(quote_arg "$residue_script") sh $(quote_arg "$directory")"
residue=$(mdev android adb --owner "$HERDR_PANE_ID" -- shell "$residue_command" \
  2>/dev/null || :)
if [ "$residue" = absent ]; then
  printf '%s\n' 'fixture_residue=absent'
else
  printf '%s\n' 'fixture_residue=present'
  exit 1
fi
```

Expected:

```text
call_service=inactive
automation=idle
fixture_residue=absent
```

- [ ] **Step 5: Remove exact local test residue and verify repository state**

Remove the temporary status capture, then inspect repository state:

```bash
rm -f -- /tmp/rikkahub-mdev-status.txt /tmp/rikkahub-stage-probe-directory
git status --short
git log -3 --oneline
```

Expected: `git status --short` is empty. The newest two implementation commits are `fix: parse fixture marker with POSIX shell` and `fix: use Android shell descriptor paths`. Do not amend the committed design or plan, and do not push.

- [ ] **Step 6: Apply verification-before-completion**

Invoke `verification-before-completion` and use only the fresh outputs from Steps 1-5. Report that the host-helper compatibility fix and staging-only verification pass; explicitly state that no live E2E attempt was run or authorized and that the bidirectional LiveKit outcome remains unverified.
