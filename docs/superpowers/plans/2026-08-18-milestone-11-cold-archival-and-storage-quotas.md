# Milestone 11: Cold Archival & Restore + Player Storage Quotas Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Milestone 11 (Cold Archival & Restore) and Permission-Based Player Storage Quotas for DynamicPlayerWorlds, supporting `.tar.zst`/`.tar.gz` world packaging to object storage/filesystem, safe lease-fenced archival and restore lifecycle states, dynamic LuckPerms storage quota resolution (`gzmn.worlds.storage.<size>`), quota gating on creation/restore/transfers, storage breakdown commands, and maintenance recovery loops.

**Architecture:** Core schema migration (`V4__storage_bytes.sql`) and repositories (`ArchiveRepository`, `PlayerWorldRepository`) track storage footprint and cold archive metadata. `StorageQuotaResolver` dynamically parses numeric permission nodes to determine player quota limits. In `:backend`, `ArchivePacker`, `WorldArchiver`, and `WorldRestorer` manage dimension folder compression, SHA-256 validation, object storage uploads, and state machine transitions (`ARCHIVING` $\rightarrow$ `ARCHIVED` $\rightarrow$ `RESTORING` $\rightarrow$ `READY`). The Velocity proxy exposes `/world storage`, `/world delete` (archival flow), `/world restore`, and administrative storage commands. `MaintenanceTask` performs inactivity archival and stale lease recovery.

**Tech Stack:** Java 25, Gradle, PostgreSQL (HikariCP / JDBC), Flyway, Velocity 4.0.0, Paper 1.21.4, Brigadier, Zstandard (`zstd-jni` / Gzip), S3-compatible Object Storage (MinIO / AWS SDK), JUnit 5, AssertJ.

