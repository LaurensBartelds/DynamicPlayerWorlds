# Milestone 10: Ownership Transfer Design Spec

## Overview
This specification details the technical design for **Milestone 10: Ownership Transfer** in `DynamicPlayerWorlds`, covering requirements **FR-29**, **FR-30**, **FR-31**, **FR-31a**, **FR-32**, and **FR-33** as well as section 4 data model and section 6 commands.

Ownership transfer allows an owner to safely transfer full ownership of a player world to an existing member. The transfer guarantees atomicity, updates role denormalizations, maintains full audit logs in `player_world_ownership_log`, respects per-player world capacity limits, and handles offline players gracefully via pending transfer requests in `player_world_transfer_request`.

---

## 1. Requirements & Core Constraints

1. **FR-29**: The owner may initiate a transfer with `/world transfer <player>`. It requires:
   - Target player is already a member of the world (`player_world_member`).
   - Target player is not already the owner.
   - Typed confirmation (`/world transfer <player> confirm`) to avoid accidental transfer.
2. **FR-30**: Transfer must check the target player's owned world count (`countOwnedBy(target)` excluding `ARCHIVED` worlds) against `worlds.max-per-player` (via `NetworkPolicy.maxWorldsPerPlayer()`). If at or over cap, the transfer is refused.
3. **FR-31 / FR-31a**:
   - `player_world.owner_uuid` is the authoritative source of ownership.
   - Ownership change is performed in a single database transaction:
     - `UPDATE player_world SET owner_uuid = :newOwner WHERE id = :worldId AND owner_uuid = :oldOwner`
     - `UPDATE player_world_member SET role = 'BUILDER' WHERE world_id = :worldId AND uuid = :oldOwner`
     - `UPDATE player_world_member SET role = 'OWNER' WHERE world_id = :worldId AND uuid = :newOwner`
     - `INSERT INTO player_world_ownership_log (world_id, from_uuid, to_uuid, reason) VALUES (...)`
     - `DELETE FROM player_world_transfer_request WHERE world_id = :worldId AND to_uuid = :newOwner`
4. **FR-32**:
   - If the target is **offline** when the owner confirms the transfer, a transfer request is stored in `player_world_transfer_request` with an expiration (`transfers.pending-expiry-days`, default 7 days).
   - On login (`PostLoginEvent` on the proxy), the proxy checks for live transfer requests for the connecting player and sends an informative reminder.
   - The target can accept with `/world transfer accept <owner>` or decline with `/world transfer decline <owner>`.
   - Acceptance re-checks the target player's world cap at the moment of acceptance (since they may have created or acquired other worlds in the interim).
5. **FR-33**:
   - Admins holding `gzmn.worlds.admin` may force a transfer with `/world admin transfer <id> <player>`.
   - Reason is recorded as `ADMIN`.
   - If the target is not already a member, the target is added as a member prior to/during transfer.
   - Cap check is evaluated.
6. **Control Plane Notification**:
   - When ownership changes, `CommandKind.INVALIDATE_CACHE` is enqueued to the world's holding node (or all alive nodes if unassigned) so cached roles are reloaded.

---

## 2. Architecture & Components

```
                   ┌────────────────────────────────────────────────────────┐
                   │                     Velocity Proxy                     │
                   │                                                        │
                   │  /world transfer <player> [confirm]                    │
                   │  /world transfer accept <owner>                        │
                   │  /world transfer decline <owner>                       │
                   │  /world admin transfer <id> <player>                   │
                   │  PostLoginEvent -> TransferRequest notification        │
                   └───────────┬────────────────────────────────┬───────────┘
                               │                                │
                     JDBC / HikariPool               pg_notify(gzmn_node_*)
                               │                                │
                               ▼                                ▼
                   ┌───────────────────────┐        ┌───────────────────────┐
                   │      PostgreSQL       │        │     Paper Backend     │
                   │                       │        │                       │
                   │ player_world          │        │ RoleEnforcementListener│
                   │ player_world_member   │◄───────┤ (invalidates cache)   │
                   │ player_world_trans... │        └───────────────────────┘
                   │ player_world_owner... │
                   └───────────────────────┘
```

---

## 3. Data Model & Repositories (`:core`)

### 3.1 New Models
- `nl.gzmn.playerworlds.core.model.TransferRequest`:
  ```java
  public record TransferRequest(
      WorldId worldId,
      UUID toUuid,
      UUID fromUuid,
      Instant expiresAt,
      Instant createdAt
  )
  ```
- `nl.gzmn.playerworlds.core.model.OwnershipLogEntry`:
  ```java
  public record OwnershipLogEntry(
      long id,
      WorldId worldId,
      UUID fromUuid,
      UUID toUuid,
      String reason,
      Instant transferredAt
  )
  ```

### 3.2 `TransferRequestRepository`
A new repository in `nl.gzmn.playerworlds.core.db`:
- `TransferRequest requestTransfer(WorldId worldId, UUID toUuid, UUID fromUuid, Duration expiry)`:
  Inserts or updates `player_world_transfer_request` with `expires_at = now() + expiry`.
- `Optional<TransferRequest> findLiveRequest(WorldId worldId, UUID toUuid)`:
  Selects where `world_id = ? AND to_uuid = ? AND expires_at > now()`.
