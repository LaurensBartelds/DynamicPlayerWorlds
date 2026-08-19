# DynamicPlayerWorlds Local E2E Testing Environment & Agent Framework

**Author:** Antigravity  
**Date:** 2026-08-19  
**Status:** Approved  
**Target:** Local Development & Autonomous AI Agent Feature Testing

---

## 1. Executive Summary

This document specifies the architecture and tooling for a local Docker-based end-to-end (E2E) testing environment for `DynamicPlayerWorlds`. The environment enables AI agents and human developers to execute automated multi-player scenarios, test plugin features across Paper nodes and Velocity proxies, verify database leases in a real PostgreSQL instance, inspect S3 object storage in MinIO, and execute fast ad-hoc debugging commands natively on Windows (PowerShell) and Linux CI.

---

## 2. Infrastructure Architecture (Docker Compose)

The environment runs real, production-equivalent components in containerized isolation.

### 2.1 Service Specifications

```mermaid
graph TD
    Agent["AI Agent / Host Tooling"] -->|Port 25565 (Minecraft)| Velocity["Velocity 4 Proxy (gzmn-worlds-proxy)"]
    Agent -->|Port 5432 (SQL)| Postgres[("PostgreSQL 18.3 (gzmn_worlds)")]
    Agent -->|Port 9000 (S3 API) / 9001 (Console)| MinIO[("MinIO S3 (gzmn-worlds bucket)")]
    Agent -->|Port 25575 (RCON)| PaperA["Paper 26.2 Node A (Lobby / Worlds)"]
    Agent -->|Port 25576 (RCON)| PaperB["Paper 26.2 Node B (Worlds)"]

    Velocity --> PaperA
    Velocity --> PaperB
    PaperA --> Postgres
    PaperA --> MinIO
    PaperB --> Postgres
    PaperB --> MinIO
```

| Service | Image / Base | Host Port | Internal Port | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `postgres` | `postgres:18.3` | `5432` | `5432` | Authoritative database for player worlds, leases, memberships, and network policy. |
| `minio` | `minio/minio:RELEASE.2025-04-22T22-12-26Z` | `9000` (API)<br>`9001` (Web) | `9000`<br>`9001` | S3-compatible world storage source of truth. |
| `minio-init` | `minio/mc:RELEASE.2025-04-16T18-13-26Z` | — | — | One-shot container creating the `gzmn-worlds` bucket on stack startup. |
| `paper-a` | `eclipse-temurin:25-jre` | `25575` (RCON) | `25565` (MC)<br>`25575` (RCON) | Primary backend node (Lobby + Player Worlds). Staged at `e2e/runtime/paper-a`. |
| `paper-b` | `eclipse-temurin:25-jre` | `25576` (RCON) | `25565` (MC)<br>`25575` (RCON) | Secondary backend node for multi-node migration testing. Staged at `e2e/runtime/paper-b`. |
| `velocity` | `eclipse-temurin:25-jre` | `25565` (Proxy) | `25565` | Network gateway proxy. Handles modern player forwarding to Paper nodes. |

### 2.2 Host Port Mappings & Network Topology
* Network: Dedicated Docker bridge network `gzmn-e2e`.
* All host ports are bound to `127.0.0.1` so host tools (Node.js test runner, psql, AWS SDK, Mineflayer bots) can connect directly.

---

## 3. Agent CLI & Developer Tooling (`e2e/cli.mjs`)

A cross-platform Node.js CLI script provides a unified interface for both AI agents and developers.

### 3.1 Command Reference

* `npm run e2e -- up` (or `node e2e/cli.mjs up`):
  Starts PostgreSQL, MinIO, Paper-A, Paper-B, and Velocity in the background. Waits for all readiness healthchecks and RCON probes to pass before returning.
* `npm run e2e -- down`:
  Stops and removes all containers gracefully.
* `npm run e2e -- build`:
  Executes `./gradlew build` (or `gradlew.bat build` on Windows) to compile `:core`, `:backend`, `:proxy`, and the harness plugin, staging jars to `e2e/runtime/`.
* `npm run e2e -- deploy`:
  Copies compiled plugin jars into running server directories and executes plugin reloads / container restarts.
* `npm run e2e -- reset`:
  Resets test state without recreating containers: truncates PostgreSQL tables (`TRUNCATE player_world, world_lease, player_profile CASCADE`), flushes the MinIO `gzmn-worlds` bucket, and clears temporary world folders on Paper nodes.
* `npm run e2e -- status`:
  Queries health of all containers, RCON responsiveness, PostgreSQL connectivity, and MinIO readiness. Returns a structured status report.
* `npm run e2e -- logs <service>`:
  Outputs logs for `paper-a`, `paper-b`, `velocity`, `postgres`, or `minio`.
* `npm run e2e -- rcon <paper-a|paper-b> <command>`:
  Sends an RCON command and returns the string response.
