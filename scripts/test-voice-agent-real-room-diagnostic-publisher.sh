#!/usr/bin/env bash
set -euo pipefail

umask 077
set +x

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUBLISHER="$ROOT_DIR/scripts/voice-agent-real-room-diagnostic-publisher.py"
TMP_DIR="$(mktemp -d)"
chmod 700 "$TMP_DIR"
TEST_COUNT=0
RUN_STATUS=0

cleanup() {
  rm -rf -- "$TMP_DIR"
}
trap cleanup EXIT HUP INT TERM

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

pass() {
  TEST_COUNT=$((TEST_COUNT + 1))
}

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

assert_silent() {
  [[ ! -s "$TMP_DIR/stdout" ]] || fail "publisher test: stdout was not empty"
  [[ ! -s "$TMP_DIR/stderr" ]] || fail "publisher test: stderr was not empty"
}

assert_no_named_temporary() {
  local parent="$1"
  if compgen -G "$parent/.voice-step-diagnostic.*" >/dev/null; then
    fail "publisher test: named diagnostic temporary was created"
  fi
}

[[ -x "$PUBLISHER" ]] || fail "publisher test: private publisher is missing"

parent="$TMP_DIR/success-parent"
destination="$parent/record"
mkdir "$parent"
chmod 700 "$parent"
run_publisher "$destination" "$(parent_identity "$parent")" \
  start fixture-arm adb-command-failed 73 failed
[[ "$RUN_STATUS" -eq 0 ]] || fail "publisher test: normal publication failed"
assert_silent
assert_failure_record "$destination" fixture-arm adb-command-failed 73 failed
assert_no_named_temporary "$parent"
pass

parent="$TMP_DIR/existing-parent"
destination="$parent/record"
mkdir "$parent"
chmod 700 "$parent"
printf 'raced' > "$destination"
run_publisher "$destination" "$(parent_identity "$parent")" \
  start fixture-arm adb-command-failed 73 failed
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher test: existing destination was accepted"
assert_silent
[[ "$(<"$destination")" == raced ]] || fail "publisher test: existing destination changed"
assert_no_named_temporary "$parent"
pass

parent="$TMP_DIR/short-write-parent"
destination="$parent/record"
site="$TMP_DIR/short-write-site"
marker="$TMP_DIR/short-write-marker"
mkdir "$parent" "$site"
chmod 700 "$parent" "$site"
cat > "$site/sitecustomize.py" <<'PY'
import os

marker = os.environ["PUBLISHER_SHORT_WRITE_MARKER"]
real_write = os.write
shortened = False


def short_write(descriptor, payload):
    global shortened
    if not shortened and len(payload) > 1:
        shortened = True
        with open(marker, "w", encoding="ascii") as handle:
            handle.write("short-write-injected\n")
        return real_write(descriptor, payload[:7])
    return real_write(descriptor, payload)


os.write = short_write
PY
PUBLISHER_SHORT_WRITE_MARKER="$marker" PYTHONPATH="$site" \
  run_publisher "$destination" "$(parent_identity "$parent")" \
  start fixture-arm adb-command-failed 73 failed
[[ "$RUN_STATUS" -eq 0 && -f "$marker" &&
   "$(<"$marker")" == short-write-injected ]] ||
  fail "publisher test: positive short write was not handled"
assert_silent
assert_failure_record "$destination" fixture-arm adb-command-failed 73 failed
assert_no_named_temporary "$parent"
pass

parent="$TMP_DIR/tmpfile-parent"
destination="$parent/record"
site="$TMP_DIR/tmpfile-site"
mkdir "$parent" "$site"
chmod 700 "$parent" "$site"
cat > "$site/sitecustomize.py" <<'PY'
import os

real_open = os.open


def blocked_open(path, flags, *args, **kwargs):
    if flags & os.O_TMPFILE:
        raise OSError
    return real_open(path, flags, *args, **kwargs)


os.open = blocked_open
PY
PYTHONPATH="$site" run_publisher "$destination" "$(parent_identity "$parent")" \
  start fixture-arm adb-command-failed 73 failed
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher test: O_TMPFILE failure was accepted"
assert_silent
[[ ! -e "$destination" ]] || fail "publisher test: O_TMPFILE failure published a destination"
assert_no_named_temporary "$parent"
pass

parent="$TMP_DIR/link-race-parent"
destination="$parent/record"
site="$TMP_DIR/link-race-site"
mkdir "$parent" "$site"
chmod 700 "$parent" "$site"
cat > "$site/sitecustomize.py" <<'PY'
import os

real_link = os.link
real_open = os.open


def raced_link(source, target, *args, **kwargs):
    if os.fsdecode(source).startswith("/proc/self/fd/"):
        descriptor = real_open(
            target,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC,
            0o600,
            dir_fd=kwargs["dst_dir_fd"],
        )
        os.write(descriptor, b"raced")
        os.close(descriptor)
    return real_link(source, target, *args, **kwargs)


os.link = raced_link
PY
PYTHONPATH="$site" run_publisher "$destination" "$(parent_identity "$parent")" \
  start fixture-arm adb-command-failed 73 failed
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher test: link race was accepted"
assert_silent
[[ "$(<"$destination")" == raced ]] || fail "publisher test: link race destination changed"
assert_no_named_temporary "$parent"
pass

parent="$TMP_DIR/replaced-parent"
pinned_parent="$TMP_DIR/pinned-parent"
replacement_parent="$TMP_DIR/replacement-parent"
destination="$parent/record"
site="$TMP_DIR/replaced-parent-site"
mkdir "$parent" "$replacement_parent" "$site"
chmod 700 "$parent" "$replacement_parent" "$site"
printf 'pinned-sentinel' > "$parent/sentinel"
printf 'replacement-sentinel' > "$replacement_parent/sentinel"
cat > "$site/sitecustomize.py" <<'PY'
import os

parent = os.environ["PUBLISHER_PARENT"]
pinned_parent = os.environ["PUBLISHER_PINNED_PARENT"]
replacement_parent = os.environ["PUBLISHER_REPLACEMENT_PARENT"]
real_open = os.open
replaced = False


def replaced_parent_open(path, flags, *args, **kwargs):
    global replaced
    if (
        not replaced
        and os.fsdecode(path) == parent
        and flags & os.O_DIRECTORY
    ):
        replaced = True
        os.rename(parent, pinned_parent)
        os.symlink(replacement_parent, parent, target_is_directory=True)
    return real_open(path, flags, *args, **kwargs)


os.open = replaced_parent_open
PY
PUBLISHER_PARENT="$parent" PUBLISHER_PINNED_PARENT="$pinned_parent" \
  PUBLISHER_REPLACEMENT_PARENT="$replacement_parent" PYTHONPATH="$site" \
  run_publisher "$destination" "$(parent_identity "$parent")" \
  start fixture-arm adb-command-failed 73 failed
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher test: replaced parent was accepted"
assert_silent
[[ ! -e "$pinned_parent/record" ]] || fail "publisher test: pinned parent received destination"
[[ ! -e "$replacement_parent/record" ]] || fail "publisher test: replacement parent received destination"
[[ "$(<"$pinned_parent/sentinel")" == pinned-sentinel ]] || fail "publisher test: pinned sentinel changed"
[[ "$(<"$replacement_parent/sentinel")" == replacement-sentinel ]] || fail "publisher test: replacement sentinel changed"
assert_no_named_temporary "$parent"
assert_no_named_temporary "$pinned_parent"
assert_no_named_temporary "$replacement_parent"
pass

printf 'PASS: voice-agent-real-room-diagnostic-publisher (%s assertions)\n' "$TEST_COUNT"
