# Milestone 5 Control Plane & Return Leg Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the remaining Milestone 5 deliverables (§11.5, §13, FR-10–13, FR-27, CP-1–7) by wiring `ControlPlane` listeners and handlers on backend and proxy, emitting control commands upon world deletion and membership changes, and implementing the return leg back to the lobby server.

**Architecture:** 
- `:core` provides `EjectPayload` serializer/parser for typed payload handling.
- `:backend` runs `ControlPlane.forNode` with handlers for `UNLOAD_WORLD`, `INVALIDATE_CACHE`, `KICK_MEMBER`, and `EJECT_PLAYER`, integrating with `WorldRegistry`, `WorldLifecycleService`, `MembershipCache`, and `NetworkSettings`.
- `:proxy` runs `ControlPlane.forProxy` with an `EJECT_PLAYER` handler that transfers connected players to the lobby server, and emits `UNLOAD_WORLD`, `INVALIDATE_CACHE`, and `KICK_MEMBER` over `NodeCommandRepository` during `/world delete`, `/world kick`, and `/world promote`.
- Return leg routes players from `/world leave` and FR-11 refusals back to the lobby via `EJECT_PLAYER` on `ControlChannels.PROXY`.

**Tech Stack:** Java 25, Gradle, PostgreSQL (pg_notify / LISTEN), Paper API 26.2, Velocity API 4.0.0, Adventure text components, JUnit 5, MockBukkit.

---

### Task 1: Payload Serialization in `:core` (`EjectPayload`)

