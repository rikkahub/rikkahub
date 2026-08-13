#!/usr/bin/env bash
set -euo pipefail

umask 077
set +x

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUBLISHER="$ROOT_DIR/scripts/voice-agent-real-room-binding-publisher.py"
TMP_DIR="$(mktemp -d)"
chmod 700 "$TMP_DIR"
UUID="123e4567-e89b-12d3-a456-426614174000"
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
  stat -Lc '%d:%i' -- "$1"
}

run_publisher() {
  local destination="$1" identity="$2"
  shift 2
  : >"$TMP_DIR/stdout"
  : >"$TMP_DIR/stderr"
  set +e
  { python3 "$PUBLISHER" "$destination" "$identity" "$@" \
      >"$TMP_DIR/stdout" 2>"$TMP_DIR/stderr"; } \
    2>"$TMP_DIR/launcher-stderr"
  RUN_STATUS=$?
  set -e
}

run_publisher_arguments() {
  : >"$TMP_DIR/stdout"
  : >"$TMP_DIR/stderr"
  set +e
  { python3 "$PUBLISHER" "$@" >"$TMP_DIR/stdout" 2>"$TMP_DIR/stderr"; } \
    2>"$TMP_DIR/launcher-stderr"
  RUN_STATUS=$?
  set -e
}

write_sitecustomize() {
  local site="$1"
  cat >"$site/sitecustomize.py" <<'PY'
import os
import signal

action = os.environ.get("PUBLISHER_INJECTION")
marker = os.environ.get("PUBLISHER_MARKER")
real_link = os.link
real_open = os.open
real_write = os.write
real_fstat = os.fstat
real_signal = signal.signal
real_close = os.close
shortened = False
replaced = False


def record(value):
    if marker is not None:
        with open(marker, "w", encoding="ascii") as handle:
            handle.write(value)


def injected_open(path, flags, *args, **kwargs):
    if (
        action == "tmpfile"
        and os.fsdecode(path) == "."
        and (flags & os.O_TMPFILE) == os.O_TMPFILE
        and "dir_fd" in kwargs
    ):
        record("tmpfile")
        raise OSError("injected O_TMPFILE failure")
    return real_open(path, flags, *args, **kwargs)


def injected_write(descriptor, payload):
    global shortened
    if action == "short-write" and not shortened and len(payload) > 1:
        shortened = True
        return real_write(descriptor, payload[:7])
    if action == "write-error":
        if not shortened:
            shortened = True
            return real_write(descriptor, payload[:7])
        raise OSError("injected write failure")
    if action == "stdout-error" and marker is not None and os.path.exists(marker) and descriptor == 1:
        raise OSError("injected stdout failure")
    return real_write(descriptor, payload)


def injected_fstat(descriptor):
    global replaced
    metadata = real_fstat(descriptor)
    if action == "replace-parent" and not replaced and os.path.exists(os.environ["PUBLISHER_PARENT"]):
        parent = os.environ["PUBLISHER_PARENT"]
        if (metadata.st_dev, metadata.st_ino) == (os.stat(parent).st_dev, os.stat(parent).st_ino):
            replaced = True
            os.rename(parent, parent + ".pinned")
            os.symlink(os.environ["PUBLISHER_REPLACEMENT_PARENT"], parent, target_is_directory=True)
    return metadata


def injected_link(source, target, *args, **kwargs):
    if not os.fsdecode(source).startswith("/proc/self/fd/"):
        return real_link(source, target, *args, **kwargs)
    if action == "link-race":
        descriptor = real_open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC, 0o600, dir_fd=kwargs["dst_dir_fd"])
        real_write(descriptor, b"raced")
        real_close(descriptor)
        try:
            real_link(source, target, *args, **kwargs)
        except FileExistsError:
            record("link-eexist")
            raise
        raise AssertionError("raced link unexpectedly succeeded")
    result = real_link(source, target, *args, **kwargs)
    if action in {"post-signal", "stdout-error"}:
        record("linked")
    if action == "post-signal":
        os.kill(os.getpid(), getattr(signal, "SIG" + os.environ["PUBLISHER_SIGNAL"]))
    return result


def injected_signal(signum, handler):
    if action == "pre-signal" and signum == getattr(signal, "SIG" + os.environ["PUBLISHER_SIGNAL"]):
        os.kill(os.getpid(), signum)
    return real_signal(signum, handler)


def cleanup_hook(path, *args, **kwargs):
    if marker is not None and os.path.exists(marker):
        record("cleanup")
    raise AssertionError("cleanup after link")


os.open = injected_open
os.write = injected_write
os.fstat = injected_fstat
os.link = injected_link
signal.signal = injected_signal
os.unlink = cleanup_hook
os.remove = cleanup_hook
os.close = cleanup_hook
PY
}

assert_silent() {
  [[ ! -s "$TMP_DIR/stdout" ]] || fail "publisher: stdout was not empty"
  [[ ! -s "$TMP_DIR/stderr" ]] || fail "publisher: stderr was not empty"
}

