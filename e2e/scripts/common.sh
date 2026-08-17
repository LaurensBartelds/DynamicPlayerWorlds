#!/usr/bin/env bash
# Shared helpers for the e2e harness scripts.
set -euo pipefail

E2E_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "${E2E_ROOT}/.." && pwd)"
RUNTIME_DIR="${E2E_ROOT}/runtime"
DOWNLOADS_DIR="${E2E_ROOT}/downloads"
VERSIONS_ENV="${E2E_ROOT}/versions.env"

USER_AGENT="${PAPER_USER_AGENT:-gzmn-worlds-ci/0.1 (+https://github.com/GZMN/DynamicPlayerWorlds)}"

load_versions() {
  if [[ ! -f "${VERSIONS_ENV}" ]]; then
    echo "missing ${VERSIONS_ENV}" >&2
    exit 1
  fi
  # shellcheck disable=SC1090
  set -a
  # shellcheck disable=SC1091
  source "${VERSIONS_ENV}"
  set +a
}

require_cmd() {
  local cmd="$1"
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "required command not found: ${cmd}" >&2
    exit 1
  fi
}

download_pinned() {
  # download_pinned <url> <sha256> <dest>
  local url="$1"
  local sha="$2"
  local dest="$3"
  local tmp="${dest}.part"

  mkdir -p "$(dirname "${dest}")"
  if [[ -f "${dest}" ]]; then
    if echo "${sha}  ${dest}" | sha256sum -c - >/dev/null 2>&1; then
      return 0
    fi
    echo "stale download (checksum mismatch), re-fetching ${dest}"
    rm -f "${dest}"
  fi

  echo "Downloading $(basename "${dest}")"
  curl -fsSL -A "${USER_AGENT}" -o "${tmp}" "${url}"
  echo "${sha}  ${tmp}" | sha256sum -c -
  mv -f "${tmp}" "${dest}"
}

compose() {
  # Always run compose from e2e/ so relative volume paths resolve.
  # Export versions.env into the compose process environment.
  (
    cd "${E2E_ROOT}"
    set -a
    # shellcheck disable=SC1091
    source "${VERSIONS_ENV}"
    set +a
    docker compose -f compose.yml "$@"
  )
}

wait_for_log() {
  # wait_for_log <container> <pattern> <timeout_sec>
  local container="$1"
  local pattern="$2"
  local timeout_sec="${3:-180}"
  local start
  start="$(date +%s)"

  echo "Waiting up to ${timeout_sec}s for '${pattern}' in ${container}"
  while true; do
    if docker logs "${container}" 2>&1 | grep -F -q "${pattern}"; then
      echo "Observed in ${container}: ${pattern}"
      return 0
    fi
    if docker logs "${container}" 2>&1 | grep -Eiq 'Error occurred while enabling|Could not load .*(gzmn|e2e-harness)|Unsupported API version|FAILED TO BIND|You need to agree to the EULA'; then
      echo "Failure signature in ${container} logs:" >&2
      docker logs "${container}" 2>&1 | tail -n 120 >&2 || true
      return 1
    fi
    local now
    now="$(date +%s)"
    if (( now - start >= timeout_sec )); then
      echo "Timed out waiting for '${pattern}' in ${container}" >&2
      docker logs "${container}" 2>&1 | tail -n 160 >&2 || true
      return 1
    fi
    # Container died?
    if ! docker inspect -f '{{.State.Running}}' "${container}" 2>/dev/null | grep -q true; then
      echo "Container ${container} is not running" >&2
      docker logs "${container}" 2>&1 | tail -n 160 >&2 || true
      return 1
    fi
    sleep 3
  done
}

container_name() {
  # Compose project name is gzmn-e2e; default service container names.
  local service="$1"
  echo "gzmn-e2e-${service}-1"
}
