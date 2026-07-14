#!/usr/bin/env bash
# Legacy wrapper — prefer: perry wipe
# Keeps devices by default.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PERRY_HOME="$ROOT"
exec "$ROOT/scripts/perry" wipe content "$@"