assert_record() {
  local path="$1"
  python3 - "$path" <<'PY'
import os
import stat
import sys

path = sys.argv[1]
expected = b"123e4567-e89b-12d3-a456-426614174000\n"
metadata = os.lstat(path)
assert open(path, "rb").read() == expected
assert stat.S_ISREG(metadata.st_mode)
assert stat.S_IMODE(metadata.st_mode) == 0o600
assert metadata.st_nlink == 1
PY
}

assert_success_stdout() {
  if ! python3 - "$TMP_DIR/stdout" <<'PY'
import sys

assert open(sys.argv[1], "rb").read() == (
    b"voice-step.status=ok\n"
    b"voice-step.operation=resolve-binding\n"
    b"voice-step.binding=resolved\n"
)
PY
  then
    fail "publisher: fixed success stdout differed"
  fi
  [[ ! -s "$TMP_DIR/stderr" ]] || fail "publisher: success stderr was not empty"
}

assert_no_named_temporary() {
  local parent="$1"
  if compgen -G "$parent/.voice-step-binding.*" >/dev/null; then
    fail "publisher: named temporary was created"
  fi
}

[[ -x "$PUBLISHER" ]] || fail "publisher: private binding publisher is missing"

parent="$TMP_DIR/normal-parent"
destination="$parent/record"
mkdir "$parent"
chmod 700 "$parent"
run_publisher "$destination" "$(parent_identity "$parent")" "$UUID"
[[ "$RUN_STATUS" -eq 0 ]] || fail "publisher: normal publication failed"
assert_success_stdout
assert_record "$destination"
assert_no_named_temporary "$parent"
pass

parent="$TMP_DIR/invalid-parent"
destination="$parent/record"
mkdir "$parent"
chmod 700 "$parent"
run_publisher "$destination" "$(parent_identity "$parent")" "123E4567-e89b-12d3-a456-426614174000"
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher: noncanonical UUID was accepted"
assert_silent
[[ ! -e "$destination" ]] || fail "publisher: invalid UUID published a destination"
run_publisher "$destination" "0:0" "$UUID"
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher: mismatched parent identity was accepted"
assert_silent
[[ ! -e "$destination" ]] || fail "publisher: mismatched identity published a destination"
run_publisher "$destination" "not-an-identity" "$UUID"
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher: invalid parent identity was accepted"
assert_silent
[[ ! -e "$destination" ]] || fail "publisher: invalid identity published a destination"
pass

parent="$TMP_DIR/validation-parent"
destination="$parent/record"
mkdir "$parent"
chmod 700 "$parent"
run_publisher_arguments "$destination" "$(parent_identity "$parent")"
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher: malformed argument count was accepted"
assert_silent
[[ ! -e "$destination" ]] || fail "publisher: malformed argument count published a destination"
run_publisher "$parent/./record" "$(parent_identity "$parent")" "$UUID"
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher: non-normalized destination was accepted"
assert_silent
[[ ! -e "$destination" ]] || fail "publisher: non-normalized destination published a destination"
chmod 755 "$parent"
run_publisher "$destination" "$(parent_identity "$parent")" "$UUID"
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher: non-0700 parent was accepted"
assert_silent
[[ ! -e "$destination" ]] || fail "publisher: non-0700 parent published a destination"
chmod 700 "$parent"
linked_parent="$TMP_DIR/validation-parent-link"
ln -s "$parent" "$linked_parent"
run_publisher "$linked_parent/record" "$(parent_identity "$parent")" "$UUID"
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher: symlink parent was accepted"
assert_silent
[[ ! -e "$destination" ]] || fail "publisher: symlink parent published a destination"
ln -s sentinel "$destination"
run_publisher "$destination" "$(parent_identity "$parent")" "$UUID"
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher: symlink destination was accepted"
assert_silent
[[ "$(readlink "$destination")" == sentinel ]] || fail "publisher: symlink destination changed"
pass

parent="$TMP_DIR/existing-parent"
destination="$parent/record"
mkdir "$parent"
chmod 700 "$parent"
printf 'raced' >"$destination"
run_publisher "$destination" "$(parent_identity "$parent")" "$UUID"
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher: existing destination was accepted"
assert_silent
[[ "$(<"$destination")" == raced ]] || fail "publisher: existing destination changed"
assert_no_named_temporary "$parent"
pass

parent="$TMP_DIR/tmpfile-parent"
destination="$parent/record"
site="$TMP_DIR/tmpfile-site"
mkdir "$parent" "$site"
chmod 700 "$parent" "$site"
write_sitecustomize "$site"
marker="$TMP_DIR/tmpfile-marker"
PUBLISHER_INJECTION=tmpfile PUBLISHER_MARKER="$marker" PYTHONPATH="$site" run_publisher "$destination" "$(parent_identity "$parent")" "$UUID"
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher: O_TMPFILE failure was accepted"
assert_silent
[[ ! -e "$destination" ]] || fail "publisher: O_TMPFILE failure published a destination"
[[ "$(<"$marker")" == tmpfile ]] || fail "publisher: O_TMPFILE injection missed anonymous inode open"
pass

