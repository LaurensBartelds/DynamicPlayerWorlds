#!/usr/bin/env bash
# Boot a bare Paper server with the shaded backend jar and require the plugin
# enable line. Used by paper-latest.yml so a Paper upgrade that breaks enable
# fails CI rather than an operator's node (plan §5.6 / F10).
#
# Env:
#   PAPER_SERVER_URL   (required) download URL for the server jar
#   PAPER_SERVER_SHA256 (optional) expected sha256 of the jar
#   PLUGIN_JAR         (required) path to gzmn-worlds-*.jar
#   BOOT_DIR           (default: ./paper-boot)
#   JAVA_BIN           (default: java)
#   BOOT_TIMEOUT_SEC   (default: 180)
set -euo pipefail

: "${PAPER_SERVER_URL:?PAPER_SERVER_URL is required}"
: "${PLUGIN_JAR:?PLUGIN_JAR is required}"

BOOT_DIR="${BOOT_DIR:-./paper-boot}"
JAVA_BIN="${JAVA_BIN:-java}"
BOOT_TIMEOUT_SEC="${BOOT_TIMEOUT_SEC:-180}"
USER_AGENT="${PAPER_USER_AGENT:-gzmn-worlds-ci/0.1 (+https://github.com/GZMN/DynamicPlayerWorlds)}"
ENABLE_PATTERN="${ENABLE_PATTERN:-enabled: minecraft}"

if [[ ! -f "${PLUGIN_JAR}" ]]; then
  echo "plugin jar not found: ${PLUGIN_JAR}" >&2
  exit 1
fi

rm -rf "${BOOT_DIR}"
mkdir -p "${BOOT_DIR}/plugins"

echo "Downloading Paper from ${PAPER_SERVER_URL}"
curl -fsSL -A "${USER_AGENT}" -o "${BOOT_DIR}/paper.jar" "${PAPER_SERVER_URL}"

if [[ -n "${PAPER_SERVER_SHA256:-}" ]]; then
  echo "Verifying sha256 ${PAPER_SERVER_SHA256}"
  echo "${PAPER_SERVER_SHA256}  ${BOOT_DIR}/paper.jar" | sha256sum -c -
fi

cp "${PLUGIN_JAR}" "${BOOT_DIR}/plugins/"
printf 'eula=true\n' > "${BOOT_DIR}/eula.txt"
cat > "${BOOT_DIR}/server.properties" <<'EOF'
online-mode=false
max-players=1
spawn-protection=0
motd=gzmn-worlds paper-latest boot
sync-chunk-writes=true
EOF
# Skip the downloader's first-run prompts; we already fetched the jar.
printf '[]\n' > "${BOOT_DIR}/ops.json"
printf '[]\n' > "${BOOT_DIR}/whitelist.json"
printf '[]\n' > "${BOOT_DIR}/banned-players.json"
printf '[]\n' > "${BOOT_DIR}/banned-ips.json"

LOG="${BOOT_DIR}/boot.log"
FIFO="${BOOT_DIR}/cmd.fifo"
rm -f "${FIFO}"
mkfifo "${FIFO}"

echo "Starting Paper (timeout ${BOOT_TIMEOUT_SEC}s); looking for '${ENABLE_PATTERN}'"
(
  cd "${BOOT_DIR}"
  # Keep the fifo open for the server's lifetime.
  "${JAVA_BIN}" -Xms512M -Xmx1G -jar paper.jar --nogui < cmd.fifo > boot.log 2>&1 &
  echo $! > paper.pid
) &
# Open the writer side after the reader is attached.
sleep 1
exec 3>"${FIFO}"

deadline=$((SECONDS + BOOT_TIMEOUT_SEC))
enabled=0
while (( SECONDS < deadline )); do
  if [[ -f "${LOG}" ]] && grep -F -q "${ENABLE_PATTERN}" "${LOG}"; then
    enabled=1
    break
  fi
  if [[ -f "${LOG}" ]] && grep -Eiq 'Unsupported API version|Error occurred while enabling|Could not load .*gzmn' "${LOG}"; then
    echo "Paper reported a plugin load/enable failure:" >&2
    tail -n 80 "${LOG}" >&2 || true
    echo stop >&3 || true
    exec 3>&-
    wait || true
    exit 1
  fi
  # Server process died early.
  if [[ -f "${BOOT_DIR}/paper.pid" ]]; then
    pid="$(cat "${BOOT_DIR}/paper.pid")"
    if ! kill -0 "${pid}" 2>/dev/null; then
      echo "Paper exited before plugin enable:" >&2
      tail -n 120 "${LOG}" >&2 || true
      exec 3>&-
      exit 1
    fi
  fi
  sleep 2
done

if [[ "${enabled}" -ne 1 ]]; then
  echo "Timed out waiting for '${ENABLE_PATTERN}' after ${BOOT_TIMEOUT_SEC}s:" >&2
  tail -n 160 "${LOG}" >&2 || true
  echo stop >&3 || true
  exec 3>&-
  if [[ -f "${BOOT_DIR}/paper.pid" ]]; then
    kill "$(cat "${BOOT_DIR}/paper.pid")" 2>/dev/null || true
  fi
  exit 1
fi

echo "Plugin enable observed. Stopping Paper."
echo stop >&3 || true
exec 3>&-

if [[ -f "${BOOT_DIR}/paper.pid" ]]; then
  pid="$(cat "${BOOT_DIR}/paper.pid")"
  # Give a clean shutdown a moment, then force.
  for _ in $(seq 1 30); do
    if ! kill -0 "${pid}" 2>/dev/null; then
      break
    fi
    sleep 1
  done
  if kill -0 "${pid}" 2>/dev/null; then
    kill "${pid}" 2>/dev/null || true
  fi
  wait "${pid}" 2>/dev/null || true
fi

echo "paper-latest boot OK"
grep -F "${ENABLE_PATTERN}" "${LOG}" | tail -n 5
