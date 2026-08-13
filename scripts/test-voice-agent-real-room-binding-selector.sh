#!/usr/bin/env bash
set -euo pipefail

umask 077
set +x

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SELECTOR="$ROOT_DIR/scripts/voice-agent-real-room-binding-selector.py"
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

make_snapshot() {
  local topology="$1" destination="$2" rows_json="$3" schema_kind="${4:-integer}"
  python3 - "$topology" "$destination" "$rows_json" "$schema_kind" <<'PY'
import json
import shutil
import sqlite3
import sys
from pathlib import Path

topology, destination, rows_json, schema_kind = sys.argv[1:]
destination_path = Path(destination)
source_path = destination_path.parent / "source.db"
rows = json.loads(rows_json)
connection = sqlite3.connect(source_path)
connection.execute("PRAGMA journal_mode=WAL")
connection.execute("PRAGMA wal_autocheckpoint=0")
if schema_kind == "integer":
    connection.execute(
        "CREATE TABLE ConversationEntity ("
        "id TEXT NOT NULL PRIMARY KEY, "
        "create_at INTEGER NOT NULL, "
        "update_at INTEGER NOT NULL)"
    )
else:
    connection.execute(
        "CREATE TABLE ConversationEntity ("
        "id TEXT NOT NULL PRIMARY KEY, "
        "create_at TEXT, "
        "update_at INTEGER NOT NULL)"
    )
connection.executemany(
    "INSERT INTO ConversationEntity(id, create_at, update_at) VALUES (?, ?, ?)",
    rows,
)
connection.commit()
if topology == "main-only":
    connection.close()
    assert not source_path.with_name(source_path.name + "-wal").exists()
    shutil.copyfile(source_path, destination_path)
elif topology == "main-wal":
    source_wal = source_path.with_name(source_path.name + "-wal")
    assert source_wal.exists()
    shutil.copyfile(source_path, destination_path)
    shutil.copyfile(source_wal, destination_path.with_name(destination_path.name + "-wal"))
    connection.close()
else:
    raise ValueError(topology)
PY
}

run_selector() {
  set +e
  python3 "$SELECTOR" "$@" >"$TMP_DIR/stdout" 2>"$TMP_DIR/stderr"
  RUN_STATUS=$?
  set -e
}

assert_silent_failure() {
  [[ "$RUN_STATUS" -ne 0 ]] || fail "$1: accepted invalid input"
  [[ ! -s "$TMP_DIR/stdout" ]] || fail "$1: stdout was not empty"
  [[ ! -s "$TMP_DIR/stderr" ]] || fail "$1: stderr was not empty"
}

assert_selected() {
  local expected="$1" label="$2"
  [[ "$RUN_STATUS" -eq 0 ]] || fail "$label: selection failed"
  printf '%s\n' "$expected" > "$TMP_DIR/expected"
  cmp -s "$TMP_DIR/expected" "$TMP_DIR/stdout" || fail "$label: wrong stdout"
  [[ ! -s "$TMP_DIR/stderr" ]] || fail "$label: stderr was not empty"
}

[[ -x "$SELECTOR" ]] || fail "selector is missing"

WINDOW_START=1776070800000
WINDOW_END=1776072600000
OLDER=11111111-1111-4111-8111-111111111111
INTENDED=22222222-2222-4222-8222-222222222222
BOUNDARY_START=33333333-3333-4333-8333-333333333333
BOUNDARY_END=44444444-4444-4444-8444-444444444444

main_parent="$TMP_DIR/main-only"
mkdir "$main_parent"
chmod 700 "$main_parent"
main_path="$main_parent/rikka_hub"
make_snapshot main-only "$main_path" \
  "[[\"$BOUNDARY_START\", $WINDOW_START, $WINDOW_START], [\"$OLDER\", 1776070860000, 1776070860001], [\"$INTENDED\", 1776070920000, 1776070920001], [\"$BOUNDARY_END\", $WINDOW_END, $WINDOW_END]]"
[[ ! -e "$main_path-wal" ]] || fail "main-only fixture unexpectedly has a WAL"
run_selector "$main_path" - "$WINDOW_START" "$WINDOW_END"
assert_selected "$INTENDED" "main-only snapshot"
pass
run_selector "$main_path" - "$WINDOW_START" "$((WINDOW_START + 1))"
assert_selected "$BOUNDARY_START" "main-only start boundary"
pass

wal_parent="$TMP_DIR/main-wal"
mkdir "$wal_parent"
chmod 700 "$wal_parent"
wal_main="$wal_parent/rikka_hub"
make_snapshot main-wal "$wal_main" \
  "[[\"$BOUNDARY_START\", $WINDOW_START, $WINDOW_START], [\"$OLDER\", 1776070860000, 1776070860001], [\"$INTENDED\", 1776070920000, 1776070920001], [\"$BOUNDARY_END\", $WINDOW_END, $WINDOW_END]]"
[[ -s "$wal_main-wal" ]] || fail "main+WAL fixture has no WAL"
run_selector "$wal_main" "$wal_main-wal" "$WINDOW_START" "$WINDOW_END"
assert_selected "$INTENDED" "main+WAL snapshot"
pass
run_selector "$wal_main" "$wal_main-wal" "$WINDOW_START" "$((WINDOW_START + 1))"
assert_selected "$BOUNDARY_START" "main+WAL start boundary"
pass

update_parent="$TMP_DIR/older-later-update"
mkdir "$update_parent"
chmod 700 "$update_parent"
update_main="$update_parent/rikka_hub"
make_snapshot main-only "$update_main" \
  "[[\"$OLDER\", 1776070860000, 1776072500000], [\"$INTENDED\", 1776070920000, 1776070920000]]"