**Spec:** [`docs/superpowers/specs/2026-08-18-milestone-11-cold-archival-and-storage-quotas-design.md`](file:///home/laurensb/IdeaProjects/DynamicPlayerWorlds/docs/superpowers/specs/2026-08-18-milestone-11-cold-archival-and-storage-quotas-design.md) (and [`docs/spec/v0.4.md`](file:///home/laurensb/IdeaProjects/DynamicPlayerWorlds/docs/spec/v0.4.md) §4, §5.8, §6).

## Global Constraints
- Target: Paper (latest stable), Java 25, Velocity proxy.
- NFR-2: All database operations must be off the main/event loop thread.
- CONTRIBUTING rule 5: All expiration and timestamp comparisons must use database time (`now()`), not local clock.
- FR-35: Archiving must acquire lease first, set state `ARCHIVING`, verify archive checksum before deleting live snapshot prefix, and release lease last.
- FR-36: Restore acquires lease, sets `state = 'RESTORING'`, verifies checksum and version check (MN-29), checks storage quota, uploads fresh snapshot, and transitions to `READY`.
- Storage Quota: Evaluates highest `gzmn.worlds.storage.<amount><unit>` permission, defaulting to `NetworkPolicy.defaultStorageLimitBytes()`. Exceeded quota blocks `/world create`, `/world restore`, and `/world transfer accept`.

---

### Task 1: Migration V4, Core Models (`WorldArchive`, `StorageQuota`), `ArchiveRepository` and `PlayerWorldRepository` extensions

**Files:**
- Create: `core/src/main/resources/db/migration/V4__storage_bytes.sql`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/model/WorldArchive.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/model/StorageQuota.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/db/ArchiveRepository.java`
- Modify: `core/src/main/java/nl/gzmn/playerworlds/core/db/PlayerWorldRepository.java`
- Test: `core/src/test/java/nl/gzmn/playerworlds/core/db/ArchiveRepositoryTest.java`
- Test: `core/src/test/java/nl/gzmn/playerworlds/core/db/PlayerWorldRepositoryTest.java`

**Interfaces:**
- Produces:
  - `WorldArchive(WorldId worldId, String objectKey, long sizeBytes, String checksum, int dataVersion, Instant archivedAt, int restoreCount)`
  - `StorageQuota(UUID playerUuid, long usedBytes, long limitBytes, boolean unlimited)`
  - `ArchiveRepository` methods:
    - `WorldArchive recordArchive(Connection connection, WorldId worldId, String objectKey, long sizeBytes, String checksum, int dataVersion)`
    - `WorldArchive recordArchive(WorldId worldId, String objectKey, long sizeBytes, String checksum, int dataVersion)`
    - `Optional<WorldArchive> findLatestByWorld(WorldId worldId)`
    - `List<WorldArchive> findAllByWorld(WorldId worldId)`
    - `boolean incrementRestoreCount(WorldId worldId, Instant archivedAt)`
    - `boolean deleteArchive(WorldId worldId, Instant archivedAt)`
  - `PlayerWorldRepository` methods:
    - `long totalStorageUsedBy(UUID ownerUuid)`
    - `boolean updateStorageBytes(WorldId worldId, long storageBytes)`
    - `boolean transitionToArchived(WorldId worldId, String objectKey, long sizeBytes, String checksum, int dataVersion)`
    - `boolean transitionToRestoring(WorldId worldId, String node, Duration leaseDuration)`
    - `boolean completeRestore(WorldId worldId, String manifestKey, long storageBytes, int dataVersion, String mcVersion)`

- [ ] **Step 1: Write migration `V4__storage_bytes.sql`**

```sql
-- V4. Storage accounting for live snapshots and player quotas.
ALTER TABLE player_world ADD COLUMN storage_bytes BIGINT NOT NULL DEFAULT 0;
```

- [ ] **Step 2: Write failing unit test `ArchiveRepositoryTest.java`**

```java
package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldArchive;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArchiveRepositoryTest extends DatabaseTestCase {

    private ArchiveRepository archives;
    private PlayerWorldRepository worlds;

    @BeforeEach
    void setUp() {
        archives = new ArchiveRepository(database);
        worlds = new PlayerWorldRepository(database);
    }

    @Test
    void recordsAndFindsLatestArchive() throws SQLException {
        UUID owner = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "archive-test", 12345L, 5000, Visibility.PRIVATE);

        WorldArchive created = archives.recordArchive(
                worldId, "worlds/" + worldId + "/archive/test.tar.zst", 1048576L, "abcdef123456", 3953);

        assertThat(created.worldId()).isEqualTo(worldId);
        assertThat(created.sizeBytes()).isEqualTo(1048576L);
        assertThat(created.dataVersion()).isEqualTo(3953);

        Optional<WorldArchive> found = archives.findLatestByWorld(worldId);
        assertThat(found).isPresent();
        assertThat(found.get().objectKey()).isEqualTo("worlds/" + worldId + "/archive/test.tar.zst");

        archives.incrementRestoreCount(worldId, created.archivedAt());
        Optional<WorldArchive> updated = archives.findLatestByWorld(worldId);
        assertThat(updated.get().restoreCount()).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:test --tests nl.gzmn.playerworlds.core.db.ArchiveRepositoryTest`
Expected: FAIL

- [ ] **Step 4: Implement models and repositories in `:core`**

Implement `WorldArchive.java`, `StorageQuota.java`, `ArchiveRepository.java`, and extend `PlayerWorldRepository.java` (`totalStorageUsedBy`, `updateStorageBytes`, `transitionToArchived`, `transitionToRestoring`, `completeRestore`).

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:test --tests nl.gzmn.playerworlds.core.db.ArchiveRepositoryTest && ./gradlew :core:test --tests nl.gzmn.playerworlds.core.db.PlayerWorldRepositoryTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add core/src/main/resources/db/migration/V4__storage_bytes.sql \
        core/src/main/java/nl/gzmn/playerworlds/core/model/WorldArchive.java \
        core/src/main/java/nl/gzmn/playerworlds/core/model/StorageQuota.java \
        core/src/main/java/nl/gzmn/playerworlds/core/db/ArchiveRepository.java \
        core/src/main/java/nl/gzmn/playerworlds/core/db/PlayerWorldRepository.java \
        core/src/test/java/nl/gzmn/playerworlds/core/db/ArchiveRepositoryTest.java \
        core/src/test/java/nl/gzmn/playerworlds/core/db/PlayerWorldRepositoryTest.java
git commit -m "feat(core): implement archive repository, models, and storage accounting"
```

---

### Task 2: Permission-Based Storage Quota Resolver (`StorageQuotaResolver`)

**Files:**
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/config/StorageQuotaResolver.java`
- Modify: `core/src/main/java/nl/gzmn/playerworlds/core/config/NetworkPolicy.java`
- Test: `core/src/test/java/nl/gzmn/playerworlds/core/config/StorageQuotaResolverTest.java`

**Interfaces:**
- Produces:
  - `StorageQuotaResolver`:
    - `static long parsePermissionLimit(String permission)` (returns parsed bytes or -1 if non-matching)
    - `static long resolveLimitBytes(Collection<String> permissions, boolean isAdmin, long defaultLimitBytes)`
    - `static StorageQuota evaluate(UUID playerUuid, long usedBytes, Collection<String> permissions, boolean isAdmin, long defaultLimitBytes)`
    - `static String formatBytes(long bytes)` (e.g. "1.25 GB", "500 MB")

- [ ] **Step 1: Write failing unit test `StorageQuotaResolverTest.java`**

```java
package nl.gzmn.playerworlds.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.StorageQuota;
import org.junit.jupiter.api.Test;

class StorageQuotaResolverTest {

    @Test
    void parsesNumericStoragePermissions() {
        assertThat(StorageQuotaResolver.parsePermissionLimit("gzmn.worlds.storage.10gb"))
                .isEqualTo(10L * 1024 * 1024 * 1024);
        assertThat(StorageQuotaResolver.parsePermissionLimit("gzmn.worlds.storage.5000mb"))
                .isEqualTo(5000L * 1024 * 1024);
        assertThat(StorageQuotaResolver.parsePermissionLimit("gzmn.worlds.storage.500kb"))
                .isEqualTo(500L * 1024);
        assertThat(StorageQuotaResolver.parsePermissionLimit("gzmn.worlds.storage.1tb"))
                .isEqualTo(1024L * 1024 * 1024 * 1024);
        assertThat(StorageQuotaResolver.parsePermissionLimit("invalid.permission"))
                .isEqualTo(-1L);
    }

    @Test
    void selectsHighestPermissionLimit() {
        List<String> perms = List.of(
                "gzmn.worlds.storage.5gb",
                "gzmn.worlds.storage.20gb",
                "gzmn.worlds.storage.10gb"
        );
        long resolved = StorageQuotaResolver.resolveLimitBytes(perms, false, 5L * 1024 * 1024 * 1024);
        assertThat(resolved).isEqualTo(20L * 1024 * 1024 * 1024);
    }

    @Test
    void adminAndUnlimitedAreExempt() {
        StorageQuota quotaAdmin = StorageQuotaResolver.evaluate(
                UUID.randomUUID(), 1000L, List.of(), true, 5000L);
        assertThat(quotaAdmin.unlimited()).isTrue();
        assertThat(quotaAdmin.isExceeded()).isFalse();

        StorageQuota quotaPerm = StorageQuotaResolver.evaluate(
                UUID.randomUUID(), 1000L, List.of("gzmn.worlds.storage.unlimited"), false, 5000L);
        assertThat(quotaPerm.unlimited()).isTrue();
        assertThat(quotaPerm.isExceeded()).isFalse();
    }

    @Test
    void formatsHumanReadableBytes() {
        assertThat(StorageQuotaResolver.formatBytes(1073741824L)).isEqualTo("1.00 GB");
        assertThat(StorageQuotaResolver.formatBytes(524288000L)).isEqualTo("500.00 MB");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests nl.gzmn.playerworlds.core.config.StorageQuotaResolverTest`
Expected: FAIL

- [ ] **Step 3: Implement `StorageQuotaResolver.java`**

Implement regex matching `^gzmn\.worlds\.storage\.(\d+)(b|kb|mb|gb|tb)?$`, unit multipliers, max selection, and `formatBytes`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests nl.gzmn.playerworlds.core.config.StorageQuotaResolverTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/nl/gzmn/playerworlds/core/config/StorageQuotaResolver.java \
        core/src/main/java/nl/gzmn/playerworlds/core/config/NetworkPolicy.java \
        core/src/test/java/nl/gzmn/playerworlds/core/config/StorageQuotaResolverTest.java
git commit -m "feat(core): implement StorageQuotaResolver for dynamic LuckPerms storage tiers"
```

---

### Task 3: Backend Archive Packaging & Storage Client (`ArchivePacker`, `ArchiveStorage`)

**Files:**
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/storage/ArchivePacker.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/storage/ArchiveStorage.java`
- Test: `backend/src/test/java/nl/gzmn/playerworlds/backend/storage/ArchivePackerTest.java`
- Test: `backend/src/test/java/nl/gzmn/playerworlds/backend/storage/ArchiveStorageTest.java`

**Interfaces:**
- Produces:
  - `ArchivePacker.PackResult pack(List<Path> dimensionDirs, Path targetArchiveFile, boolean useZstd)`
  - `void unpack(Path archiveFile, Path targetExtractionDir)`
  - `String computeSha256(Path file)`
  - `ArchiveStorage`:
    - `void uploadArchive(String key, Path file)`
    - `void downloadArchive(String key, Path destination)`
    - `void deleteArchive(String key)`

- [ ] **Step 1: Write failing unit test `ArchivePackerTest.java`**

Test creating sample dimension folders with files, packing them into `.tar.zst` / `.tar.gz`, computing SHA-256 checksum, unpacking to clean directory, and verifying file contents match.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.storage.ArchivePackerTest`
Expected: FAIL

- [ ] **Step 3: Implement `ArchivePacker.java` and `ArchiveStorage.java`**

Implement streaming Tar archive output with Zstandard/Gzip compression, calculating SHA-256 during packaging, and streaming Tar decompression with checksum verification.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.storage.ArchivePackerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/nl/gzmn/playerworlds/backend/storage/ArchivePacker.java \
        backend/src/main/java/nl/gzmn/playerworlds/backend/storage/ArchiveStorage.java \
        backend/src/test/java/nl/gzmn/playerworlds/backend/storage/ArchivePackerTest.java \
        backend/src/test/java/nl/gzmn/playerworlds/backend/storage/ArchiveStorageTest.java
git commit -m "feat(backend): implement ArchivePacker and ArchiveStorage for world tarballs"
```

---

### Task 4: Backend Archival & Restore Services (`WorldArchiver`, `WorldRestorer`, Node Handlers)

**Files:**
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/storage/WorldArchiver.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/storage/WorldRestorer.java`
- Modify: `backend/src/main/java/nl/gzmn/playerworlds/backend/control/BackendControlHandlers.java`
- Test: `backend/src/test/java/nl/gzmn/playerworlds/backend/storage/WorldArchiverTest.java`
- Test: `backend/src/test/java/nl/gzmn/playerworlds/backend/storage/WorldRestorerTest.java`

**Interfaces:**
- Produces:
  - `WorldArchiver.archiveWorld(WorldId worldId, UUID ownerUuid)`:
    - Acquires lease (sets state `ARCHIVING`).
    - Unloads dimensions if loaded.
    - Packs world files to compressed archive.
    - Uploads archive and records `player_world_archive` entry.
    - Sets state `ARCHIVED`, updates `storage_bytes`.
    - Purges live object storage prefix upon verification.
    - Releases lease.
  - `WorldRestorer.restoreWorld(WorldId worldId, UUID targetOwnerUuid)`:
    - Acquires lease (sets state `RESTORING`).
    - Fetches latest archive from `player_world_archive`.
    - Verifies checksum and version.
    - Unpacks files, generates snapshot manifest, uploads objects.
    - Commits fresh snapshot, sets state `READY`, updates `storage_bytes`.
    - Releases lease.

- [ ] **Step 1: Write failing unit tests `WorldArchiverTest.java` and `WorldRestorerTest.java`**

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.storage.WorldArchiverTest`
Expected: FAIL

- [ ] **Step 3: Implement `WorldArchiver`, `WorldRestorer`, and wire into `BackendControlHandlers`**

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.storage.WorldArchiverTest && ./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.storage.WorldRestorerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/nl/gzmn/playerworlds/backend/storage/WorldArchiver.java \
        backend/src/main/java/nl/gzmn/playerworlds/backend/storage/WorldRestorer.java \
        backend/src/main/java/nl/gzmn/playerworlds/backend/control/BackendControlHandlers.java \
        backend/src/test/java/nl/gzmn/playerworlds/backend/storage/WorldArchiverTest.java \
        backend/src/test/java/nl/gzmn/playerworlds/backend/storage/WorldRestorerTest.java
git commit -m "feat(backend): implement WorldArchiver and WorldRestorer with lease fencing"
```

---

### Task 5: Proxy Commands for Storage, Archival, and Restore (`WorldCommand`)

**Files:**
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldCommand.java`
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/GzmnWorldsProxyPlugin.java`
- Test: `proxy/src/test/java/nl/gzmn/playerworlds/proxy/command/WorldCommandTest.java`

**Interfaces:**
- Produces:
  - `/world storage`: Evaluates player's permissions via `StorageQuotaResolver`, queries `totalStorageUsedBy`, renders progress bar and world list.
  - `/world delete <name> confirm`: Triggers `ARCHIVING` flow instead of hard delete.
  - `/world restore <name>`: Validates quota, triggers `RESTORE_WORLD`.
  - `/world create`: Checks if quota would be exceeded before creation.
  - `/world transfer accept`: Checks if quota would be exceeded before acceptance.
  - `/world admin storage <player>`
  - `/world admin archive <id>`
  - `/world admin restore <id> [player]`
  - `/world admin delete <id> confirm` (hard deletion).

- [ ] **Step 1: Write failing unit tests in `WorldCommandTest.java`**

Add tests for:
1. `/world storage` display with percentage bar and owned world list.
2. `/world create` refusal when storage quota is exceeded.
3. `/world restore <name>` refusal when storage quota is exceeded.
4. `/world restore <name>` happy path enqueuing restore.
5. `/world transfer accept` refusal when target would exceed storage quota.
6. `/world admin storage <player>`, `/world admin archive <id>`, `/world admin restore <id>`, `/world admin delete <id> confirm`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :proxy:test --tests nl.gzmn.playerworlds.proxy.command.WorldCommandTest`
Expected: FAIL

- [ ] **Step 3: Implement storage and archival commands in `WorldCommand.java`**

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :proxy:test --tests nl.gzmn.playerworlds.proxy.command.WorldCommandTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldCommand.java \
        proxy/src/main/java/nl/gzmn/playerworlds/proxy/GzmnWorldsProxyPlugin.java \
        proxy/src/test/java/nl/gzmn/playerworlds/proxy/command/WorldCommandTest.java
git commit -m "feat(proxy): implement /world storage, /world restore, and quota checks"
```

---

### Task 6: Inactivity Archival & Maintenance Recovery Loop (`MaintenanceTask`)

**Files:**
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/storage/MaintenanceTask.java`
- Modify: `backend/src/main/java/nl/gzmn/playerworlds/backend/GzmnWorldsBackendPlugin.java`
- Test: `backend/src/test/java/nl/gzmn/playerworlds/backend/storage/MaintenanceTaskTest.java`

**Interfaces:**
- Produces:
  - `MaintenanceTask.run()`:
    - Scans for worlds with `last_played < now() - interval '90 days'` and schedules archival.
    - Scans for stale `ARCHIVING` rows with expired lease (`lease_expires < now()`) $\rightarrow$ retries archival or resets to `READY`.
    - Scans for stale `RESTORING` rows with expired lease (`lease_expires < now()`) $\rightarrow$ resets to `ARCHIVED`.
    - Purges expired transfer requests via `TransferRequestRepository.deleteExpired()`.

- [ ] **Step 1: Write failing unit test `MaintenanceTaskTest.java`**

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.storage.MaintenanceTaskTest`
Expected: FAIL

- [ ] **Step 3: Implement `MaintenanceTask.java` and register periodic execution**

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.storage.MaintenanceTaskTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/nl/gzmn/playerworlds/backend/storage/MaintenanceTask.java \
        backend/src/main/java/nl/gzmn/playerworlds/backend/GzmnWorldsBackendPlugin.java \
        backend/src/test/java/nl/gzmn/playerworlds/backend/storage/MaintenanceTaskTest.java
git commit -m "feat(backend): implement MaintenanceTask for inactivity archival and crash recovery"
```

---

### Task 7: Full Verification & Formatting

**Files:**
- Modify: (All touched files as needed for formatting)

- [ ] **Step 1: Run spotlessApply**

Run: `./gradlew spotlessApply`

- [ ] **Step 2: Run check and build across all modules**

Run: `./gradlew check build`
Expected: PASS across `:core`, `:backend`, `:proxy`, `:e2e-harness`, and `:testing` with 0 warnings/failures.

- [ ] **Step 3: Commit any formatting or verification adjustments**

```bash
git add -A
git commit -m "chore: spotless formatting and verification for milestone 11"
```
