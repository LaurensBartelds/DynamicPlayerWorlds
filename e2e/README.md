# e2e compose harness (F11)

Docker Compose stack that boots the foundation the way an operator would:

| Service | Role |
| --- | --- |
| `postgres` | PostgreSQL 18.3 (same pin as `:testing`) |
| `minio` + `minio-init` | S3-compatible store + worlds bucket |
| `paper-a` | Lobby Paper node (`gzmn-worlds` + `e2e-harness`) |
| `paper-b` | Second worlds node (registered on the proxy) |
| `velocity` | Proxy (`gzmn-worlds-proxy`) |

Acceptance for F11: **one offline player joins the lobby through Velocity in CI**.

## Quick start

Requires JDK 25, Docker with Compose v2, Node 20+, Python 3, `curl`, `sha256sum`.

```sh
e2e/scripts/run.sh
```

That builds the plugin jars, downloads pinned Paper / Velocity, stages
`e2e/runtime/`, brings the stack up, runs the smoke, and tears down.

Keep the stack for debugging:

```sh
E2E_KEEP=1 e2e/scripts/run.sh
# ...
docker compose -f e2e/compose.yml --env-file e2e/versions.env down -v
```

## Layout

```
e2e/
  compose.yml           # the stack
  versions.env          # every pin (Paper build, images, secrets)
  config/               # Velocity + Paper templates
  harness-plugin/       # :e2e-harness — join markers + /e2e RCON commands
  bot/                  # minecraft-protocol offline client
  scripts/
    run.sh              # CI entry
    prepare.sh          # build + download + stage
    smoke.sh            # wait, RCON, bot join, assert
    rcon.py             # Source RCON, no deps
    common.sh
  runtime/              # staged trees (gitignored)
  downloads/            # cached server jars (gitignored)
```

## Client protocol

`minecraft-data` currently ships packet schemas through Minecraft **26.1**
(protocol 775). The repo pins Paper **26.2** (protocol 776). The bot keeps the
26.1 schemas and advertises protocol **776** on the handshake — adjacent
releases, enough for a join smoke. When a real 26.2 schema pin lands upstream,
set `E2E_BOT_MC_VERSION=26.2` in `versions.env` and drop the spoof.

## Driving the stack

- RCON on paper-a: `localhost:25575`, password from `versions.env`
- RCON on paper-b: `localhost:25576`
- Velocity: `localhost:25565`
- `/e2e ping` → `e2e pong`
- `/e2e status` → `e2e status online=N players=...`

The harness plugin also logs `e2e player_joined name=...` and writes
`plugins/e2e-harness/last-join.txt`.

## Pins

Bump `e2e/versions.env` together with:

- `gradle/libs.versions.toml` (`paperApi`, `velocityApi`)
- `testing` fixtures (`TestDatabase.IMAGE`, `TestObjectStore.IMAGE`)