* `npm run e2e -- sql <query>`:
  Executes a raw SQL query against PostgreSQL and prints output in formatted JSON or table format.
* `npm run e2e -- s3 <ls|cat|rm> [args]`:
  Interacts with the MinIO S3 bucket (listing keys, reading metadata, or inspecting world zip files).
* `npm run e2e -- test [scenario]`:
  Executes a single test scenario or the entire suite using the Mineflayer bot framework.
* `npm run e2e:run`:
  Complete ephemeral verification: prepares binaries &rarr; starts stack &rarr; runs full test suite &rarr; tears down stack.

---

## 4. Mineflayer Multi-Bot Engine & Assertion Framework

### 4.1 Architecture
The bot framework (`e2e/lib/bot-session.mjs` and `e2e/lib/test-context.mjs`) manages headless Minecraft clients connecting via Velocity.

```
e2e/
├── compose.yml
├── versions.env
├── package.json
├── cli.mjs                  # Main CLI entrypoint
├── lib/
│   ├── bot-session.mjs      # Mineflayer bot wrapper with Promise-based APIs
│   ├── test-context.mjs     # Test fixture with DB, S3, RCON & Bot helpers
│   ├── db-client.mjs        # PostgreSQL helper (pg pool)
│   ├── s3-client.mjs        # MinIO S3 helper (@aws-sdk/client-s3)
│   └── rcon-client.mjs      # Native Node.js RCON client
└── scenarios/
    ├── 01-lobby-join.test.mjs
    ├── 02-world-lifecycle.test.mjs
    ├── 03-membership-invites.test.mjs
    ├── 04-visibility-isolation.test.mjs
    ├── 05-inventory-isolation.test.mjs
    ├── 06-s3-persistence.test.mjs
    └── 07-multi-node-routing.test.mjs
```

### 4.2 `BotSession` API Capabilities
* `connect(options)`: Connects client with protocol version negotiation (spoofing handshake protocol `776` while decoding with compatible schema definitions).
* `waitForSpawn(timeoutMs)`: Awaits player placement and position packets.
* `runCommand(command)`: Sends `/command` via chat packets.
* `waitForChat(pattern, timeoutMs)`: Resolves when a matching chat or system message is received.
* `getTabListPlayers()`: Returns list of visible player usernames on the client's tab list.
* `assertPlayerVisible(username)` / `assertPlayerHidden(username)`: Verifies visibility rules (tab list and entity presence).
* `getInventoryItems()`: Returns parsed inventory slots.
* `waitForInventoryItem(itemName, count, timeoutMs)`: Awaits specific item presence in player inventory.
* `clickWindowSlot(slot, mouseButton, mode)`: Simulates clicking GUI menu items.
* `disconnect()`: Cleanly ends the session.

### 4.3 `TestContext` API Capabilities
* `spawnBot(username, options)`: Creates, registers, and awaits spawn for a named bot.
* `db.query(sql, params)`: Executes SQL query on Postgres.
* `s3.listObjects(prefix)` / `s3.getObject(key)`: Directly inspects MinIO objects.
* `rcon(node, command)`: Sends console commands to Paper nodes.
* `resetState()`: Cleans DB, S3, and server world directories.

---

## 5. Test Scenarios & Feature Verification Plan

1. **`01-lobby-join`**: Single and multi-bot join through Velocity proxy to Paper-A lobby. Verifies handshake, position, tab list, and join markers.
2. **`02-world-lifecycle`**: Player creates a private world (`/world create Adventure1`), verifies database row in `player_world`, validates lease in `world_lease`, and tests world deletion.
3. **`03-membership-invites`**: Alice invites Bob to her private world; Bob receives invitation, joins world, and uninvited Charlie is prevented from joining.
4. **`04-visibility-isolation`**: Verifies that players in private worlds are hidden from lobby players in both tab list and spatial entity tracking.
5. **`05-inventory-isolation`**: Verifies player inventory separation between lobby and distinct private worlds.
6. **`06-s3-persistence`**: Verifies world chunks and metadata are written to MinIO S3 upon world save/unload.
7. **`07-multi-node-routing`**: Verifies dynamic lease handover when moving a player world from `paper-a` to `paper-b`.

---

## 6. Execution Lifecycle Modes

1. **Persistent Mode (Interactive Agent Loop):**
   * Agent runs `node e2e/cli.mjs up` once.
   * Agent edits plugin code &rarr; `node e2e/cli.mjs build` &rarr; `node e2e/cli.mjs test 02-world-lifecycle`.
   * Fast feedback loop without container restart overhead.
2. **Ephemeral Mode (CI / Complete Validation):**
   * Invoked via `npm run e2e:run` or `e2e/scripts/run.sh`.
   * Automatically prepares environment, boots fresh stack, runs all test scenarios, and cleans up containers and volumes.
