# DynamicPlayerWorlds E2E Testing Environment

Local Docker Compose testing environment and Mineflayer multi-bot test framework for **DynamicPlayerWorlds**.

This environment allows developers and AI agents to test all features of the plugin cluster (world creation, membership, inventory sync, S3 snapshots, proxy routing, and multi-node handoffs) with real server instances, real database state, and real headless player bots.

---

## Architecture Overview

```
                      +-----------------------------+
                      |   Mineflayer Multi-Bot      |
                      |  Harness (e2e/cli.mjs)      |
                      +--------------+--------------+
                                     |
              +----------------------+----------------------+
              |                      |                      |
      (Minecraft TCP)           (Host RCON)             (Host RCON)
         Port 25565             Port 25575              Port 25576
              |                      |                      |
              v                      v                      v
      +---------------+      +---------------+      +---------------+
      |   Velocity    |----->|    Paper-A    |      |    Paper-B    |
      | 4.0.0 Proxy   |----->|  (Lobby Node) |      | (Worlds Node) |
      +---------------+      +-------+-------+      +-------+-------+
              |                      |                      |
              |                      |                      |
              +--------------+-------+----------------------+
                             |
                   +---------+---------+
                   |                   |
               (PostgreSQL)         (MinIO S3)
                Port 5432          Ports 9000/9001
                   |                   |
                   v                   v
           +---------------+   +---------------+
           |  PostgreSQL   |   |   MinIO S3    |
           |     18.3      |   | Object Store  |
           +---------------+   +---------------+
```

The stack is composed of 5 services defined in [`e2e/compose.yml`](compose.yml):

| Service | Image / Version | Role |
| --- | --- | --- |
| `postgres` | `postgres:18.3` | Shared PostgreSQL database storing worlds, leases, members, profiles, and node metadata. |
| `minio` + `minio-init` | `minio/minio` | S3-compatible object store managing world snapshot archives in bucket `gzmn-worlds`. |
| `paper-a` | `eclipse-temurin:25-jre` + Paper 26.2 | Primary backend lobby node running `gzmn-worlds` and `e2e-harness`. |
| `paper-b` | `eclipse-temurin:25-jre` + Paper 26.2 | Secondary backend node for multi-node routing and lease transfers. |
| `velocity` | `eclipse-temurin:25-jre` + Velocity 4 | Velocity proxy running `gzmn-worlds-proxy`, forwarding traffic and routing players. |

---

## Connection Coordinates & Ports

All services bind to `127.0.0.1` on the host machine:

| Service | Host Port | Protocol | Credentials / Details |
| --- | --- | --- | --- |
| **Velocity Proxy** | `25565` | Minecraft TCP | Default join address for Mineflayer bots |
| **Paper-A RCON** | `25575` | Source RCON | Password: `e2e-rcon-secret` (or from `versions.env`) |
| **Paper-B RCON** | `25576` | Source RCON | Password: `e2e-rcon-secret` (or from `versions.env`) |
| **PostgreSQL** | `5432` | PostgreSQL | DB: `gzmn_worlds`, User: `gzmn`, Pass: `e2e-postgres-not-for-prod` |
| **MinIO S3 API** | `9000` | S3 / HTTP | Key: `gzmn-e2e`, Secret: `gzmn-e2e-secret`, Bucket: `gzmn-worlds` |
| **MinIO Web Console** | `9001` | HTTP Web UI | Accessible in browser for visual S3 bucket inspection |

---

## Agent CLI (`e2e/cli.mjs`)

The CLI is a cross-platform Node.js tool (works on Windows, Linux, and macOS) providing full lifecycle management, testing, and debugging utilities.

You can invoke it via `node e2e/cli.mjs <command>` or via the root `npm run` shortcuts:

### Quick Reference

