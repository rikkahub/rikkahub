#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/voice-agent-hermes-gbrain-e2e.sh"

if ! grep -A8 'Seeding Hermes provider in debug settings' "$SCRIPT" | grep -F -- '--es conversation_id "$VOICE_AGENT_E2E_CONVERSATION_ID"' >/dev/null; then
  printf 'seed broadcast must include VOICE_AGENT_E2E_CONVERSATION_ID as conversation_id\n' >&2
  exit 1
fi

if ! grep -F 'HERMES_RESPONSE_TIMEOUT_SECONDS="${VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS:-360}"' "$SCRIPT" >/dev/null; then
  printf 'Hermes response wait must default to 360 seconds for slow private Gbrain prompts\n' >&2
  exit 1
fi

if ! grep -F 'Hermes response hash matched" "VoiceAgentE2E.*hermes_tool_response_hash' "$SCRIPT" | grep -F '"$HERMES_RESPONSE_TIMEOUT_SECONDS"' >/dev/null; then
  printf 'Hermes response hash wait must use HERMES_RESPONSE_TIMEOUT_SECONDS\n' >&2
  exit 1
fi

printf 'voice-agent-hermes-gbrain-e2e tests passed.\n'