- `List<TransferRequest> findLiveRequestsFor(UUID toUuid)`:
  Selects where `to_uuid = ? AND expires_at > now() ORDER BY created_at DESC`.
- `boolean deleteRequest(WorldId worldId, UUID toUuid)`:
  Deletes request matching world and target.
- `int deleteExpired(Duration expiry)`:
  Deletes rows where `expires_at <= now()`.

### 3.3 `PlayerWorldRepository` Extension
Adds atomic ownership transfer:
- `boolean transferOwnership(WorldId worldId, UUID oldOwnerUuid, UUID newOwnerUuid, String reason)`:
  Executes in a single database transaction:
  1. `UPDATE player_world SET owner_uuid = ? WHERE id = ? AND owner_uuid = ?`
  2. Ensures `newOwnerUuid` exists in `player_world_member`, updating role to `OWNER`.
  3. Updates `oldOwnerUuid` in `player_world_member` to `BUILDER`.
  4. Inserts into `player_world_ownership_log (world_id, from_uuid, to_uuid, reason) VALUES (?, ?, ?, ?)`.
  5. Deletes matching row from `player_world_transfer_request WHERE world_id = ? AND to_uuid = ?`.
  Returns `true` if all statements succeed.

---

## 4. Proxy Commands & Listeners (`:proxy`)

### 4.1 `/world transfer` Subcommands
- `/world transfer <player>`:
  - Validates caller owns a world (via `soleOwnedWorld`).
  - Resolves target UUID.
  - Verifies target is not caller.
  - Verifies target is a member of the world (FR-29).
  - Checks target world cap (FR-30).
  - Prompts with:
    `"Are you sure you want to transfer ownership of '<name>' to <player>? You will become a BUILDER. Type /world transfer <player> confirm to proceed."`
- `/world transfer <player> confirm`:
  - Re-evaluates member & cap checks.
  - If target is **online**:
    - Executes `transferOwnership(worldId, callerUuid, targetUuid, "MANUAL")`.
    - Enqueues `INVALIDATE_CACHE` via `NodeCommandRepository`.
    - Sends success messages to caller and target player.
  - If target is **offline**:
    - Calls `transferRequests.requestTransfer(worldId, targetUuid, callerUuid, policy.transferPendingExpiry())`.
    - Sends message to caller: `"Created transfer request for <player>. They can accept it next time they log in."`
- `/world transfer accept <owner>`:
  - Resolves owner UUID.
  - Finds caller's live transfer requests from that owner.
  - Checks caller's world cap (`countOwnedBy(caller) < policy.maxWorldsPerPlayer()`).
  - Executes `transferOwnership(worldId, ownerUuid, callerUuid, "MANUAL")`.
  - Enqueues `INVALIDATE_CACHE`.
  - Notifies caller and original owner (if online).
- `/world transfer decline <owner>`:
  - Resolves owner UUID.
  - Finds and removes live transfer request for caller.
  - Confirms cancellation to caller and notifies owner if online.

### 4.2 `/world admin transfer <id> <player>`
- Requires `ADMIN_PERMISSION` (`gzmn.worlds.admin`).
- Parses `WorldId`.
- Resolves target player UUID.
- Checks target world cap.
- Ensures target is a member (inserting as member if absent).
- Executes `transferOwnership(worldId, currentOwnerUuid, targetUuid, "ADMIN")`.
- Enqueues `INVALIDATE_CACHE`.
- Sends success feedback to admin and parties involved.

### 4.3 `PostLoginEvent` Reminder
- When a player connects, proxy asynchronously queries `transferRequests.findLiveRequestsFor(player.getUniqueId())`.
- If non-empty, sends message:
  `"You have pending world transfer requests! Use /world transfer accept <owner> to claim ownership."`

---

## 5. Verification Plan

### Automated Tests
1. **Core Unit & Integration Tests**:
   - `TransferRequestRepositoryTest`:
     - Test request creation, live lookup, query filtering on expiration, deletion, expired cleanup.
   - `PlayerWorldRepositoryTest`:
     - Test `transferOwnership` happy path (MANUAL & ADMIN reasons).
     - Test role swaps: old owner becomes BUILDER, new owner becomes OWNER.
     - Test `player_world_ownership_log` row insertion.
     - Test atomic rollback on constraint violation.
     - Test cap calculation after transfer.
2. **Proxy Command Tests (`WorldCommandTest`)**:
   - Test `/world transfer <player>` confirmation gate.
   - Test `/world transfer <player> confirm` with online target (immediate transfer).
   - Test `/world transfer <player> confirm` with offline target (request creation).
   - Test `/world transfer <player> confirm` exceeding cap (FR-30 refusal).
   - Test `/world transfer <player> confirm` non-member (FR-29 refusal).
   - Test `/world transfer accept <owner>` happy path & cap check at acceptance (FR-32).
   - Test `/world transfer decline <owner>`.
   - Test `/world admin transfer <id> <player>` with reason `ADMIN` (FR-33).
3. **Full Build & Verification**:
   - `./gradlew check build` (all static analysis, NullAway, forbidden-apis, and unit tests).
