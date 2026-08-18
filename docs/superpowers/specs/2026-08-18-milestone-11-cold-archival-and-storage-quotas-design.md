# Milestone 11: Cold Archival & Restore + Player Storage Quotas Design Spec

## Overview
This specification details the technical design for **Milestone 11: Cold Archival & Restore** in `DynamicPlayerWorlds`, covering requirements **FR-34** through **FR-39**, **FR-27**, §5.8, §4 schema, §6 commands, alongside a **Permission-Based Player Storage Quotas Subsystem** allowing dynamic storage tiering (e.g., via LuckPerms).

---

## 1. Requirements & Core Constraints

### 1.1 Cold Archival & Restore Requirements
1. **FR-27**: `/world delete <name>` requires typed confirmation (`/world delete <name> confirm`) and initiates cold archival (FR-35), setting state to `ARCHIVED`.
2. **FR-34**: A world with no logins for `archive.after-days` (default 90) is archived automatically.
3. **FR-35**:
   - Archiving **acquires the lease first** (MN-8) to prevent concurrent loading.
   - Sets `state` to `ARCHIVING`.
   - Unloads world if currently loaded on any node (sending `UNLOAD_WORLD` via control plane).
   - Packs all 3 dimension folders (`<world_id>`, `<world_id>_nether`, `<world_id>_the_end`) or downloads the latest snapshot files from object storage into a single compressed tarball (`.tar.zst` or `.tar.gz`).
   - Writes key, checksum (SHA-256), format, and size in bytes to `player_world_archive`.
   - Sets `state` to `ARCHIVED` and updates `player_world.storage_bytes`.
   - Cleans up live object storage prefix (`worlds/<world_id>/data/` and `manifests/`) only after archive checksum verification succeeds.
   - Releases the lease.
4. **FR-36**:
   - `/world restore <name>` by the owner (or admin) acquires the lease, sets `state` to `RESTORING`.
   - Unpacks archive, verifies SHA-256 checksum.
   - Checks storage quota and Minecraft version compatibility (MN-29).
   - Uploads world files as a fresh live snapshot (MN-6a) and commits the snapshot (MN-3a).
   - Sets `state` to `READY` and releases the lease.
5. **FR-37**: Archives are never hard-deleted automatically. Hard deletion is an admin command with typed confirmation (`/world admin delete <id> confirm`).
6. **FR-40 Crash Recovery**: Stale `ARCHIVING` or `RESTORING` states with expired leases are safely detected and retried by the maintenance loop.

### 1.2 Permission-Based Storage Quotas Requirements
1. **Dynamic Numeric Permissions**:
   - Quotas are resolved dynamically from permissions matching `gzmn.worlds.storage.<amount><unit>` (e.g. `gzmn.worlds.storage.10gb`, `gzmn.worlds.storage.5000mb`, `gzmn.worlds.storage.50gb`).
   - The plugin parses all active matching permissions for the player and selects the **highest** granted byte limit.
   - If no storage permission is present, the player receives `NetworkPolicy.defaultStorageLimitBytes()` (default: 5 GB).
   - Players with `gzmn.worlds.admin` or `gzmn.worlds.storage.unlimited` are exempt from storage quotas.
2. **Storage Accounting**:
   - A player's total used storage is the sum of `storage_bytes` across all worlds they own (`player_world.owner_uuid = ?`):
     - **Live worlds**: Total uncompressed size of files in the active snapshot manifest.
     - **Archived worlds**: Compressed archive file size in `player_world_archive.size_bytes`.
   - `player_world.storage_bytes` is updated on snapshot commit, world creation, and world archival.
3. **Quota Enforcement Gates**:
   - `/world create`: Blocked if $\text{Used Storage} + \text{Default Estimated Initial World Size} > \text{Storage Quota}$.
   - `/world restore <name>`: Blocked if $\text{Used Storage} + (\text{Estimated Unpacked Size} - \text{Archive Size}) > \text{Storage Quota}$.
   - `/world transfer accept <owner>`: Blocked if accepting the world's `storage_bytes` would put the new owner over their quota.
   - Existing live worlds remain joinable and playable so owners can clean up or archive worlds.
4. **Storage UI & Commands**:
   - `/world storage`: Displays total used, quota limit, percentage progress bar, and a breakdown per owned world (name, state, size).
   - `/world list`: Includes player's storage summary in the header or footer.
   - `/world info <name>`: Displays the world's individual storage footprint and state.
   - `/world admin storage <player>`: Allows admins to view another player's quota and world footprint.