parent="$TMP_DIR/short-write-parent"
destination="$parent/record"
site="$TMP_DIR/short-write-site"
mkdir "$parent" "$site"
chmod 700 "$parent" "$site"
write_sitecustomize "$site"
PUBLISHER_INJECTION=short-write PYTHONPATH="$site" run_publisher "$destination" "$(parent_identity "$parent")" "$UUID"
[[ "$RUN_STATUS" -eq 0 ]] || fail "publisher: positive short write was not handled"
assert_success_stdout
assert_record "$destination"
pass

parent="$TMP_DIR/write-error-parent"
destination="$parent/record"
site="$TMP_DIR/write-error-site"
mkdir "$parent" "$site"
chmod 700 "$parent" "$site"
write_sitecustomize "$site"
PUBLISHER_INJECTION=write-error PYTHONPATH="$site" run_publisher "$destination" "$(parent_identity "$parent")" "$UUID"
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher: write error before link was accepted"
assert_silent
[[ ! -e "$destination" ]] || fail "publisher: write error before link published a destination"
pass

parent="$TMP_DIR/link-race-parent"
destination="$parent/record"
site="$TMP_DIR/link-race-site"
mkdir "$parent" "$site"
chmod 700 "$parent" "$site"
write_sitecustomize "$site"
marker="$TMP_DIR/link-race-marker"
PUBLISHER_INJECTION=link-race PUBLISHER_MARKER="$marker" PYTHONPATH="$site" run_publisher "$destination" "$(parent_identity "$parent")" "$UUID"
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher: link race was accepted"
assert_silent
[[ "$(<"$destination")" == raced ]] || fail "publisher: raced destination changed"
[[ "$(<"$marker")" == link-eexist ]] || fail "publisher: link race did not reach real EEXIST link"
pass

parent="$TMP_DIR/replaced-parent"
replacement="$TMP_DIR/replacement-parent"
destination="$parent/record"
site="$TMP_DIR/replaced-parent-site"
mkdir "$parent" "$replacement" "$site"
chmod 700 "$parent" "$replacement" "$site"
write_sitecustomize "$site"
PUBLISHER_PARENT="$parent" PUBLISHER_REPLACEMENT_PARENT="$replacement" PUBLISHER_INJECTION=replace-parent PYTHONPATH="$site" \
  run_publisher "$destination" "$(parent_identity "$parent")" "$UUID"
[[ "$RUN_STATUS" -ne 0 ]] || fail "publisher: replaced parent was accepted"
assert_silent
[[ ! -e "$parent/record" && ! -e "$replacement/record" ]] || fail "publisher: parent replacement received a destination"
pass

for signal_name in HUP INT TERM; do
  parent="$TMP_DIR/pre-$signal_name-parent"
  destination="$parent/record"
  site="$TMP_DIR/pre-$signal_name-site"
  mkdir "$parent" "$site"
  chmod 700 "$parent" "$site"
  write_sitecustomize "$site"
  PUBLISHER_SIGNAL="$signal_name" PUBLISHER_INJECTION=pre-signal PYTHONPATH="$site" \
    run_publisher "$destination" "$(parent_identity "$parent")" "$UUID"
  [[ "$RUN_STATUS" -ne 0 ]] || fail "publisher: $signal_name before link was accepted"
  assert_silent
  [[ ! -e "$destination" ]] || fail "publisher: $signal_name before link published a destination"
done
pass

for signal_name in HUP INT TERM; do
  parent="$TMP_DIR/post-$signal_name-parent"
  destination="$parent/record"
  site="$TMP_DIR/post-$signal_name-site"
  marker="$TMP_DIR/post-$signal_name-marker"
  mkdir "$parent" "$site"
  chmod 700 "$parent" "$site"
  write_sitecustomize "$site"
  PUBLISHER_SIGNAL="$signal_name" PUBLISHER_MARKER="$marker" PUBLISHER_INJECTION=post-signal PYTHONPATH="$site" \
    run_publisher "$destination" "$(parent_identity "$parent")" "$UUID"
  [[ "$RUN_STATUS" -eq 0 ]] || fail "publisher: $signal_name after link did not commit"
  assert_success_stdout
  assert_record "$destination"
  [[ "$(<"$marker")" == linked ]] || fail "publisher: $signal_name link marker missing"
done
pass

parent="$TMP_DIR/stdout-error-parent"
destination="$parent/record"
site="$TMP_DIR/stdout-error-site"
marker="$TMP_DIR/stdout-error-marker"
mkdir "$parent" "$site"
chmod 700 "$parent" "$site"
write_sitecustomize "$site"
PUBLISHER_MARKER="$marker" PUBLISHER_INJECTION=stdout-error PYTHONPATH="$site" \
  run_publisher "$destination" "$(parent_identity "$parent")" "$UUID"
[[ "$RUN_STATUS" -eq 0 ]] || fail "publisher: stdout error after link did not commit"
assert_record "$destination"
[[ "$(<"$marker")" == linked ]] || fail "publisher: stdout error link marker missing"
pass

printf 'PASS: voice-agent-real-room-binding-publisher (%s assertions)\n' "$TEST_COUNT"
