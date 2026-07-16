#!/usr/bin/env bash
set -euo pipefail

env_file="${1:-.env}"
touch "$env_file"

ensure_value() {
  local key="$1"
  local value="$2"
  if ! grep -q "^${key}=" "$env_file"; then
    printf '%s=%s\n' "$key" "$value" >> "$env_file"
  fi
}

ensure_value PERRY_WORKSPACE_ENABLED true
ensure_value PERRY_WORKSPACE_PODMAN_BINARY podman
ensure_value PERRY_WORKSPACE_DATA_ROOT ./data/workspaces
ensure_value PERRY_WORKSPACE_DEFAULT_IMAGE docker.io/library/python:3.12-slim-bookworm
ensure_value PERRY_WORKSPACE_MEMORY 2g
ensure_value PERRY_WORKSPACE_CPUS 2
ensure_value PERRY_WORKSPACE_PIDS_LIMIT 512