---

## 2. Architecture & Components

```
                      ┌────────────────────────────────────────────────────────┐
                      │                     Velocity Proxy                     │
                      │                                                        │
                      │  /world storage                                        │
                      │  /world restore <name>                                 │
                      │  /world delete <name> [confirm] -> Archival trigger    │
                      │  /world admin archive <id>                             │
                      │  /world admin restore <id> [player]                    │
                      │  /world admin delete <id> confirm                      │
                      │  /world admin storage <player>                         │
                      │  Permission Resolver: gzmn.worlds.storage.<size>       │
                      └───────────┬────────────────────────────────┬───────────┘
                                  │                                │
                        JDBC / HikariPool               NodeCommand (CONTROL)
                                  │                                │
                                  ▼                                ▼
                      ┌───────────────────────┐        ┌───────────────────────┐
                      │      PostgreSQL       │        │     Paper Backend     │
                      │                       │        │                       │
                      │ player_world          │        │ WorldArchiver         │
                      │   - storage_bytes     │        │ WorldRestorer         │
                      │ player_world_archive  │◄───────┤ MaintenanceTask       │
                      │   - archive_key       │        │ ObjectStorageClient   │
                      │   - checksum          │        └───────────────────────┘
                      │   - size_bytes        │
                      └───────────────────────┘
```

---

## 3. Data Models & Repositories (`:core`)

### 3.1 Database Migrations (`V3__storage_and_archive.sql`)
1. **`player_world` update**:
   ```sql
   ALTER TABLE player_world ADD COLUMN storage_bytes BIGINT NOT NULL DEFAULT 0;
   ```
2. **`player_world_archive` table**:
   ```sql
   CREATE TABLE player_world_archive (
       id BIGSERIAL PRIMARY KEY,
       world_id UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
       archive_key TEXT NOT NULL,
       checksum TEXT NOT NULL,
       size_bytes BIGINT NOT NULL,
       format TEXT NOT NULL DEFAULT 'tar.zst',
       created_at TIMESTAMPTZ NOT NULL DEFAULT now()
   );
   CREATE INDEX idx_player_world_archive_world ON player_world_archive(world_id);
   ```

### 3.2 Core Models
- `nl.gzmn.playerworlds.core.model.WorldArchive`:
  ```java
  public record WorldArchive(
      long id,
      WorldId worldId,
      String archiveKey,
      String checksum,
      long sizeBytes,
      String format,
      Instant createdAt
  ) {}
  ```
- `nl.gzmn.playerworlds.core.model.StorageQuota`:
  ```java
  public record StorageQuota(
      UUID playerUuid,
      long usedBytes,
      long limitBytes,
      boolean unlimited
  ) {
      public boolean isExceeded() {
          return !unlimited && usedBytes >= limitBytes;
      }
      public double percentage() {
          if (unlimited || limitBytes == 0) return 0.0;
          return Math.min(100.0, (usedBytes * 100.0) / limitBytes);
      }
  }
  ```

### 3.3 `ArchiveRepository`
New repository in `nl.gzmn.playerworlds.core.db`:
- `WorldArchive createArchive(WorldId worldId, String archiveKey, String checksum, long sizeBytes, String format)`
- `Optional<WorldArchive> findLatestByWorld(WorldId worldId)`
- `List<WorldArchive> findAllByWorld(WorldId worldId)`
- `boolean deleteArchive(long id)`

### 3.4 `PlayerWorldRepository` Extension
- `long totalStorageUsedBy(UUID ownerUuid)`:
  ```sql
  SELECT COALESCE(SUM(storage_bytes), 0) FROM player_world WHERE owner_uuid = ?;
  ```
- `boolean updateStorageBytes(WorldId worldId, long storageBytes)`:
  Updates `storage_bytes` for a given world.
- `boolean transitionToArchived(WorldId worldId, String archiveKey, String checksum, long sizeBytes, String format)`:
  In a single transaction:
  1. Inserts into `player_world_archive`.
  2. Updates `player_world SET state = 'ARCHIVED', storage_bytes = ?, manifest_key = NULL, assigned_node = NULL, lease_holder = NULL, lease_expires = NULL WHERE id = ?`.

---

## 4. Storage Quota Subsystem & Permission Resolution

