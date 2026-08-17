#!/usr/bin/env bash
# Build plugin jars, download pinned Paper/Velocity/ViaVersion, stage e2e/runtime.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

load_versions
require_cmd curl
require_cmd sha256sum
require_cmd docker

GRADLEW="${REPO_ROOT}/gradlew"
if [[ ! -x "${GRADLEW}" && -f "${GRADLEW}" ]]; then
  chmod +x "${GRADLEW}" || true
fi
if [[ ! -f "${GRADLEW}" ]]; then
  echo "gradlew not found at ${GRADLEW}" >&2
  exit 1
fi

echo "==> Building plugin jars (backend, proxy, e2e-harness)"
(
  cd "${REPO_ROOT}"
  # Skip tests here: the build workflow already owns unit/integration gates.
  # e2e needs the shaded jars and the harness jar only.
  ./gradlew --console=plain \
    :backend:shadowJar \
    :proxy:shadowJar \
    :e2e-harness:jar
)

BACKEND_JAR="$(ls -1 "${REPO_ROOT}/backend/build/libs"/gzmn-worlds-*.jar | head -n 1)"
PROXY_JAR="$(ls -1 "${REPO_ROOT}/proxy/build/libs"/gzmn-worlds-proxy-*.jar | head -n 1)"
HARNESS_JAR="$(ls -1 "${REPO_ROOT}/e2e/harness-plugin/build/libs"/e2e-harness-*.jar | head -n 1)"

if [[ ! -f "${BACKEND_JAR}" || ! -f "${PROXY_JAR}" || ! -f "${HARNESS_JAR}" ]]; then
  echo "missing built jars:" >&2
  echo "  backend=${BACKEND_JAR:-}" >&2
  echo "  proxy=${PROXY_JAR:-}" >&2
  echo "  harness=${HARNESS_JAR:-}" >&2
  exit 1
fi
echo "backend: ${BACKEND_JAR}"
echo "proxy:   ${PROXY_JAR}"
echo "harness: ${HARNESS_JAR}"

echo "==> Downloading server jars"
mkdir -p "${DOWNLOADS_DIR}"
download_pinned "${PAPER_SERVER_URL}" "${PAPER_SERVER_SHA256}" "${DOWNLOADS_DIR}/paper-${PAPER_MC_VERSION}-${PAPER_BUILD}.jar"
download_pinned "${VELOCITY_SERVER_URL}" "${VELOCITY_SERVER_SHA256}" "${DOWNLOADS_DIR}/velocity-${VELOCITY_VERSION}-${VELOCITY_BUILD}.jar"

stage_paper_node() {
  local node="$1"
  local motd="$2"
  local dest="${RUNTIME_DIR}/${node}"

  rm -rf "${dest}"
  mkdir -p "${dest}/plugins" "${dest}/config"

  cp "${DOWNLOADS_DIR}/paper-${PAPER_MC_VERSION}-${PAPER_BUILD}.jar" "${dest}/paper.jar"
  cp "${E2E_ROOT}/config/paper/eula.txt" "${dest}/eula.txt"
  cp "${E2E_ROOT}/config/paper/server.properties" "${dest}/server.properties"
  cp "${E2E_ROOT}/config/paper/bukkit.yml" "${dest}/bukkit.yml"
  cp "${E2E_ROOT}/config/paper/spigot.yml" "${dest}/spigot.yml"
  cp "${E2E_ROOT}/config/paper/config/paper-global.yml" "${dest}/config/paper-global.yml"
  cp "${E2E_ROOT}/config/paper/config/paper-world-defaults.yml" "${dest}/config/paper-world-defaults.yml"

  # Per-node motd so logs are greppable.
  if grep -q '^motd=' "${dest}/server.properties"; then
    sed -i.bak "s/^motd=.*/motd=${motd}/" "${dest}/server.properties"
    rm -f "${dest}/server.properties.bak"
  else
    echo "motd=${motd}" >> "${dest}/server.properties"
  fi

  cp "${BACKEND_JAR}" "${dest}/plugins/"
  cp "${HARNESS_JAR}" "${dest}/plugins/"

  # gzmn-worlds node config. From milestone 1 the plugin refuses to enable
  # without a reachable database, which is what makes this harness the only
  # place that exercises the shaded jar against a real PostgreSQL — the exact
  # combination that hid the relocated JDBC driver not registering with
  # DriverManager. Leaving this out means the plugin quietly fails to enable and
  # the harness proves nothing about it.
  mkdir -p "${dest}/plugins/gzmn-worlds"
  cat > "${dest}/plugins/gzmn-worlds/config.yml" <<EOF
node:
  id: ${node}
  address: ${node}:25565
  heartbeat-seconds: 30

database:
  url: jdbc:postgresql://postgres:5432/gzmn_worlds
  user: gzmn
  password: "${E2E_POSTGRES_PASSWORD}"
  pool-size: 8
  connection-timeout-seconds: 10

storage:
  # Blank follows the server's world container, which is the only directory
  # Bukkit will create a world in.
  local-scratch-path: ""
  local-cache-path: cache
  quarantine-path: quarantine
  # A CI runner has no 20 GiB to spare, and the NFR-3 floor is covered by
  # ConfigValidatorTest rather than here.
  min-free-space-bytes: 0
  s3:
    enabled: false

metrics:
  bind: 0.0.0.0
  port: 9464
EOF

  # Empty JSON lists so Paper skips first-run prompts.
  printf '[]\n' > "${dest}/ops.json"
  printf '[]\n' > "${dest}/whitelist.json"
  printf '[]\n' > "${dest}/banned-players.json"
  printf '[]\n' > "${dest}/banned-ips.json"
  printf '{}\n' > "${dest}/usercache.json"

  echo "staged ${node}"
}

echo "==> Staging runtime trees"
rm -rf "${RUNTIME_DIR}"
mkdir -p "${RUNTIME_DIR}"

stage_paper_node "paper-a" "gzmn-e2e-lobby"
stage_paper_node "paper-b" "gzmn-e2e-node-b"

# Velocity
VEL_DIR="${RUNTIME_DIR}/velocity"
mkdir -p "${VEL_DIR}/plugins"
cp "${DOWNLOADS_DIR}/velocity-${VELOCITY_VERSION}-${VELOCITY_BUILD}.jar" "${VEL_DIR}/velocity.jar"
cp "${E2E_ROOT}/config/velocity/velocity.toml" "${VEL_DIR}/velocity.toml"
# forwarding.secret must be exactly the secret string, no trailing commentary.
printf '%s' "${E2E_FORWARDING_SECRET}" > "${VEL_DIR}/forwarding.secret"
cp "${PROXY_JAR}" "${VEL_DIR}/plugins/"
echo "staged velocity"

echo "prepare complete"