| Command | npm Shortcut | Description |
| --- | --- | --- |
| `node e2e/cli.mjs up` | `npm run e2e:up` | Starts Docker Compose services and polls until all 5 services pass healthchecks. |
| `node e2e/cli.mjs down` | `npm run e2e:down` | Stops Compose stack and wipes containers and volumes. |
| `node e2e/cli.mjs build` | `npm run e2e:build` | Compiles Gradle shadow jars, downloads pinned server binaries, and stages runtime directories. |
| `node e2e/cli.mjs deploy` | `npm run e2e:deploy` | Fast hot-deploy: copies latest built jars to runtime and restarts server containers without rebuilding compose. |
| `node e2e/cli.mjs reset` | `npm run e2e:reset` | Resets test state: truncates Postgres tables, clears MinIO S3 bucket, and cleans temporary world directories. |
| `node e2e/cli.mjs status` | `npm run e2e:status` | Probes TCP, RCON, PostgreSQL, and MinIO S3, printing an ASCII health table. |
| `node e2e/cli.mjs logs [service]` | - | Streams Docker logs for a specific service (e.g. `paper-a`, `velocity`, `postgres`) or all services. |
| `node e2e/cli.mjs rcon <node> <cmd>` | - | Executes an RCON command on `paper-a` or `paper-b` and outputs the server response. |
| `node e2e/cli.mjs sql <query>` | - | Executes a raw SQL query against PostgreSQL and prints the results in a table. |
| `node e2e/cli.mjs s3 <ls\|cat\|rm> ...` | - | Interacts with the MinIO S3 bucket (list objects, read file content, delete objects). |
| `node e2e/cli.mjs test [filter]` | `npm run e2e:test` | Discovers and executes test scenarios in `e2e/scenarios/` matching an optional name filter. |
| `node e2e/cli.mjs run [filter]` | `npm run e2e:run` | Runs full ephemeral pipeline: `build` -> `up` -> `test` -> `down`. |

---

### Command Details & Examples

#### 1. Stack Lifecycle (`up`, `down`, `status`)
```bash
# Start all containers and wait for full readiness
node e2e/cli.mjs up

# Check status of all stack components
node e2e/cli.mjs status

# Tear down the stack and remove volumes
node e2e/cli.mjs down
```

#### 2. Building & Hot Deployment (`build`, `deploy`)
```bash
# Full build: Gradle shadow jars + server jar download + runtime staging
node e2e/cli.mjs build

# Fast iteration: recompile and replace plugin jars on running nodes
node e2e/cli.mjs deploy
```

#### 3. Test State Reset (`reset`)
```bash
# Clean database tables, empty S3 bucket, and remove temp world files
node e2e/cli.mjs reset
```

#### 4. Server & Database Inspection (`rcon`, `sql`, `s3`, `logs`)
```bash
# Send RCON commands to Paper nodes
node e2e/cli.mjs rcon paper-a "e2e ping"
node e2e/cli.mjs rcon paper-a "e2e status"
node e2e/cli.mjs rcon paper-b "list"

# Run PostgreSQL queries
node e2e/cli.mjs sql "SELECT id, name, owner_uuid, state, assigned_node FROM player_world"
node e2e/cli.mjs sql "SELECT * FROM worlds_node"

# Inspect MinIO S3 object storage
node e2e/cli.mjs s3 ls
node e2e/cli.mjs s3 ls test-worlds/
node e2e/cli.mjs s3 cat test-worlds/snapshot-123.tar.gz
node e2e/cli.mjs s3 rm --all

# View container logs
node e2e/cli.mjs logs paper-a
node e2e/cli.mjs logs velocity
```

#### 5. Running Test Scenarios (`test`, `run`)
```bash
# Run all scenarios in e2e/scenarios/
node e2e/cli.mjs test

# Run specific scenarios by filter
node e2e/cli.mjs test 01-lobby
node e2e/cli.mjs test visibility

# Ephemeral full run (build -> up -> test -> down)
node e2e/cli.mjs run

# Ephemeral run keeping containers alive on completion for inspection
E2E_KEEP=1 node e2e/cli.mjs run
```