### 4.1 Permission Parsing (`StorageQuotaResolver`)
- Scans player permission subjects for `gzmn.worlds.storage.*`.
- Parsing rules:
  - Regex: `^gzmn\.worlds\.storage\.(\d+)(b|kb|mb|gb|tb)?$` (case-insensitive).
  - Units: `B` (1), `KB` (1024), `MB` ($1024^2$), `GB` ($1024^3$), `TB` ($1024^4$). Unspecified unit defaults to MB.
  - Special permission: `gzmn.worlds.storage.unlimited` or `gzmn.worlds.admin` sets `unlimited = true`.
- Evaluates the highest parsed limit.
- If no matching permission exists, defaults to `policy.defaultStorageLimitBytes()`.

---

## 5. Cold Archival & Restore Subsystem (`:backend` & `:core`)

### 5.1 Archive Packaging (`ArchivePacker`)
- Compresses directory tree containing overworld, nether, and end dimensions into `.tar.zst` (or `.tar.gz` fallback).
- Computes SHA-256 checksum during streaming write.
- Restores by validating SHA-256 checksum, unpacking tarball into target directory scratch area.

### 5.2 Storage Backend (`ObjectStorageClient` / `FileArchiveStorage`)
- Storage key layout: `worlds/<world_id>/archive/<world_id>-<timestamp>.tar.zst`.
- If S3 is configured, streams archive to S3 bucket.
- If S3 is not configured, saves to configured local archive directory `archive.local-path`.

### 5.3 Maintenance Recovery (`MaintenanceTask` in FR-40)
- Scans `player_world` for rows in `ARCHIVING` or `RESTORING` state where `lease_expires < now()`.
- If `ARCHIVING`: World was not successfully archived; re-triggers archival or resets to `READY`.
- If `RESTORING`: World restore interrupted; archive remains safe in `player_world_archive`; resets to `ARCHIVED` for user retry.
- Inactivity Archival: Scans for active worlds where `last_played < now() - interval '90 days'` and schedules archival.

---

## 6. Commands & User Interface (`:proxy`)

### 6.1 Player Commands
- `/world storage`:
  - Shows total storage used vs limit with formatted units (e.g. `2.4 GB / 10.0 GB [|||||.....] 24%`).
  - Lists each owned world: `[Name] - [Size] - [Live / Archived]`.
- `/world restore <name>`:
  - Validates caller owns archived world `<name>`.
  - Checks if restoring would exceed player's storage quota.
  - Checks node version compatibility (MN-29).
  - Enqueues `RESTORE_WORLD` to available node.
- `/world delete <name>` & `/world delete <name> confirm`:
  - Initiates archival workflow (FR-27 / FR-35), setting state to `ARCHIVED`.

### 6.2 Admin Commands
- `/world admin storage <player>`: Inspects target player's storage quota and worlds.
- `/world admin archive <id>`: Force-archives a world.
- `/world admin restore <id> [target_player]`: Restores an archived world (optionally reassigning owner).
- `/world admin delete <id> confirm`: Permanent hard deletion from database and storage backend.

---

## 7. Verification Plan

### 7.1 Automated Unit & Integration Tests
1. **Core Database Tests**:
   - `ArchiveRepositoryTest`: Archive creation, lookup, deletion, cascade behavior.
   - `PlayerWorldRepositoryTest`: `totalStorageUsedBy`, `updateStorageBytes`, atomic `transitionToArchived`.
2. **Core Storage & Packaging Tests**:
   - `StorageQuotaResolverTest`: Parsing various numeric units (`10gb`, `5000mb`, `1tb`), selecting maximum, handling unlimited and default fallbacks.
   - `ArchivePackerTest`: Tarball packing and unpacking, SHA-256 checksum verification, dimension folder structure.
3. **Backend Service Tests**:
   - `WorldArchiverTest`: Archival lifecycle, lease validation, live data cleanup after verification.
   - `WorldRestorerTest`: Restore lifecycle, version check, quota check, fresh snapshot creation.
   - `MaintenanceTaskTest`: Inactivity detection, stale lease recovery for `ARCHIVING` and `RESTORING`.
4. **Proxy Command Tests (`WorldCommandTest`)**:
   - `/world storage` display and per-world breakdown.
   - `/world create` quota refusal when over limit.
   - `/world restore` happy path and quota refusal.
   - `/world delete` archival transition.
   - `/world transfer accept` quota check for recipient.
   - `/world admin storage`, `/world admin archive`, `/world admin restore`, `/world admin delete`.
5. **Whole Project Verification**:
   - `./gradlew check build` passing 100% clean across all modules.
