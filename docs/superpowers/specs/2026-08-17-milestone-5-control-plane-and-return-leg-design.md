# Design: Milestone 5 Control Plane & Return Leg

## Overview
Completes the outstanding deliverables of Milestone 5 from the DynamicPlayerWorlds specification (§11.5, §13, FR-10–13, FR-27, CP-1–7):
1. **Control-Plane Subscription & Handlers on Backend Nodes:** Wire `ControlPlane` consumer on Paper nodes with idempotent handlers for `UNLOAD_WORLD`, `INVALIDATE_CACHE`, `KICK_MEMBER`, and `EJECT_PLAYER`.
2. **Proxy Control-Plane Runtime & Command Emission:** Wire `ControlPlane` consumer on Velocity proxy for `EJECT_PLAYER` (lobby routing), and emit control-plane commands upon `/world delete`, `/world kick`, and `/world promote`.
3. **The Return Leg & `/world leave` (FR-11, FR-12, OQ-15):** Forward `/world leave` to the backend, safely handle leaving/ejection flows, and return players to the lobby during handoff refusals.

---

## Non-Negotiable Architectural Rules
All components adhere to repository safety and quality gates:
1. **No server internals:** Strictly use Paper/Bukkit public API and Velocity API; no reflection or internal imports (`forbidden-apis`).
2. **`:core` independence:** `:core` remains pure Java and JDBC with zero Bukkit or Velocity dependencies.
3. **No blocking work on main thread (NFR-2):** Database transactions, queries, LISTEN loops, and filesystem checks run on daemon executors / `PluginExecutors`; only dimension unloads and Bukkit player actions hop to the main thread.
4. **Never cache `World` references across unloads (FR-25b):** Bukkit worlds are resolved on-demand by name at use time.
5. **Database clock is source of truth:** Timeouts and expirations rely on PostgreSQL timestamps.
6. **Immutable migrations:** Uses existing baseline V1 tables (`node_command`, `player_world`, `pending_transfer`) without altering merged migrations.
7. **Idempotency (Rule 7, CP-5):** All command handlers are idempotent and safe to retry.
8. **Verify before destroy (Rule 8):** Deletion archives world state and unloads memory instances safely without raw disk removal.

---

## Technical Architecture & Component Design

### 1. Backend Node Control Plane (`:backend`)

#### Lifecycle & Initialization
- In `GzmnWorldsPlugin.onEnable()`:
  - Create a single-thread daemon `listenExecutor` for the blocking PostgreSQL `LISTEN` loop.
  - Instantiate `ControlPlane.forNode(node.nodeId(), databaseSettings, nodeCommandsRepo, pollInterval, claimTimeout)`.
  - Register command handlers:
    - `UNLOAD_WORLD`
    - `INVALIDATE_CACHE`
    - `KICK_MEMBER`
    - `EJECT_PLAYER`
  - Start control plane: `controlPlane.start(pools.sched(), listenExecutor)`.
- In `GzmnWorldsPlugin.onDisable()`:
  - Call `controlPlane.close()` to stop polling, disconnect `LISTEN`, and join worker threads.

#### Backend Command Handlers (CP-5, CP-6)
1. **`UNLOAD_WORLD`:**
   - Checks `worldRegistry.find(worldId)`. If not loaded on this node, returns `CommandResult.ok()` (idempotent).
   - If loaded, hops to `executors.main()`:
     - For any online players standing in any dimension of the world:
       - Displays `"World is unloading..."`.
       - Teleports them to the node's holding area.
       - Enqueues `EJECT_PLAYER` to `ControlChannels.PROXY` to return them to the lobby server.
     - Unloads dimensions in FR-25a order (`END -> NETHER -> OVERWORLD`) via `lifecycle.unloadOnMain(loaded)`.
     - Finalizes unload and deregisters world via `lifecycle.afterUnload(loaded)`.
   - Returns `CommandResult.ok()`.

2. **`INVALIDATE_CACHE`:**
   - Calls `networkSettings.invalidate()` and reloads network policy.
   - If `command.worldId()` is present: `membershipCache.invalidate(worldId)`.
   - If `command.worldId()` is null: `membershipCache.clear()`.
   - Returns `CommandResult.ok()`.