---

## Directory Layout

```
e2e/
├── README.md               # This documentation file
├── cli.mjs                 # Cross-platform CLI and automation orchestrator
├── compose.yml             # Docker Compose cluster definition
├── package.json            # Node.js dependencies (Mineflayer, pg, aws-sdk)
├── versions.env            # Pinned binary URLs, Docker images, and secrets
├── bot/                    # Bot helper utilities
├── config/                 # Configuration templates
│   ├── paper/              # server.properties, bukkit.yml, paper-global.yml
│   └── velocity/           # velocity.toml
├── harness-plugin/         # Test helper plugin (:e2e-harness) with /e2e RCON commands
├── lib/                    # Node.js testing library
│   ├── bot-session.mjs     # BotSession Mineflayer wrapper with assertions
│   ├── config.mjs          # Configuration parser
│   ├── db-client.mjs       # PostgreSQL client pool & helper methods
│   ├── rcon-client.mjs     # Native TCP Source RCON client
│   ├── s3-client.mjs       # AWS S3 / MinIO helper client
│   ├── test-context.mjs    # TestContext fixture with lifecycle hooks
│   └── test-runner.mjs     # Test discovery and execution engine
├── runtime/                # (Gitignored) Server files, runtime configs, and logs
│   ├── paper-a/
│   ├── paper-b/
│   └── velocity/
├── downloads/              # (Gitignored) Cached Paper and Velocity server jars
└── scenarios/              # Automated E2E test scenarios (*.test.mjs)
    ├── 01-lobby-join.test.mjs
    ├── 02-world-lifecycle.test.mjs
    ├── 03-membership-invites.test.mjs
    ├── 04-visibility-isolation.test.mjs
    ├── 05-inventory-isolation.test.mjs
    ├── 06-s3-persistence.test.mjs
    └── 07-multi-node-routing.test.mjs
```

---

## Test Scenarios Overview

| Scenario | File | Verifies |
| --- | --- | --- |
| **01: Lobby Join** | `01-lobby-join.test.mjs` | Multi-bot join through Velocity to Paper-A lobby, tablist presence, RCON status. |
| **02: World Lifecycle** | `02-world-lifecycle.test.mjs` | `/world create`, DB record creation in `player_world`, UUID validation, `/world delete` confirmation. |
| **03: Membership & Invites** | `03-membership-invites.test.mjs` | `/world invite`, invite notifications, `/world accept`, `player_world_member` rows, unauthorized join refusal. |
| **04: Visibility Isolation** | `04-visibility-isolation.test.mjs` | Tablist isolation between private world players and lobby players (`FR-21` to `FR-24`). |
| **05: Inventory Isolation** | `05-inventory-isolation.test.mjs` | Inventory item isolation and profile persistence in `player_world_profile`. |
| **06: S3 Persistence** | `06-s3-persistence.test.mjs` | MinIO bucket health, object upload, listing, content verification, and deletion. |
| **07: Multi-Node Routing** | `07-multi-node-routing.test.mjs` | Dual Paper node RCON responsiveness, `worlds_node` cluster registry, lease checks. |

---

## Protocol Spoofing & Version Pinning

- **Minecraft Version:** Paper `26.2` (Protocol `776`).
- **Client Protocol:** `minecraft-data` currently ships packet schemas up to Minecraft `26.1` (Protocol `775`).
- **Handshake Spoofing:** `BotSession` intercepts outgoing handshake packets and sends Protocol `776` while decoding using `26.1` packet definitions. This allows Mineflayer bots to join Paper 26.2 servers without waiting for upstream schema releases.
- When an updated `minecraft-data` release supporting `26.2` becomes available, update `E2E_BOT_MC_VERSION=26.2` in [`e2e/versions.env`](versions.env).
