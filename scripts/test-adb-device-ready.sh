#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/adb-device-ready.sh"
TMP_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

assert_contains() {
  local haystack="$1"
  local needle="$2"
  if [[ "$haystack" != *"$needle"* ]]; then
    printf 'Expected output to contain: %s\n' "$needle" >&2
    printf 'Actual output:\n%s\n' "$haystack" >&2
    exit 1
  fi
}

write_success_adb() {
  cat > "$TMP_DIR/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -euo pipefail

case "$*" in
  "devices -l")
    printf 'List of devices attached\n'
    printf 'RZ device product:r11 model:SM-S711B device:r11s transport_id:1\n'
    ;;
  "-s RZ shell echo ok")
    printf 'ok\n'
    ;;
  "-s RZ shell getprop sys.boot_completed")
    printf '1\n'
    ;;
  "-s RZ shell getprop init.svc.bootanim")
    printf 'stopped\n'
    ;;
  "-s RZ shell getprop ro.product.model")
    printf 'SM-S711B\n'
    ;;
  "-s RZ shell getprop ro.build.version.release")
    printf '16\n'
    ;;
  *)
    printf 'unexpected adb args: %s\n' "$*" >&2
    exit 99
    ;;
esac
FAKE_ADB
  chmod +x "$TMP_DIR/adb"
}

write_unauthorized_adb() {
  cat > "$TMP_DIR/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -euo pipefail

case "$*" in
  "devices -l")
    printf 'List of devices attached\n'
    printf 'RZ unauthorized transport_id:1\n'
    ;;
  *)
    printf 'unexpected adb args: %s\n' "$*" >&2
    exit 99
    ;;
esac
FAKE_ADB
  chmod +x "$TMP_DIR/adb"
}

write_success_adb
success_output="$(PATH="$TMP_DIR:$PATH" "$SCRIPT" RZ 2>&1)"
assert_contains "$success_output" "ADB ready: serial=RZ state=device boot_completed=1 bootanim=stopped model=SM-S711B android=16"

write_unauthorized_adb
set +e
unauthorized_output="$(PATH="$TMP_DIR:$PATH" "$SCRIPT" RZ 2>&1)"
unauthorized_status=$?
set -e

if [[ "$unauthorized_status" -eq 0 ]]; then
  printf 'Expected unauthorized device check to fail.\n' >&2
  exit 1
fi
assert_contains "$unauthorized_output" "ADB device RZ is unauthorized"

printf 'adb-device-ready tests passed.\n'
