# Design: Milestone 6 Object Storage, Manifests & Snapshot Commits

## Overview
Implements Milestone 6 of the DynamicPlayerWorlds specification (§11.6, §12.2, MN-1–7, MN-2a/b/c, MN-3/3a, MN-4, MN-5a/b/c, MN-6/6a, NFR-1, NFR-3, NFR-7, NFR-8, NFR-9):
1. **Immutable, Content-Addressed Object Storage (`:core`):** Store world data files at `worlds/<world_id>/data/<sha256>` and write-once snapshot manifests at `worlds/<world_id>/manifest/<generation>-<sequence>.json`.
2. **Deterministic Manifests & Diff Engine (`:core`):** JSON manifest format mapping logical paths to hash, size, and mtime. Size + mtime stat walk (OQ-13) against the last committed manifest to discover dirty files.
3. **Quiesce, Snapshot, Verify Pipeline (`:backend` & `:core`):** Quiesce auto-save on Bukkit dimensions, clone dirty files via `SnapshotCopier` with re-stat retry, validate `.mca` structure during hashing (MN-5c), and upload missing objects to S3.
4. **Single-Transaction Durability Point (MN-3a & FR-15b):** Atomically update `player_world.manifest_key` and write all player profiles in `player_world_profile` in one PostgreSQL transaction.
5. **Cold/Warm Materialization & Local Object Cache (`:core` & `:backend`):** Reconstruct world directories from S3/cache on load, measure warm vs cold metrics (NFR-1), and ensure survivability against scratch directory wipe (NFR-9).
6. **Periodic Incremental Sync & Lifecycle Integration:** Scheduled sync every `storage.sync-minutes` (MN-6), initial snapshot on `/world create` (FR-1a), and shutdown snapshot commit (FR-28).

---

## Non-Negotiable Architectural Rules
1. **No server internals:** Strictly use public Paper/Bukkit API and Velocity API; no reflection or internal imports (`forbidden-apis`).
2. **`:core` independence:** `:core` remains pure Java, JDBC, and AWS SDK S3 (via JDK `UrlConnectionHttpClient`) with zero Bukkit or Velocity dependencies.
3. **No blocking work on main thread (NFR-2, NFR-7):** S3 network transfers, hashing, directory stat walks, and database transactions execute on daemon pools (`PluginExecutors.io()` and `db()`); only Bukkit dimension mounting and `World#save()` hop to the main thread.
4. **Never cache `World` references across unloads (FR-25b):** Bukkit worlds are resolved on-demand by name at point of use.
5. **Database clock is source of truth:** Timestamp comparisons rely on PostgreSQL `now()`.
6. **Immutable migrations:** Uses existing baseline V1 tables (`player_world`, `player_world_profile`) without modifying merged migrations.
7. **Verify before destroy (MN-5a, MN-5c, NFR-9):** Verify region structure on hashing; never upload corrupt data; wipe local working copies only after verifying durability.

---

## Technical Architecture & Component Design

### 1. Storage Primitives & Manifest Engine (`:core`)

#### Manifest Data Model & Codec (`nl.gzmn.playerworlds.core.storage`)
- `ManifestEntry`:
  ```java
  public record ManifestEntry(
      String path,
      String sha256Hex,
      long sizeBytes,
      long lastModifiedMillis)
  ```
- `Manifest`:
  ```java
  public record Manifest(
      WorldId worldId,
      long generation,
      int sequence,
      int dataVersion,
      String mcVersion,
      Instant createdAt,
      Map<String, ManifestEntry> entries)
  ```
- `ManifestCodec`:
  - Serializes `Manifest` into compact, deterministic JSON with entries sorted by logical path.
  - Parses JSON into `Manifest` instance, validating required fields and SHA-256 formatting.

#### S3 Object Store Client (`S3ObjectStore`)
- Implements `ObjectStore` interface:
  - `void putObject(String key, Path sourceFile)`
  - `void putBytes(String key, byte[] bytes, String contentType)`
  - `void getObject(String key, Path destinationFile)`
  - `byte[] getBytes(String key)`
  - `boolean exists(String key)`
- Built using `software.amazon.awssdk.services.s3.S3Client` with `UrlConnectionHttpClient` and path-style access.
- Uploads data objects to `worlds/<world_id>/data/<sha256>` and manifests to `worlds/<world_id>/manifest/<generation>-<sequence>.json`.

#### Local Object Cache (`LocalObjectCache`)
- Stores cached data files by hash at `<cachePath>/<sha256>`.
- Content is immutable: files are created read-only or written once via temp file rename.
- When materializing into scratch directory: uses `FileCloner` (reflink / copy) — **never hard links** — ensuring server mutations cannot modify cache files.
- `evictLru(long maxBytes)`: Sweeps cache directory when size exceeds `storage.local-cache-max-gb`, deleting oldest files by last access time.

#### World Downloader (`WorldDownloader`)
- Materializes or updates a local world folder to match a target `Manifest`:
  1. Compares local scratch files against manifest entries.
  2. For missing or altered files: copies from `LocalObjectCache` or fetches from S3 into cache, then clones to scratch.
  3. Sets file `lastModifiedTime` to match manifest mtime.
  4. Removes extraneous files in the world folder not tracked by the manifest.
  5. Returns `DownloadResult(int filesRestored, long bytesDownloaded, boolean wasWarm)`.