run_selector "$update_main" - "$WINDOW_START" "$WINDOW_END"
assert_selected "$INTENDED" "older row with later update"
pass

run_selector "$main_path" - 1776072600001 1776074400001
assert_silent_failure "empty window"
pass

tied_parent="$TMP_DIR/tied"
mkdir "$tied_parent"
chmod 700 "$tied_parent"
tied_main="$tied_parent/rikka_hub"
make_snapshot main-only "$tied_main" \
  "[[\"$OLDER\", 1776070860000, 1776070860001], [\"$INTENDED\", 1776070920000, 1776070920001], [\"33333333-3333-4333-8333-333333333333\", 1776070920000, 1776070920002]]"
run_selector "$tied_main" - "$WINDOW_START" "$WINDOW_END"
assert_silent_failure "tied maximum"
pass

malformed_parent="$TMP_DIR/malformed"
mkdir "$malformed_parent"
chmod 700 "$malformed_parent"
malformed_main="$malformed_parent/rikka_hub"
make_snapshot main-only "$malformed_main" \
  "[[\"22222222-2222-4222-8222-222222222222\", 1776070920000, 1776070920001]]"
run_selector "$malformed_main" - "$WINDOW_START" "$WINDOW_END"
assert_selected "$INTENDED" "canonical UUID"
pass

for bad_id in 22222222-2222-4222-8222-22222222222A not-a-uuid; do
  bad_parent="$TMP_DIR/bad-id-$bad_id"
  mkdir "$bad_parent"
  chmod 700 "$bad_parent"
  bad_main="$bad_parent/rikka_hub"
  make_snapshot main-only "$bad_main" "[[\"$bad_id\", 1776070920000, 1776070920001]]"
  run_selector "$bad_main" - "$WINDOW_START" "$WINDOW_END"
  assert_silent_failure "malformed in-window UUID $bad_id"
  pass
done

text_parent="$TMP_DIR/text-create-at"
mkdir "$text_parent"
chmod 700 "$text_parent"
text_main="$text_parent/rikka_hub"
make_snapshot main-only "$text_main" \
  "[[\"$INTENDED\", \"1776070920000\", 1776070920001]]" text
run_selector "$text_main" - "$WINDOW_START" "$WINDOW_END"
assert_silent_failure "non-integer in-window create_at"
pass

invalid_windows=(
  -1 "$WINDOW_END"
  +1776070800000 "$WINDOW_END"
  ' 1776070800000' "$WINDOW_END"
  "$WINDOW_START" "$WINDOW_START"
  "$WINDOW_END" "$WINDOW_START"
  "$WINDOW_START" 1776072600001
  9223372036854775808 9223372036854775809
  "$WINDOW_START" 9223372036854775808
)
for ((window_index = 0; window_index < ${#invalid_windows[@]}; window_index += 2)); do
  invalid_start="${invalid_windows[window_index]}"
  invalid_end="${invalid_windows[window_index + 1]}"
  run_selector "$main_path" - "$invalid_start" "$invalid_end"
  assert_silent_failure "invalid window $invalid_start:$invalid_end"
  pass
done

invalid_parent="$TMP_DIR/invalid-snapshots"
mkdir "$invalid_parent"
chmod 700 "$invalid_parent"

missing_main="$invalid_parent/missing-main"
run_selector "$missing_main" - "$WINDOW_START" "$WINDOW_END"
assert_silent_failure "missing main"
pass

wal_without_main="$invalid_parent/wal-without-main"
cp -- "$wal_main-wal" "$wal_without_main-wal"
run_selector "$wal_without_main" "$wal_without_main-wal" "$WINDOW_START" "$WINDOW_END"
assert_silent_failure "WAL without main"
pass

malformed_file="$invalid_parent/malformed-main"
truncate -s 512 "$malformed_file"
run_selector "$malformed_file" - "$WINDOW_START" "$WINDOW_END"
assert_silent_failure "malformed main"
pass

link_parent="$TMP_DIR/links"
mkdir "$link_parent"
chmod 700 "$link_parent"
ln -s -- "$main_path" "$link_parent/rikka_hub"
run_selector "$link_parent/rikka_hub" - "$WINDOW_START" "$WINDOW_END"
assert_silent_failure "symlink main"
pass

cp -- "$wal_main" "$link_parent/wal-main"
ln -s -- "$wal_main-wal" "$link_parent/wal-main-wal"
run_selector "$link_parent/wal-main" "$link_parent/wal-main-wal" "$WINDOW_START" "$WINDOW_END"
assert_silent_failure "symlink WAL"
pass

run_selector "$main_path" "$TMP_DIR/unexpected-wal" "$WINDOW_START" "$WINDOW_END"
assert_silent_failure "unexpected WAL argument for main-only"
pass

python3 - "$SELECTOR" <<'PY'
import re
import sys

source = open(sys.argv[1], encoding="utf-8").read().lower()
for token in ("update_at", "title", "node", "prompt", "message", "transcript"):
    assert re.search(r"\b" + token + r"\b", source) is None, token
PY
pass

for sidecar in "$TMP_DIR"/*-wal "$TMP_DIR"/*-shm; do
  if [[ -f "$sidecar" ]]; then
    fail "selector left a SQLite sidecar outside a private fixture directory"
  fi
done
pass

printf 'PASS: voice-agent-real-room-binding-selector (%s assertions)\n' "$TEST_COUNT"
