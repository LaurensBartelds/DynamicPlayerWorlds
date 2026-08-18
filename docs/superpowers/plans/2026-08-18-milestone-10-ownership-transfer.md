# Milestone 10: Ownership Transfer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Milestone 10 (Ownership Transfer) for DynamicPlayerWorlds, enabling safe, atomic transfer of world ownership with cap checks, offline transfer requests, admin overrides, and cache invalidation across Paper nodes.

**Architecture:** Core data model and repositories (`TransferRequest`, `OwnershipLogEntry`, `TransferRequestRepository`, `PlayerWorldRepository`) execute atomic multi-table database transactions with full audit logging. The Velocity proxy exposes `/world transfer` (with typed confirmation, offline request staging, accept/decline flows) and `/world admin transfer`, reminds players on login via `PostLoginEvent`, and notifies backend nodes over the control plane (`INVALIDATE_CACHE`).

**Tech Stack:** Java 25, Gradle, PostgreSQL (HikariCP / JDBC), Velocity 4.0.0, Brigadier, JUnit 5, AssertJ, Testcontainers.

**Spec:** [`docs/superpowers/specs/2026-08-18-milestone-10-ownership-transfer-design.md`](file:///home/laurensb/IdeaProjects/DynamicPlayerWorlds/docs/superpowers/specs/2026-08-18-milestone-10-ownership-transfer-design.md) (and [`docs/spec/v0.4.md`](file:///home/laurensb/IdeaProjects/DynamicPlayerWorlds/docs/spec/v0.4.md) §4, §5.7, §6).

## Global Constraints
- Target: Paper (latest stable), Java 25, Velocity proxy.
- NFR-2: All database operations must be off the main/event loop thread.
- CONTRIBUTING rule 5: All expiration and timestamp comparisons must use database time (`now()`), not local clock.
- FR-31a: `player_world.owner_uuid` is authoritative. Ownership changes, member role updates, audit logging, and transfer request cleanup must be atomic in a single database transaction.

---

### Task 1: Core Models & `TransferRequestRepository`

**Files:**
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/model/TransferRequest.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/model/OwnershipLogEntry.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/db/TransferRequestRepository.java`
- Test: `core/src/test/java/nl/gzmn/playerworlds/core/db/TransferRequestRepositoryTest.java`

**Interfaces:**
- Produces:
  - `TransferRequest(WorldId worldId, UUID toUuid, UUID fromUuid, Instant expiresAt, Instant createdAt)`
  - `OwnershipLogEntry(long id, WorldId worldId, UUID fromUuid, UUID toUuid, String reason, Instant transferredAt)`
  - `TransferRequestRepository` with methods:
    - `TransferRequest requestTransfer(Connection connection, WorldId worldId, UUID toUuid, UUID fromUuid, Duration expiry)`
    - `TransferRequest requestTransfer(WorldId worldId, UUID toUuid, UUID fromUuid, Duration expiry)`
    - `Optional<TransferRequest> findLiveRequest(WorldId worldId, UUID toUuid)`
    - `List<TransferRequest> findLiveRequestsFor(UUID toUuid)`
    - `boolean deleteRequest(Connection connection, WorldId worldId, UUID toUuid)`
    - `boolean deleteRequest(WorldId worldId, UUID toUuid)`
    - `int deleteExpired()`

- [ ] **Step 1: Write the failing tests for `TransferRequestRepositoryTest`**

```java
package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.TransferRequest;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransferRequestRepositoryTest extends DatabaseTestCase {

    private TransferRequestRepository requests;
    private PlayerWorldRepository worlds;
    private MembershipRepository membership;

    @BeforeEach
    void setUp() {
        requests = new TransferRequestRepository(database);
        worlds = new PlayerWorldRepository(database);
        membership = new MembershipRepository(database);
    }

    @Test
    void createsAndFindsLiveTransferRequest() throws SQLException {
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "transfer-test", 12345L, 5000, Visibility.PRIVATE);
        membership.insertMember(database.connection(), worldId, target, Role.BUILDER, owner);

        TransferRequest req = requests.requestTransfer(worldId, target, owner, Duration.ofDays(7));
        assertThat(req.worldId()).isEqualTo(worldId);
        assertThat(req.toUuid()).isEqualTo(target);
        assertThat(req.fromUuid()).isEqualTo(owner);
        assertThat(req.expiresAt()).isNotNull();

        Optional<TransferRequest> found = requests.findLiveRequest(worldId, target);
        assertThat(found).isPresent();
        assertThat(found.get().worldId()).isEqualTo(worldId);

        List<TransferRequest> forTarget = requests.findLiveRequestsFor(target);
        assertThat(forTarget).hasSize(1);
        assertThat(forTarget.getFirst().worldId()).isEqualTo(worldId);

        boolean deleted = requests.deleteRequest(worldId, target);
        assertThat(deleted).isTrue();
        assertThat(requests.findLiveRequest(worldId, target)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests nl.gzmn.playerworlds.core.db.TransferRequestRepositoryTest`
Expected: Compilation failure / missing classes.

- [ ] **Step 3: Implement `TransferRequest`, `OwnershipLogEntry`, and `TransferRequestRepository`**

Create `core/src/main/java/nl/gzmn/playerworlds/core/model/TransferRequest.java`:
```java
package nl.gzmn.playerworlds.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TransferRequest(
        WorldId worldId,
        UUID toUuid,
        UUID fromUuid,
        Instant expiresAt,
        Instant createdAt) {

    public TransferRequest {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(toUuid, "toUuid");
        Objects.requireNonNull(fromUuid, "fromUuid");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
```

Create `core/src/main/java/nl/gzmn/playerworlds/core/model/OwnershipLogEntry.java`:
```java
package nl.gzmn.playerworlds.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OwnershipLogEntry(
        long id,
        WorldId worldId,
        UUID fromUuid,
        UUID toUuid,
        String reason,
        Instant transferredAt) {

    public OwnershipLogEntry {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(fromUuid, "fromUuid");
        Objects.requireNonNull(toUuid, "toUuid");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(transferredAt, "transferredAt");
    }
}
```

Create `core/src/main/java/nl/gzmn/playerworlds/core/db/TransferRequestRepository.java`:
```java
package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.TransferRequest;
import nl.gzmn.playerworlds.core.model.WorldId;

/**
 * JDBC access to {@code player_world_transfer_request} (FR-32).
 */
public final class TransferRequestRepository extends Repository {

    public TransferRequestRepository(Database database) {
        super(database);
    }

    public TransferRequest requestTransfer(
            Connection connection, WorldId worldId, UUID toUuid, UUID fromUuid, Duration expiry)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(toUuid, "toUuid");
        Objects.requireNonNull(fromUuid, "fromUuid");
        Objects.requireNonNull(expiry, "expiry");

        return queryOne(
                        connection,
                        """
                        INSERT INTO player_world_transfer_request (world_id, to_uuid, from_uuid, expires_at)
                        VALUES (?, ?, ?, now() + (? * interval '1 second'))
                        ON CONFLICT (world_id, to_uuid) DO UPDATE
                          SET from_uuid = EXCLUDED.from_uuid,
                              expires_at = EXCLUDED.expires_at,
                              created_at = now()
                        RETURNING world_id, to_uuid, from_uuid, expires_at, created_at
                        """,
                        statement -> {
                            statement.setObject(1, worldId.value());
                            statement.setObject(2, toUuid);
                            statement.setObject(3, fromUuid);
                            statement.setLong(4, expiry.toSeconds());
                        },
                        TransferRequestRepository::mapRow)
                .orElseThrow(() -> new SQLException("INSERT player_world_transfer_request RETURNING produced no row"));
    }

    public TransferRequest requestTransfer(WorldId worldId, UUID toUuid, UUID fromUuid, Duration expiry)
            throws SQLException {
        return database.inTransaction(
                connection -> requestTransfer(connection, worldId, toUuid, fromUuid, expiry));
    }

    public Optional<TransferRequest> findLiveRequest(WorldId worldId, UUID toUuid) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(toUuid, "toUuid");
        return database.withConnection(connection -> queryOne(
                connection,
                """
                SELECT world_id, to_uuid, from_uuid, expires_at, created_at
                  FROM player_world_transfer_request
                 WHERE world_id = ? AND to_uuid = ? AND expires_at > now()
                """,
                statement -> {
                    statement.setObject(1, worldId.value());
                    statement.setObject(2, toUuid);
                },
                TransferRequestRepository::mapRow));
    }

    public List<TransferRequest> findLiveRequestsFor(UUID toUuid) throws SQLException {
        Objects.requireNonNull(toUuid, "toUuid");
        return database.withConnection(connection -> queryList(
                connection,
                """
                SELECT world_id, to_uuid, from_uuid, expires_at, created_at
                  FROM player_world_transfer_request
                 WHERE to_uuid = ? AND expires_at > now()
                 ORDER BY created_at DESC
                """,
                statement -> statement.setObject(1, toUuid),
                TransferRequestRepository::mapRow));
    }

    public boolean deleteRequest(Connection connection, WorldId worldId, UUID toUuid) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(toUuid, "toUuid");
        return execute(
                        connection,
                        "DELETE FROM player_world_transfer_request WHERE world_id = ? AND to_uuid = ?",
                        statement -> {
                            statement.setObject(1, worldId.value());
                            statement.setObject(2, toUuid);
                        })
                >= 1;
    }

    public boolean deleteRequest(WorldId worldId, UUID toUuid) throws SQLException {
        return database.inTransaction(connection -> deleteRequest(connection, worldId, toUuid));
    }

    public int deleteExpired() throws SQLException {
        return database.inTransaction(connection -> execute(
                connection,
                "DELETE FROM player_world_transfer_request WHERE expires_at <= now()",
                StatementBinder.NONE));
    }

    private static TransferRequest mapRow(ResultSet row) throws SQLException {
        UUID worldId = Objects.requireNonNull(row.getObject("world_id", UUID.class), "world_id");
        UUID toUuid = Objects.requireNonNull(row.getObject("to_uuid", UUID.class), "to_uuid");
        UUID fromUuid = Objects.requireNonNull(row.getObject("from_uuid", UUID.class), "from_uuid");
        Instant expiresAt = requireInstant(row, "expires_at");
        Instant createdAt = requireInstant(row, "created_at");
        return new TransferRequest(new WorldId(worldId), toUuid, fromUuid, expiresAt, createdAt);
    }

    private static Instant requireInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException(column + " was NULL");
        }
        return value.toInstant();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests nl.gzmn.playerworlds.core.db.TransferRequestRepositoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/nl/gzmn/playerworlds/core/model/TransferRequest.java \
        core/src/main/java/nl/gzmn/playerworlds/core/model/OwnershipLogEntry.java \
        core/src/main/java/nl/gzmn/playerworlds/core/db/TransferRequestRepository.java \
        core/src/test/java/nl/gzmn/playerworlds/core/db/TransferRequestRepositoryTest.java
git commit -m "feat(core): implement TransferRequestRepository and ownership models"
```

---

### Task 2: Atomic Ownership Transfer in `PlayerWorldRepository`

**Files:**
- Modify: `core/src/main/java/nl/gzmn/playerworlds/core/db/PlayerWorldRepository.java`
- Test: `core/src/test/java/nl/gzmn/playerworlds/core/db/PlayerWorldRepositoryTest.java`

**Interfaces:**
- Produces:
  - `PlayerWorldRepository.transferOwnership(WorldId worldId, UUID oldOwnerUuid, UUID newOwnerUuid, String reason)`

- [ ] **Step 1: Write the failing tests in `PlayerWorldRepositoryTest`**

```java
    @Test
    void transfersOwnershipAtomically() throws SQLException {
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        WorldId worldId = WorldId.random();

        worlds.create(worldId, oldOwner, "my-world", 42L, 5000, Visibility.PRIVATE);
        membership.insertMember(database.connection(), worldId, newOwner, Role.BUILDER, oldOwner);

        boolean transferred = worlds.transferOwnership(worldId, oldOwner, newOwner, "MANUAL");
        assertThat(transferred).isTrue();

        PlayerWorld updated = worlds.findById(worldId).orElseThrow();
        assertThat(updated.ownerUuid()).isEqualTo(newOwner);

        assertThat(membership.findMember(worldId, oldOwner).orElseThrow().role()).isEqualTo(Role.BUILDER);
        assertThat(membership.findMember(worldId, newOwner).orElseThrow().role()).isEqualTo(Role.OWNER);

        assertThat(worlds.countOwnedBy(oldOwner)).isZero();
        assertThat(worlds.countOwnedBy(newOwner)).isEqualTo(1);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests nl.gzmn.playerworlds.core.db.PlayerWorldRepositoryTest`
Expected: FAIL (`transferOwnership` method not found).

- [ ] **Step 3: Implement `transferOwnership` in `PlayerWorldRepository`**

Add to `PlayerWorldRepository.java`:
```java
    /**
     * Atomically transfers world ownership to a new owner (FR-31, FR-31a).
     *
     * <p>In a single transaction:
     * <ol>
     *   <li>Updates {@code player_world.owner_uuid} to {@code newOwnerUuid} conditionally on {@code oldOwnerUuid}</li>
     *   <li>Ensures {@code newOwnerUuid} exists in {@code player_world_member} and sets role to {@link Role#OWNER}</li>
     *   <li>Demotes {@code oldOwnerUuid} in {@code player_world_member} to {@link Role#BUILDER}</li>
     *   <li>Inserts a row into {@code player_world_ownership_log}</li>
     *   <li>Removes any pending {@code player_world_transfer_request} for this world and target</li>
     * </ol>
     *
     * @return true if the transfer succeeded, false if the old owner did not match
     */
    public boolean transferOwnership(WorldId worldId, UUID oldOwnerUuid, UUID newOwnerUuid, String reason)
            throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(oldOwnerUuid, "oldOwnerUuid");
        Objects.requireNonNull(newOwnerUuid, "newOwnerUuid");
        Objects.requireNonNull(reason, "reason");

        return database.inTransaction(connection -> {
            int updated = execute(
                    connection,
                    "UPDATE player_world SET owner_uuid = ? WHERE id = ? AND owner_uuid = ?",
                    statement -> {
                        statement.setObject(1, newOwnerUuid);
                        statement.setObject(2, worldId.value());
                        statement.setObject(3, oldOwnerUuid);
                    });

            if (updated != 1) {
                return false;
            }

            // 1. Ensure target member is OWNER
            execute(
                    connection,
                    """
                    INSERT INTO player_world_member (world_id, uuid, role)
                    VALUES (?, ?, 'OWNER')
                    ON CONFLICT (world_id, uuid) DO UPDATE SET role = 'OWNER'
                    """,
                    statement -> {
                        statement.setObject(1, worldId.value());
                        statement.setObject(2, newOwnerUuid);
                    });

            // 2. Demote old owner to BUILDER
            execute(
                    connection,
                    "UPDATE player_world_member SET role = 'BUILDER' WHERE world_id = ? AND uuid = ?",
                    statement -> {
                        statement.setObject(1, worldId.value());
                        statement.setObject(2, oldOwnerUuid);
                    });

            // 3. Insert audit log
            execute(
                    connection,
                    """
                    INSERT INTO player_world_ownership_log (world_id, from_uuid, to_uuid, reason)
                    VALUES (?, ?, ?, ?)
                    """,
                    statement -> {
                        statement.setObject(1, worldId.value());
                        statement.setObject(2, oldOwnerUuid);
                        statement.setObject(3, newOwnerUuid);
                        statement.setString(4, reason);
                    });

            // 4. Delete any pending transfer requests
            execute(
                    connection,
                    "DELETE FROM player_world_transfer_request WHERE world_id = ? AND to_uuid = ?",
                    statement -> {
                        statement.setObject(1, worldId.value());
                        statement.setObject(2, newOwnerUuid);
                    });

            return true;
        });
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests nl.gzmn.playerworlds.core.db.PlayerWorldRepositoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/nl/gzmn/playerworlds/core/db/PlayerWorldRepository.java \
        core/src/test/java/nl/gzmn/playerworlds/core/db/PlayerWorldRepositoryTest.java
git commit -m "feat(core): implement atomic transferOwnership in PlayerWorldRepository"
```

---

### Task 3: Proxy Commands for Ownership Transfer (`WorldCommand`)

**Files:**
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldCommand.java`
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/GzmnWorldsProxyPlugin.java`
- Test: `proxy/src/test/java/nl/gzmn/playerworlds/proxy/command/WorldCommandTest.java`

**Interfaces:**
- Consumes: `TransferRequestRepository`, `PlayerWorldRepository.transferOwnership`
- Produces: Command handling for `/world transfer`, `/world transfer accept`, `/world transfer decline`, and `/world admin transfer`.

- [ ] **Step 1: Write failing unit tests for `/world transfer` and `/world admin transfer` in `WorldCommandTest`**

Add tests for:
- `/world transfer <player>` (prompts with confirmation requirement)
- `/world transfer <player> confirm` when target is online (immediate transfer)
- `/world transfer <player> confirm` when target is offline (creates transfer request)
- `/world transfer <player> confirm` when target at cap (refused)
- `/world transfer <player> confirm` when target is not a member (refused)
- `/world transfer accept <owner>` (accepts transfer request and transfers world)
- `/world transfer decline <owner>` (removes transfer request)
- `/world admin transfer <id> <player>` (admin force transfer)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :proxy:test --tests nl.gzmn.playerworlds.proxy.command.WorldCommandTest`
Expected: FAIL

- [ ] **Step 3: Implement the transfer commands in `WorldCommand` and wire dependencies in `GzmnWorldsProxyPlugin`**

1. Pass `TransferRequestRepository` to `WorldCommand`.
2. Add `transfer` to `SUBCOMMANDS` and `ADMIN_SUBCOMMANDS`.
3. Add command branch in `build()`:
   - `transfer` subcommand:
     - `accept <owner>`
     - `decline <owner>`
     - `<player>`:
       - executes transfer prompt
       - `confirm` executes transfer if online or creates pending request if offline
   - `admin transfer <id> <player>`: executes admin transfer with reason `ADMIN`.
4. Implement handlers: `transfer(...)`, `transferAccept(...)`, `transferDecline(...)`, `adminTransfer(...)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :proxy:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldCommand.java \
        proxy/src/main/java/nl/gzmn/playerworlds/proxy/GzmnWorldsProxyPlugin.java \
        proxy/src/test/java/nl/gzmn/playerworlds/proxy/command/WorldCommandTest.java
git commit -m "feat(proxy): implement /world transfer and /world admin transfer commands"
```

---

### Task 4: Proxy Login Notification for Pending Transfer Requests

**Files:**
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/GzmnWorldsProxyPlugin.java`
- Test: `proxy/src/test/java/nl/gzmn/playerworlds/proxy/GzmnWorldsProxyPluginTest.java`

**Interfaces:**
- Consumes: `TransferRequestRepository.findLiveRequestsFor`
- Produces: Sends reminder message to player on `PostLoginEvent` if pending requests exist.

- [ ] **Step 1: Write test for `PostLoginEvent` transfer reminder**

Test that when a player connects and has pending transfer requests, a reminder message is sent to them.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :proxy:test`
Expected: FAIL

- [ ] **Step 3: Implement reminder in `GzmnWorldsProxyPlugin.onPostLogin`**

In `onPostLogin`:
```java
        pools.db().execute(() -> {
            try {
                repository.remember(
                        event.getPlayer().getUniqueId(), event.getPlayer().getUsername());
                List<TransferRequest> pending = transferRequests.findLiveRequestsFor(event.getPlayer().getUniqueId());
                if (!pending.isEmpty()) {
                    event.getPlayer().sendMessage(net.kyori.adventure.text.Component.text(
                            "You have " + pending.size() + " pending world ownership transfer request(s)! Use /world transfer accept <owner> to accept.",
                            net.kyori.adventure.text.format.NamedTextColor.GOLD));
                }
            } catch (SQLException e) {
                logger.warn("could not process login for {}: {}", event.getPlayer().getUsername(), e.getMessage());
            }
        });
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :proxy:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add proxy/src/main/java/nl/gzmn/playerworlds/proxy/GzmnWorldsProxyPlugin.java \
        proxy/src/test/java/nl/gzmn/playerworlds/proxy/GzmnWorldsProxyPluginTest.java
git commit -m "feat(proxy): send pending transfer request reminders on player login"
```

---

### Task 5: Full Verification & Formatting

**Files:**
- All modified modules (`:core`, `:proxy`)

- [ ] **Step 1: Run spotlessApply and full check build**

Run:
```bash
./gradlew spotlessApply
./gradlew check build
```
Expected: Green build, all tests pass, NullAway and forbiddenApis pass, architecture tests pass.

- [ ] **Step 2: Commit any final formatting or cleanup**

```bash
git add -A
git commit -m "chore: spotless formatting and verification for milestone 10"
```