#### Dirty Scanner & Snapshot Engine (`SnapshotEngine`)
- Compares live world folder against baseline `Manifest` (or empty manifest for initial commit) using `FileFingerprint` (OQ-13).
- Excludes files matching `NetworkPolicy.excludeGlobs` (`session.lock`, `uid.dat`).
- Coordinates with `SnapshotCopier` to copy dirty files to `.snapshot-<worldId>-<uuid>` directory on the same filesystem.
- Runs `ContentHasher.hashAndValidate(path, verifyRegionStructure)` (MN-5c) for all copied files.
- Uploads new data objects to S3 and populates `LocalObjectCache`.
- Produces new `Manifest` and uploads to S3.
- Cleans up `.snapshot-*` temporary directories.

---

### 2. Snapshot Commit & Durability Pipeline (`:backend` & `:core`)

#### Single Database Transaction (MN-3a, FR-15b)
`PlayerWorldRepository` adds transaction-owning commit method:
```sql
UPDATE player_world
   SET manifest_key = ?,
       last_played  = now(),
       data_version = ?,
       mc_version   = ?
 WHERE id = ?
   AND (assigned_node IS NULL OR assigned_node = ?)
   AND generation = ?
```
- If 0 rows updated: throws `FencedException` / `SQLException` and rolls back transaction.
- If 1 row updated: calls `ProfileRepository.saveAll(connection, worldId, snapshot, formatVersion, profiles)` in the same transaction, then commits.

#### Quiescence & Snapshot Pipeline in `WorldCommitService`
1. **Main Thread**:
   - `worldRuntime.setAutoSave(world, false)` for all 3 dimensions.
   - `worldRuntime.save(world)` per dimension.
   - `profileService.capture(player, dimension)` for all players in world.
   - Armed `QuiesceWatchdog` scheduled on background executor to ensure `setAutoSave(true)` is restored if an unhandled exception occurs.
2. **IO Executor**:
   - Stat-walk dirty files against last manifest.
   - Wait quiet period (`storage.snapshot-quiet-ms`, default 250ms).
   - `SnapshotCopier.copyAll()` into temp snapshot directory with post-copy re-stat retry.
   - Hop to main thread / release to restore `setAutoSave(world, true)`.
   - Hash and validate `.mca` files (`RegionStructure`).
   - Upload data objects to S3 and `LocalObjectCache`.
   - Upload `Manifest` JSON to S3.
3. **DB Executor**:
   - Execute single database transaction committing `manifest_key` and player profiles.
4. **Completion**:
   - Clean up temp snapshot directory.
   - Update in-memory manifest reference for the world.

---

### 3. World Lifecycle Integration (`:backend`)

#### Cold & Warm Load in `WorldLifecycleService`
- When `load(worldId)` runs:
  - Reads `PlayerWorld` row from database.
  - If `manifest_key` is present:
    - Fetches `Manifest` from S3.
    - Runs `WorldDownloader.materialize(manifest, scratchPath, cache)` on IO thread.
    - Records `metrics.worldLoadWarm()` or `worldLoadCold()`.
  - Hops to main thread to mount dimensions via `Platform.worldLifecycle().materialise(...)`.
  - Re-applies border, PVP, mob-griefing settings on all dimensions.
  - Caches membership and marks loaded.

#### Initial Snapshot on Creation (FR-1a)
- On `/world create`:
  - After `materialiseNewWorld` generates overworld files, calls `commitService.requestCommit(worldId)` before allowing player join.

#### Periodic Incremental Sync (MN-6)
- `PeriodicSyncTask` runs every `storage.sync-minutes` (default 5 min) on `executors.sched()`, requesting commits for all loaded worlds.

#### Server Shutdown (FR-28)
- In `GzmnWorldsPlugin.onDisable()`:
  - Commits a final snapshot for all loaded worlds before dimension unloads.

---

## Testing & Verification Plan

### 1. Automated Tests (`:core` & `:testing`)
- `ManifestCodecTest`: Round-trip JSON serialization/deserialization, key ordering, corrupt JSON handling.
- `S3ObjectStoreTest` (MinIO Testcontainers): Put/get data objects, manifest objects, existence checks, idempotent retries.
- `LocalObjectCacheTest`: Cache insertion, clone-to-scratch verification, LRU eviction above byte limits.
- `WorldDownloaderTest`:
  - Complete scratch directory wipe restore (NFR-9).
  - Incremental repair of modified/missing files.
  - Untracked file cleanup.
- `SnapshotEngineTest`:
  - Dirty file detection vs previous manifest.
  - Structural `.mca` validation failure aborts without upload.
  - Snapshot copying with retry under concurrent file modification.

### 2. Backend Integration Tests (`:backend`)
- `WorldCommitServiceTest`:
  - Quiesce and auto-save restoration.
  - Fused world snapshot + profile commit in single transaction.
  - Crash recovery & two-store consistency test (FR-15a).
- `WorldLifecycleServiceTest`:
  - Cold load vs warm load execution.
  - Initial snapshot creation on world generation (FR-1a).

### 3. Architecture & Build Gates
- `./gradlew check`: Spotless, Checkstyle, `forbidden-apis`, ArchUnit.
- `./gradlew build`: Full build and shaded jar verification.
