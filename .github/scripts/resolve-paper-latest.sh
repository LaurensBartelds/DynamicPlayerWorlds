#!/usr/bin/env bash
# Resolve Paper's newest published paper-api coordinate and a matching server jar.
#
# Writes a GitHub Actions-compatible env file (key=value) to the path in $1
# (default: paper-latest.env). Used by paper-latest.yml so a Minecraft upgrade
# failure arrives as a red nightly weeks before an operator hits it (plan §5.6).
#
# Requires: curl, python3 (stdlib only).
set -euo pipefail

OUT="${1:-paper-latest.env}"
USER_AGENT="${PAPER_USER_AGENT:-gzmn-worlds-ci/0.1 (+https://github.com/GZMN/DynamicPlayerWorlds)}"

PAPER_API_META_URL="${PAPER_API_META_URL:-https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml}"
FILL_BASE="${FILL_BASE:-https://fill.papermc.io/v3/projects/paper}"

echo "Resolving latest paper-api from ${PAPER_API_META_URL}"
META_XML="$(curl -fsSL -A "${USER_AGENT}" "${PAPER_API_META_URL}")"
PAPER_API="$(printf '%s' "${META_XML}" | python3 -c '
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
release = root.findtext("./versioning/release")
latest = root.findtext("./versioning/latest")
version = (release or latest or "").strip()
if not version:
    sys.exit("paper-api maven-metadata.xml has no release/latest")
print(version)
')"

echo "paper-api=${PAPER_API}"

# Map paper-api coordinates onto a Paper server (MC version + build).
#   26.2.build.112-stable  -> mc=26.2  build=112
#   1.21.4-R0.1-SNAPSHOT   -> mc=1.21.4 (fill: latest STABLE build of that version)
python3 - "${PAPER_API}" "${FILL_BASE}" "${USER_AGENT}" "${OUT}" <<'PY'
import json, re, sys, urllib.request

paper_api, fill_base, user_agent, out_path = sys.argv[1:5]

def fetch(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": user_agent})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)

mc = None
build = None

m = re.fullmatch(r"(\d+\.\d+)\.build\.(\d+)(?:-stable)?", paper_api)
if m:
    mc, build = m.group(1), int(m.group(2))
else:
    m = re.fullmatch(r"(\d+\.\d+(?:\.\d+)?)-R\d+\.\d+(?:-SNAPSHOT)?", paper_api)
    if m:
        mc = m.group(1)
    else:
        # year.season without build suffix, or anything else: take the leading MC-ish token
        mc = paper_api.split("-", 1)[0]
        mc = re.sub(r"\.build\.\d+$", "", mc)

if build is None:
    builds = fetch(f"{fill_base}/versions/{mc}/builds")
    if not isinstance(builds, list) or not builds:
        raise SystemExit(f"no fills builds for Minecraft version {mc}")
    stable = [b for b in builds if b.get("channel") == "STABLE"]
    pool = stable or builds
    # Fill does not guarantee order; pick the highest build id.
    chosen = max(pool, key=lambda b: int(b["id"]))
    build = int(chosen["id"])
    download = chosen["downloads"]["server:default"]
else:
    build_info = fetch(f"{fill_base}/versions/{mc}/builds/{build}")
    download = build_info["downloads"]["server:default"]

server_url = download["url"]
server_name = download["name"]
server_sha256 = download.get("checksums", {}).get("sha256", "")

lines = [
    f"PAPER_API={paper_api}",
    f"PAPER_MC_VERSION={mc}",
    f"PAPER_BUILD={build}",
    f"PAPER_SERVER_URL={server_url}",
    f"PAPER_SERVER_NAME={server_name}",
    f"PAPER_SERVER_SHA256={server_sha256}",
]
with open(out_path, "w", encoding="utf-8") as fh:
    fh.write("\n".join(lines) + "\n")

print(f"Wrote {out_path}:")
for line in lines:
    print(f"  {line}")
PY