3. **`KICK_MEMBER` / `EJECT_PLAYER`:**
   - Reads target player UUID and optional reason from `command.payloadJson()`.
   - If `command.worldId()` is present:
     - Calls `membershipCache.invalidate(worldId)`.
     - For all online players in that world on this node: if UUID matches target player, moves them to holding area, informs them of removal, and enqueues `EJECT_PLAYER` to `ControlChannels.PROXY`.
   - Returns `CommandResult.ok()`.

---

### 2. Velocity Proxy Control Plane (`:proxy`)

#### Lifecycle & Initialization
- In `GzmnWorldsProxyPlugin.onProxyInitialize()`:
  - Create single-thread daemon `listenExecutor`.
  - Instantiate `ControlPlane.forProxy(config.lobbyServer(), databaseSettings, nodeCommandsRepo, pollInterval, claimTimeout)` listening on `ControlChannels.PROXY`.
  - Register `EJECT_PLAYER` handler:
    - Parses player UUID from payload.
    - Resolves connected `Player` on proxy and configured `lobbyServer` from `NodeRegistry`.
    - Dispatches `player.createConnectionRequest(lobbyServer).fireAndForget()`.
    - Returns `CommandResult.ok()`.
  - Start control plane: `controlPlane.start(pools.sched(), listenExecutor)`.
- In `GzmnWorldsProxyPlugin.onProxyShutdown()`:
  - Call `controlPlane.close()`.

#### Proxy Command Emission (Proxy → Node)
- In `WorldCommand.java`:
  - **`/world delete <name> confirm` (FR-27):**
    - Transitions world state to `ARCHIVED`.
    - If `world.assignedNode()` is present, enqueues `UNLOAD_WORLD` targeted at that node.
    - If `world.assignedNode()` is absent, enqueues `UNLOAD_WORLD` to all alive nodes in `NodeRegistry.aliveNodes(...)`.
  - **`/world kick <player>` & `/world promote <player>`:**
    - Emits `INVALIDATE_CACHE` and `KICK_MEMBER` addressed to the world's node or alive nodes.

---

### 3. The Return Leg & `/world leave` (FR-11, FR-12, OQ-15)

#### Proxy Forwarding
- Update `WorldCommand.BACKEND_SUBCOMMANDS` to include `"leave"`.
- When `/world leave` is executed, the proxy lets the backend handle the command.

#### Backend `/world leave` Handler
- Registered in `PworldCommand` (handling `/pworld leave` and `/world leave` on backend):
  - Validates player is standing in a player world (or holding area).
  - Triggers profile pre-leave snapshot commit hook (FR-15).
  - Teleports player to holding area.
  - Enqueues `EJECT_PLAYER` targeted at `ControlChannels.PROXY` with player UUID.
  - Sends feedback: `"Returning to lobby..."`.

#### FR-11 Refusal Handling in `TransferJoinListener`
- When a join refusal occurs (missing/expired transfer, node mismatch, generation mismatch, world load failure):
  - Informs player of the refusal reason.
  - Enqueues `EJECT_PLAYER` to `ControlChannels.PROXY` to return player to lobby, preventing them from remaining trapped in the holding area.

---

## Testing & Verification Plan

### Automated Tests
1. **Control Plane Unit Tests (`:core`):**
   - Verify `ControlPlane` poll and dispatch mechanisms.
   - Verify `CommandResult` error and idempotency guarantees.
2. **Backend Handler Unit Tests (`:backend`):**
   - Test `UNLOAD_WORLD` handler when world is loaded vs not loaded.
   - Test `INVALIDATE_CACHE` handler updating cache state.
   - Test `KICK_MEMBER` / `EJECT_PLAYER` handler ejecting targeted player.
3. **Proxy Handler Unit Tests (`:proxy`):**
   - Test proxy `EJECT_PLAYER` handler successfully requesting connection to lobby.
4. **Build & Architecture Verification:**
   - `./gradlew check` (enforces ArchUnit rules, Checkstyle/Spotless, forbidden-apis, licensee, shaded jar validation).
   - `./gradlew build`