**Files:**
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/control/EjectPayload.java`
- Test: `core/src/test/java/nl/gzmn/playerworlds/core/control/EjectPayloadTest.java`

- [ ] **Step 1: Write failing unit tests for `EjectPayload`**

Create `core/src/test/java/nl/gzmn/playerworlds/core/control/EjectPayloadTest.java`:
```java
package nl.gzmn.playerworlds.core.control;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EjectPayloadTest {

    @Test
    void encodesAndDecodesPayloadWithReason() {
        UUID uuid = UUID.randomUUID();
        String reason = "World deleted";
        String json = EjectPayload.format(uuid, reason);

        Optional<EjectPayload> parsed = EjectPayload.parse(json);
        assertTrue(parsed.isPresent());
        assertEquals(uuid, parsed.get().playerUuid());
        assertEquals(reason, parsed.get().reason());
    }

    @Test
    void encodesAndDecodesPayloadWithoutReason() {
        UUID uuid = UUID.randomUUID();
        String json = EjectPayload.format(uuid, null);

        Optional<EjectPayload> parsed = EjectPayload.parse(json);
        assertTrue(parsed.isPresent());
        assertEquals(uuid, parsed.get().playerUuid());
        assertNull(parsed.get().reason());
    }

    @Test
    void rejectsInvalidJson() {
        assertTrue(EjectPayload.parse("").isEmpty());
        assertTrue(EjectPayload.parse("{}").isEmpty());
        assertTrue(EjectPayload.parse("not json").isEmpty());
        assertTrue(EjectPayload.parse("{\"playerUuid\":\"invalid-uuid\"}").isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew :core:test --tests nl.gzmn.playerworlds.core.control.EjectPayloadTest`
Expected: Compilation failure / Test failure.

- [ ] **Step 3: Implement `EjectPayload`**

Create `core/src/main/java/nl/gzmn/playerworlds/core/control/EjectPayload.java`:
```java
package nl.gzmn.playerworlds.core.control;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@link CommandKind#EJECT_PLAYER} and {@link CommandKind#KICK_MEMBER}.
 *
 * @param playerUuid target player to eject or route
 * @param reason optional user-facing explanation
 */
public record EjectPayload(UUID playerUuid, @Nullable String reason) {

    public EjectPayload {
        Objects.requireNonNull(playerUuid, "playerUuid");
    }

    public static String format(UUID playerUuid, @Nullable String reason) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        if (reason == null || reason.isBlank()) {
            return "{\"playerUuid\":\"" + playerUuid + "\"}";
        }
        String sanitized = reason.replace("\"", "\\\"").replace("\n", " ");
        return "{\"playerUuid\":\"" + playerUuid + "\",\"reason\":\"" + sanitized + "\"}";
    }

    public static Optional<EjectPayload> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        String trimmed = json.strip();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return Optional.empty();
        }
        int uuidKeyIdx = trimmed.indexOf("\"playerUuid\"");
        if (uuidKeyIdx == -1) {
            return Optional.empty();
        }
        int colonIdx = trimmed.indexOf(':', uuidKeyIdx);
        if (colonIdx == -1) {
            return Optional.empty();
        }
        int firstQuote = trimmed.indexOf('"', colonIdx);
        if (firstQuote == -1) {
            return Optional.empty();
        }
        int secondQuote = trimmed.indexOf('"', firstQuote + 1);
        if (secondQuote == -1) {
            return Optional.empty();
        }
        String uuidStr = trimmed.substring(firstQuote + 1, secondQuote).strip();
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        String reason = null;
        int reasonKeyIdx = trimmed.indexOf("\"reason\"");
        if (reasonKeyIdx != -1) {
            int reasonColon = trimmed.indexOf(':', reasonKeyIdx);
            if (reasonColon != -1) {
                int rFirstQuote = trimmed.indexOf('"', reasonColon);
                if (rFirstQuote != -1) {
                    int rSecondQuote = trimmed.indexOf('"', rFirstQuote + 1);
                    if (rSecondQuote != -1) {
                        reason = trimmed.substring(rFirstQuote + 1, rSecondQuote);
                    }
                }
            }
        }
        return Optional.of(new EjectPayload(playerUuid, reason));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests nl.gzmn.playerworlds.core.control.EjectPayloadTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/nl/gzmn/playerworlds/core/control/EjectPayload.java core/src/test/java/nl/gzmn/playerworlds/core/control/EjectPayloadTest.java
git commit -m "feat(core): add EjectPayload for control-plane commands"
```

---

### Task 2: Backend Control Plane Handlers (`:backend`)

**Files:**
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/control/UnloadWorldHandler.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/control/InvalidateCacheHandler.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/control/EjectPlayerHandler.java`
- Test: `backend/src/test/java/nl/gzmn/playerworlds/backend/control/BackendControlHandlersTest.java`

- [ ] **Step 1: Write unit tests for backend handlers**

Create `backend/src/test/java/nl/gzmn/playerworlds/backend/control/BackendControlHandlersTest.java`:
```java
package nl.gzmn.playerworlds.backend.control;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.backend.world.MembershipCache;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.NetworkSettings;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.Test;

class BackendControlHandlersTest {

    @Test
    void unloadWorldReturnsOkWhenWorldNotLoaded() {
        WorldRegistry registry = new WorldRegistry();
        UnloadWorldHandler handler = new UnloadWorldHandler(registry, null, null, null, null, null);

        NodeCommand command = new NodeCommand(
                1L, "node-1", WorldId.random(), 0L, CommandKind.UNLOAD_WORLD.name(), "{}",
                Instant.now(), Instant.now().plusSeconds(60), null, null, 0, null);

        CommandResult result = handler.handle(command);
        assertTrue(result.isOk());
    }

    @Test
    void invalidateCacheInvalidatesMembershipCacheAndPolicy() {
        MembershipCache membershipCache = new MembershipCache();
        WorldId worldId = WorldId.random();
        UUID owner = UUID.randomUUID();
        membershipCache.put(worldId, owner, Map.of(owner, Role.OWNER));
        assertTrue(membershipCache.isCached(worldId));

        InvalidateCacheHandler handler = new InvalidateCacheHandler(null, membershipCache, Runnable::run);

        NodeCommand command = new NodeCommand(
                2L, "node-1", worldId, null, CommandKind.INVALIDATE_CACHE.name(), "{}",
                Instant.now(), Instant.now().plusSeconds(60), null, null, 0, null);

        CommandResult result = handler.handle(command);
        assertTrue(result.isOk());
        assertFalse(membershipCache.isCached(worldId));
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.control.BackendControlHandlersTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `UnloadWorldHandler`, `InvalidateCacheHandler`, and `EjectPlayerHandler`**

Create `backend/src/main/java/nl/gzmn/playerworlds/backend/control/UnloadWorldHandler.java`:
```java
package nl.gzmn.playerworlds.backend.control;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.UnloadOutcome;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLifecycleService;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandHandler;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@link nl.gzmn.playerworlds.core.control.CommandKind#UNLOAD_WORLD} on a node.
 *
 * <p>Idempotent (CP-5): if the world is not loaded here, completes with {@code OK}.
 */
public final class UnloadWorldHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(UnloadWorldHandler.class);

    private final WorldRegistry registry;
    private final @Nullable WorldLifecycleService lifecycle;
    private final @Nullable WorldFolders folders;
    private final @Nullable PluginExecutors executors;
    private final @Nullable NodeCommandRepository nodeCommands;
    private final @Nullable Supplier<NetworkPolicy> policy;

    public UnloadWorldHandler(
            WorldRegistry registry,
            @Nullable WorldLifecycleService lifecycle,
            @Nullable WorldFolders folders,
            @Nullable PluginExecutors executors,
            @Nullable NodeCommandRepository nodeCommands,
            @Nullable Supplier<NetworkPolicy> policy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.lifecycle = lifecycle;
        this.folders = folders;
        this.executors = executors;
        this.nodeCommands = nodeCommands;
        this.policy = policy;
    }

    @Override
    public CommandResult handle(NodeCommand command) throws Exception {
        WorldId worldId = command.worldId();
        if (worldId == null) {
            return CommandResult.error("missing world_id");
        }
        Optional<LoadedWorld> found = registry.find(worldId);
        if (found.isEmpty()) {
            return CommandResult.ok();
        }
        if (lifecycle == null || folders == null || executors == null) {
            return CommandResult.ok();
        }

        LoadedWorld loaded = found.get();
        CompletableFuture<CommandResult> future = new CompletableFuture<>();

        executors.main().execute(() -> {
            try {
                // Eject online players in any dimension of this world
                for (DimensionKind dimension : DimensionKind.values()) {
                    String bukkitName = folders.bukkitWorldName(worldId, dimension);
                    World bukkitWorld = Bukkit.getWorld(bukkitName);
                    if (bukkitWorld != null) {
                        for (Player player : bukkitWorld.getPlayers()) {
                            player.sendMessage(Component.text("World is unloading...", NamedTextColor.RED));
                            if (nodeCommands != null && policy != null) {
                                executors.db().execute(() -> {
                                    try {
                                        nodeCommands.enqueue(
                                                "proxy",
                                                worldId,
                                                null,
                                                "EJECT_PLAYER",
                                                EjectPayload.format(player.getUniqueId(), "World unloaded"),
                                                policy.get().holdingTimeout(),
                                                ControlChannels.PROXY);
                                    } catch (Exception e) {
                                        log.warn("could not enqueue EJECT_PLAYER for {}", player.getUniqueId(), e);
                                    }
                                });
                            }
                        }
                    }
                }

                UnloadOutcome outcome = lifecycle.unloadOnMain(loaded);
                switch (outcome) {
                    case UnloadOutcome.Complete complete -> {
                        lifecycle.afterUnload(loaded);
                        future.complete(CommandResult.ok());
                    }
                    case UnloadOutcome.Blocked blocked -> {
                        future.complete(CommandResult.error("unload blocked on " + blocked.dimension() + ": "
                                + String.join(", ", blocked.blockers())));
                    }
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future.get();
    }
}
```

Create `backend/src/main/java/nl/gzmn/playerworlds/backend/control/InvalidateCacheHandler.java`:
```java
package nl.gzmn.playerworlds.backend.control;

import java.util.Objects;
import java.util.concurrent.Executor;
import nl.gzmn.playerworlds.backend.world.MembershipCache;
import nl.gzmn.playerworlds.core.control.CommandHandler;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.NetworkSettings;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@link nl.gzmn.playerworlds.core.control.CommandKind#INVALIDATE_CACHE}.
 */
public final class InvalidateCacheHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(InvalidateCacheHandler.class);

    private final @Nullable NetworkSettings networkSettings;
    private final MembershipCache membershipCache;
    private final Executor dbExecutor;

    public InvalidateCacheHandler(
            @Nullable NetworkSettings networkSettings,
            MembershipCache membershipCache,
            Executor dbExecutor) {
        this.networkSettings = networkSettings;
        this.membershipCache = Objects.requireNonNull(membershipCache, "membershipCache");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "dbExecutor");
    }

    @Override
    public CommandResult handle(NodeCommand command) {
        if (networkSettings != null) {
            networkSettings.invalidate();
            dbExecutor.execute(() -> {
                try {
                    networkSettings.reload();
                } catch (Exception e) {
                    log.warn("could not reload network settings after cache invalidation", e);
                }
            });
        }
        WorldId worldId = command.worldId();
        if (worldId != null) {
            membershipCache.invalidate(worldId);
        } else {
            membershipCache.clear();
        }
        return CommandResult.ok();
    }
}
```

Create `backend/src/main/java/nl/gzmn/playerworlds/backend/control/EjectPlayerHandler.java`:
```java
package nl.gzmn.playerworlds.backend.control;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.world.MembershipCache;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandHandler;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Handles {@link nl.gzmn.playerworlds.core.control.CommandKind#KICK_MEMBER} and
 * {@link nl.gzmn.playerworlds.core.control.CommandKind#EJECT_PLAYER} on a backend node.
 */
public final class EjectPlayerHandler implements CommandHandler {

    private final MembershipCache membershipCache;
    private final @Nullable WorldFolders folders;
    private final @Nullable PluginExecutors executors;
    private final @Nullable NodeCommandRepository nodeCommands;
    private final @Nullable Supplier<NetworkPolicy> policy;

    public EjectPlayerHandler(
            MembershipCache membershipCache,
            @Nullable WorldFolders folders,
            @Nullable PluginExecutors executors,
            @Nullable NodeCommandRepository nodeCommands,
            @Nullable Supplier<NetworkPolicy> policy) {
        this.membershipCache = Objects.requireNonNull(membershipCache, "membershipCache");
        this.folders = folders;
        this.executors = executors;
        this.nodeCommands = nodeCommands;
        this.policy = policy;
    }

    @Override
    public CommandResult handle(NodeCommand command) {
        WorldId worldId = command.worldId();
        if (worldId != null) {
            membershipCache.invalidate(worldId);
        }

        Optional<EjectPayload> payload = EjectPayload.parse(command.payloadJson());
        if (payload.isEmpty()) {
            return CommandResult.ok();
        }

        UUID targetUuid = payload.get().playerUuid();
        String reason = payload.get().reason();

        if (executors != null && folders != null) {
            executors.main().execute(() -> {
                Player player = Bukkit.getPlayer(targetUuid);
                if (player != null && player.isOnline()) {
                    if (worldId != null) {
                        boolean inWorld = false;
                        for (DimensionKind dim : DimensionKind.values()) {
                            String name = folders.bukkitWorldName(worldId, dim);
                            World w = Bukkit.getWorld(name);
                            if (w != null && player.getWorld().equals(w)) {
                                inWorld = true;
                                break;
                            }
                        }
                        if (!inWorld) {
                            return;
                        }
                    }
                    String msg = reason != null ? reason : "You were removed from the world.";
                    player.sendMessage(Component.text(msg, NamedTextColor.RED));
                    if (nodeCommands != null && policy != null) {
                        executors.db().execute(() -> {
                            try {
                                nodeCommands.enqueue(
                                        "proxy",
                                        worldId,
                                        null,
                                        "EJECT_PLAYER",
                                        EjectPayload.format(targetUuid, reason),
                                        policy.get().holdingTimeout(),
                                        ControlChannels.PROXY);
                            } catch (Exception ignored) {
                            }
                        });
                    }
                }
            });
        }
        return CommandResult.ok();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.control.BackendControlHandlersTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/nl/gzmn/playerworlds/backend/control/ backend/src/test/java/nl/gzmn/playerworlds/backend/control/
git commit -m "feat(backend): implement control-plane handlers for UNLOAD_WORLD, INVALIDATE_CACHE, KICK_MEMBER"
```

---

### Task 3: Backend Control Plane Runtime Wiring (`GzmnWorldsPlugin`)

**Files:**
- Modify: `backend/src/main/java/nl/gzmn/playerworlds/backend/GzmnWorldsPlugin.java`

- [ ] **Step 1: Wire `ControlPlane` startup and shutdown in `GzmnWorldsPlugin`**

In `GzmnWorldsPlugin.java`:
- Add field: `private @Nullable ControlPlane controlPlane;`
- Add field: `private @Nullable ExecutorService listenExecutor;`
- In `onEnable()`:
  - Create `listenExecutor = Executors.newSingleThreadExecutor(...)`.
  - Create `NodeCommandRepository nodeCommands = new NodeCommandRepository(openedDatabase);`.
  - Instantiate `ControlPlane.forNode(...)`.
  - Register `UNLOAD_WORLD`, `INVALIDATE_CACHE`, `KICK_MEMBER`, and `EJECT_PLAYER` handlers.
  - Start control plane: `controlPlane.start(pools.sched(), listenExecutor)`.
- In `onDisable()`:
  - Close `controlPlane.close()`.
  - Shut down `listenExecutor.shutdownNow()`.

- [ ] **Step 2: Run build and tests**

Run: `./gradlew :backend:test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/nl/gzmn/playerworlds/backend/GzmnWorldsPlugin.java
git commit -m "feat(backend): wire ControlPlane lifecycle in GzmnWorldsPlugin"
```

---

### Task 4: Proxy Control Plane Runtime Wiring (`GzmnWorldsProxyPlugin`)

**Files:**
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/control/ProxyEjectHandler.java`
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/GzmnWorldsProxyPlugin.java`
- Test: `proxy/src/test/java/nl/gzmn/playerworlds/proxy/control/ProxyEjectHandlerTest.java`

- [ ] **Step 1: Write unit test for `ProxyEjectHandler`**

Create `proxy/src/test/java/nl/gzmn/playerworlds/proxy/control/ProxyEjectHandlerTest.java`:
```java
package nl.gzmn.playerworlds.proxy.control;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import org.junit.jupiter.api.Test;

class ProxyEjectHandlerTest {

    @Test
    void skipsWhenPayloadInvalid() {
        ProxyEjectHandler handler = new ProxyEjectHandler(null, () -> "lobby");
        NodeCommand command = new NodeCommand(
                1L, "proxy", null, null, CommandKind.EJECT_PLAYER.name(), "invalid",
                Instant.now(), Instant.now().plusSeconds(60), null, null, 0, null);

        CommandResult result = handler.handle(command);
        assertTrue(result.isOk());
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew :proxy:test --tests nl.gzmn.playerworlds.proxy.control.ProxyEjectHandlerTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `ProxyEjectHandler` and wire in `GzmnWorldsProxyPlugin`**

Create `proxy/src/main/java/nl/gzmn/playerworlds/proxy/control/ProxyEjectHandler.java`:
```java
package nl.gzmn.playerworlds.proxy.control;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.core.control.CommandHandler;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@link nl.gzmn.playerworlds.core.control.CommandKind#EJECT_PLAYER} on the proxy.
 *
 * <p>Transfers the player back to the configured lobby server.
 */
public final class ProxyEjectHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ProxyEjectHandler.class);

    private final @Nullable ProxyServer proxy;
    private final Supplier<String> lobbyServerSupplier;

    public ProxyEjectHandler(@Nullable ProxyServer proxy, Supplier<String> lobbyServerSupplier) {
        this.proxy = proxy;
        this.lobbyServerSupplier = Objects.requireNonNull(lobbyServerSupplier, "lobbyServerSupplier");
    }

    @Override
    public CommandResult handle(NodeCommand command) {
        Optional<EjectPayload> parsed = EjectPayload.parse(command.payloadJson());
        if (parsed.isEmpty()) {
            return CommandResult.ok();
        }
        if (proxy == null) {
            return CommandResult.ok();
        }

        UUID targetUuid = parsed.get().playerUuid();
        Optional<Player> player = proxy.getPlayer(targetUuid);
        if (player.isEmpty()) {
            return CommandResult.ok();
        }

        String lobbyName = lobbyServerSupplier.get();
        Optional<RegisteredServer> lobby = proxy.getServer(lobbyName);
        if (lobby.isEmpty()) {
            log.warn("cannot eject player {}: lobby server '{}' not registered", targetUuid, lobbyName);
            return CommandResult.error("lobby server '" + lobbyName + "' not registered");
        }

        player.get().createConnectionRequest(lobby.get()).fireAndForget();
        log.info("routed player {} back to lobby '{}'", targetUuid, lobbyName);
        return CommandResult.ok();
    }
}
```

In `GzmnWorldsProxyPlugin.java`:
- Add field `private @Nullable ControlPlane controlPlane;`
- Add field `private @Nullable ExecutorService listenExecutor;`
- In `onProxyInitialize()`:
  - Create `listenExecutor = Executors.newSingleThreadExecutor(...)`.
  - Create `NodeCommandRepository nodeCommands = new NodeCommandRepository(openedDatabase);`.
  - Create `ControlPlane proxyPlane = ControlPlane.forProxy(config.lobbyServer(), openedDatabase.settings(), nodeCommands, loadedPolicy.controlPollInterval(), loadedPolicy.controlClaimTimeout());`.
  - Register `proxyPlane.register(CommandKind.EJECT_PLAYER, new ProxyEjectHandler(proxy, config::lobbyServer));`.
  - Start `proxyPlane.start(pools.sched(), listenExecutor);`.
  - Pass `nodeCommands` into `WorldCommand`.
- In `onProxyShutdown()`:
  - Close `controlPlane.close()`.
  - Shut down `listenExecutor.shutdownNow()`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :proxy:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add proxy/src/main/java/nl/gzmn/playerworlds/proxy/ proxy/src/test/java/nl/gzmn/playerworlds/proxy/
git commit -m "feat(proxy): wire ControlPlane and ProxyEjectHandler on Velocity proxy"
```

---

### Task 5: Proxy Command Emission in `WorldCommand`

**Files:**
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldCommand.java`
- Test: `proxy/src/test/java/nl/gzmn/playerworlds/proxy/command/WorldCommandTest.java`

- [ ] **Step 1: Update `WorldCommand` constructor and add `leave` to `BACKEND_SUBCOMMANDS`**

In `WorldCommand.java`:
- Update `BACKEND_SUBCOMMANDS = List.of("leave");`
- Accept `NodeCommandRepository nodeCommands` in constructor.
- In `delete()`:
  - After `worlds.transitionState(world.id(), WorldState.READY, WorldState.ARCHIVED)`:
  - If `world.assignedNode() != null`:
    - `nodeCommands.enqueue(world.assignedNode(), world.id(), world.generation(), CommandKind.UNLOAD_WORLD.name(), NodeCommand.EMPTY_PAYLOAD, current.holdingTimeout(), ControlChannels.forNode(world.assignedNode()));`
  - Else:
    - Broadcast to all alive nodes:
      ```java
      for (var alive : registry.aliveNodes(current.deadAfter())) {
          nodeCommands.enqueue(alive.nodeId(), world.id(), world.generation(), CommandKind.UNLOAD_WORLD.name(), NodeCommand.EMPTY_PAYLOAD, current.holdingTimeout(), ControlChannels.forNode(alive.nodeId()));
      }
      ```
- In `kick()` and `promote()`:
  - Enqueue `INVALIDATE_CACHE` and `KICK_MEMBER` to alive nodes / assigned node.

- [ ] **Step 2: Run tests to verify**

Run: `./gradlew :proxy:test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldCommand.java
git commit -m "feat(proxy): emit UNLOAD_WORLD, INVALIDATE_CACHE, and KICK_MEMBER from WorldCommand"
```

---

### Task 6: Return Leg & `/world leave` Integration (FR-11, FR-12)

**Files:**
- Modify: `backend/src/main/java/nl/gzmn/playerworlds/backend/node/TransferJoinListener.java`
- Modify: `backend/src/main/java/nl/gzmn/playerworlds/backend/command/PworldCommand.java`
- Test: `backend/src/test/java/nl/gzmn/playerworlds/backend/smoke/SmokeTest.java`

- [ ] **Step 1: Update `TransferJoinListener` to bounce refused players to lobby**

In `TransferJoinListener.java`:
- Accept `NodeCommandRepository nodeCommands` in constructor.
- In refusal branches (missing transfer, node mismatch, generation mismatch, load failure):
  - Send message to player.
  - Enqueue `EJECT_PLAYER` to `ControlChannels.PROXY` so proxy pulls player back to lobby server instead of leaving them stranded.

- [ ] **Step 2: Add `leave` subcommand to `PworldCommand` (handling `/pworld leave` and `/world leave`)**

In `PworldCommand.java`:
- Add `"leave"` subcommand.
- When player executes `/pworld leave` or `/world leave`:
  - Verify player is in a world (or holding area).
  - Enqueue `EJECT_PLAYER` to `ControlChannels.PROXY`.
  - Send message: `"Returning to lobby..."`.

- [ ] **Step 3: Run backend tests**

Run: `./gradlew :backend:test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/nl/gzmn/playerworlds/backend/node/TransferJoinListener.java backend/src/main/java/nl/gzmn/playerworlds/backend/command/PworldCommand.java
git commit -m "feat(backend): implement return leg for /world leave and FR-11 handoff refusals"
```

---

### Task 7: Full Repository Verification & Build Gates

**Files:**
- Verification only

- [ ] **Step 1: Run complete `./gradlew check`**

Run: `./gradlew check`
Expected: PASS across all modules (`:core`, `:backend`, `:proxy`, `:testing`, `:tools`).

- [ ] **Step 2: Run full build and jar verification**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 3: Update `docs/plans/NEXT-STEPS.md` to reflect Milestone 5 completion**

Modify `docs/plans/NEXT-STEPS.md` moving control-plane handlers and return leg from "Still to do" to "Landed".

- [ ] **Step 4: Commit**

```bash
git add docs/plans/NEXT-STEPS.md
git commit -m "docs: mark Milestone 5 control plane and return leg complete"
```
