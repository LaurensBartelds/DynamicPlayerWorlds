#!/usr/bin/env bash
# Wait for the compose stack, probe RCON, join a lobby player through Velocity.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

load_versions
require_cmd docker
require_cmd python3
require_cmd npm
require_cmd node

PAPER_A="$(container_name paper-a)"
PAPER_B="$(container_name paper-b)"
VELOCITY="$(container_name velocity)"
RCON_PY="${SCRIPT_DIR}/rcon.py"
RCON_PASS="${E2E_RCON_PASSWORD}"
BOT_USER="${E2E_BOT_USERNAME:-E2EPlayer}"
BOT_VERSION="${E2E_BOT_MC_VERSION:-26.1}"
BOT_PROTOCOL="${E2E_BOT_PROTOCOL_VERSION:-776}"
HOST="${E2E_HOST:-127.0.0.1}"
PROXY_PORT="${E2E_PROXY_PORT:-25565}"
RCON_A_PORT="${E2E_RCON_A_PORT:-25575}"
RCON_B_PORT="${E2E_RCON_B_PORT:-25576}"

rcon_a() {
  python3 "${RCON_PY}" --host "${HOST}" --port "${RCON_A_PORT}" --password "${RCON_PASS}" "$1"
}

rcon_b() {
  python3 "${RCON_PY}" --host "${HOST}" --port "${RCON_B_PORT}" --password "${RCON_PASS}" "$1"
}

echo "==> Waiting for Paper nodes (plugins + Done)"
# World gen on first boot can take a few minutes on a cold CI runner.
wait_for_log "${PAPER_A}" "e2e-harness enabled" 360
# gzmn-worlds only reaches this line after it has migrated the schema and
# read network policy from PostgreSQL, so waiting on it is what makes this
# harness a real test of the shaded jar against a real database.
wait_for_log "${PAPER_A}" "enabled: node " 180
wait_for_log "${PAPER_A}" "Done (" 360

wait_for_log "${PAPER_B}" "e2e-harness enabled" 360
wait_for_log "${PAPER_B}" "enabled: node " 180
wait_for_log "${PAPER_B}" "Done (" 360

echo "==> Waiting for Velocity"
# Velocity 4 logs "Listening on" rather than Bukkit's "Done (".
wait_for_log "${VELOCITY}" "Listening on" 180

echo "==> RCON probes"
pong="$(rcon_a "e2e ping" || true)"
if [[ "${pong}" != *e2e\ pong* ]]; then
  echo "paper-a RCON e2e ping failed: '${pong}'" >&2
  exit 1
fi
echo "paper-a: ${pong}"

pong_b="$(rcon_b "e2e ping" || true)"
if [[ "${pong_b}" != *e2e\ pong* ]]; then
  echo "paper-b RCON e2e ping failed: '${pong_b}'" >&2
  exit 1
fi
echo "paper-b: ${pong_b}"

echo "==> Installing bot deps"
(
  cd "${E2E_ROOT}/bot"
  if [[ -f package-lock.json ]]; then
    npm ci --no-audit --no-fund
  else
    npm install --no-audit --no-fund
  fi
)

echo "==> Bot join through Velocity (${BOT_USER} @ ${HOST}:${PROXY_PORT}, schema ${BOT_VERSION}, protocol ${BOT_PROTOCOL})"
(
  cd "${E2E_ROOT}/bot"
  E2E_HOST="${HOST}" \
    E2E_PORT="${PROXY_PORT}" \
    E2E_USER="${BOT_USER}" \
    E2E_MC_VERSION="${BOT_VERSION}" \
    E2E_PROTOCOL_VERSION="${BOT_PROTOCOL}" \
    E2E_TIMEOUT_MS="${E2E_BOT_TIMEOUT_MS:-180000}" \
    node join-lobby.mjs
)

echo "==> Assert lobby saw the player (RCON + join marker)"
# Poll briefly: spawn is observed by the bot before the server finishes logging.
deadline=$((SECONDS + 60))
status=""
while (( SECONDS < deadline )); do
  status="$(rcon_a "e2e status" || true)"
  if [[ "${status}" == *"${BOT_USER}"* ]]; then
    break
  fi
  # Bot may already have quit; accept last-join file via docker exec.
  if docker exec "${PAPER_A}" test -f /server/plugins/e2e-harness/last-join.txt 2>/dev/null; then
    last="$(docker exec "${PAPER_A}" cat /server/plugins/e2e-harness/last-join.txt 2>/dev/null | tr -d '\r' | head -n 1 || true)"
    if [[ "${last}" == "${BOT_USER}" ]]; then
      status="e2e status via last-join.txt name=${last}"
      break
    fi
  fi
  if docker logs "${PAPER_A}" 2>&1 | grep -F -q "e2e player_joined name=${BOT_USER}"; then
    status="e2e status via log marker name=${BOT_USER}"
    break
  fi
  sleep 2
done

if [[ "${status}" != *"${BOT_USER}"* ]]; then
  echo "lobby did not observe player ${BOT_USER}" >&2
  echo "last status: ${status}" >&2
  echo "--- paper-a logs ---" >&2
  docker logs "${PAPER_A}" 2>&1 | tail -n 80 >&2 || true
  echo "--- velocity logs ---" >&2
  docker logs "${VELOCITY}" 2>&1 | tail -n 80 >&2 || true
  exit 1
fi

echo "lobby ok: ${status}"
echo "smoke passed: player joined lobby through Velocity"
