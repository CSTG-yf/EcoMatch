#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:9080}"
EXPECTED_AGENT_ID="${ECOMATCH_AGENT_ID:-33}"
EXPECTED_AGENT_NAME="${ECOMATCH_AGENT_NAME:-银行问数}"

response="$(curl --fail --silent --show-error --max-time 10 \
  "$BASE_URL/api/chat/agent/getAgentList")"

compact="${response//[[:space:]]/}"
if [[ "$compact" != *"\"id\":$EXPECTED_AGENT_ID"* ]] \
  || [[ "$compact" != *"\"name\":\"$EXPECTED_AGENT_NAME\""* ]] \
  || [[ "$compact" != *"\"status\":1"* ]]; then
  echo "ERROR: required online Agent was not returned by $BASE_URL: id=$EXPECTED_AGENT_ID name=$EXPECTED_AGENT_NAME" >&2
  exit 1
fi

echo "Agent verification passed: id=$EXPECTED_AGENT_ID name=$EXPECTED_AGENT_NAME status=1"
