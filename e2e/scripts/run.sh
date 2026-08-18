#!/usr/bin/env bash
# Full e2e entry point for CI and local runs (plan §11 / F11).
#
#   e2e/scripts/run.sh
#
# Stages jars, brings the compose stack up, runs the lobby-join smoke, then
# tears the stack down (unless E2E_KEEP=1).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

load_versions
require_cmd docker

cleanup() {
  local code=$?
  if [[ "${E2E_KEEP:-0}" == "1" ]]; then
    echo "E2E_KEEP=1 — leaving stack up"
    exit "${code}"
  fi
  echo "==> Tearing down compose stack"
  compose down -v --remove-orphans || true
  exit "${code}"
}
trap cleanup EXIT

echo "==> prepare"
"${SCRIPT_DIR}/prepare.sh"

echo "==> compose up"
compose up -d --pull missing

echo "==> smoke"
"${SCRIPT_DIR}/smoke.sh"

echo "e2e harness OK"
