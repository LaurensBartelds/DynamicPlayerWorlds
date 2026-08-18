# World Menu GUI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the `/worlds` and `/world` inventory GUI system for DynamicPlayerWorlds, allowing players to manage worlds, members, settings, storage, invites, bans, and public browsing across Velocity proxy and Paper backends without duplicating business validation rules.

**Architecture:** Domain logic and mutations remain centralized on the Velocity proxy inside `WorldActions` (extracted from `WorldCommand`). A secure plugin messaging protocol (`gzmn:menu`) in `:core` (`MenuCodec`, `MenuIntent`, `MenuResult`, `OpenMenu`) transfers player actions and results between backend and proxy. Backend nodes read database state directly on asynchronous worker threads to render responsive Bukkit inventory GUIs (`MenuService`, `MenuListener`, screen hierarchy) while routing all state mutations as `MenuIntent`s to the proxy. A `node.mode: gui-only` configuration permits lobby servers to render menus without publishing `worlds_node` placement heartbeats.

**Tech Stack:** Java 25, Paper API 1.21.4, Velocity API 4.0.0, Adventure Text, PostgreSQL (HikariCP / JDBC), MockBukkit, JUnit 5, AssertJ, ArchUnit.

**Spec:** [`docs/superpowers/specs/2026-08-18-world-menu-gui-design.md`](file:///c:/Users/Laurens%20Bartelds/Documents/Github/DynamicPlayerWorlds/docs/superpowers/specs/2026-08-18-world-menu-gui-design.md)

## Global Constraints & Invariants
- **Single Source of Truth:** All mutation rules (caps, quotas, permissions, placement, generation checks) execute strictly on the proxy in `WorldActions`.
- **Fast Direct Reads:** `:backend` reads world data directly from PostgreSQL on `PluginExecutors.db()` and builds plain data models before dispatching to the main thread for UI rendering (NFR-2).
- **Security Rule 1 (Source Check):** Proxy accepts `MenuIntent` only when `PluginMessageEvent.getSource() instanceof ServerConnection`. Client-originated packets (`Player`) are immediately dropped.
- **Security Rule 2 (Connection Identity):** `MenuIntent` carries no player UUID. Identity is derived strictly from `((ServerConnection) source).getPlayer()`.
- **Command Fallback:** Bare `/world` or `/worlds` sends `OpenMenu` to backend; if timed out or unsupported, it falls back to printing usage chat text.
- **Softened Confirmation (FR-27):** GUI uses click-to-confirm screens (`ConfirmMenu`) for destructive actions (archival/transfer/kick/ban).
- **Architecture Integrity:** `:core` must never depend on Paper or Velocity. No blocking work on the main thread. No wall clock reads for lease/expiry decisions.

---

### Task 1: `WorldActions` Extraction in `:proxy`

**Files:**
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/ActionResult.java`
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldActions.java`
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldCommand.java`
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/GzmnWorldsProxyPlugin.java`
- Create: `proxy/src/test/java/nl/gzmn/playerworlds/proxy/command/WorldActionsTest.java`
- Keep unchanged: `proxy/src/test/java/nl/gzmn/playerworlds/proxy/command/WorldCommandTest.java`

- [ ] **Step 1: Create `ActionResult.java` in `:proxy`**

```java
package nl.gzmn.playerworlds.proxy.command;

import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * Result of executing a domain action via {@link WorldActions}.
 */
public sealed interface ActionResult {

    Component message();

    record Ok(Component message) implements ActionResult {
        public Ok {
            Objects.requireNonNull(message, "message");
        }
    }

    record Failed(String code, Component message) implements ActionResult {
        public Failed {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    static ActionResult success(Component message) {
        return new Ok(message);
    }

    static ActionResult failure(String code, Component message) {
        return new Failed(code, message);
    }
}
```

- [ ] **Step 2: Create `WorldActionsTest.java`**

```java
package nl.gzmn.playerworlds.proxy.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.NodeRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.db.WorldBanRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.proxy.node.NodeRegistry;
import nl.gzmn.playerworlds.proxy.node.Placement;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorldActionsTest {

    private Database database;
    private PluginExecutors executors;
    private PlayerWorldRepository worlds;
    private MembershipRepository membership;
    private TransferRequestRepository transferRequests;
    private WorldBanRepository bans;
    private PlayerNameRepository names;
    private NodeRepository nodeRepo;
    private NodeRegistry registry;
    private PendingTransferRepository transfers;
    private NodeCommandRepository nodeCommands;
    private NetworkPolicy policy;
    private ProxyServer proxy;
    private WorldActions actions;

    private Map<UUID, List<Component>> messagesByPlayer;
    private Map<UUID, Player> playersByUuid;
    private Map<String, Player> playersByName;
    private Map<String, RegisteredServer> registeredServers;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        executors = PluginExecutors.create(2, 2, Runnable::run);
        policy = NetworkPolicy.defaults();

        messagesByPlayer = new ConcurrentHashMap<>();
        playersByUuid = new ConcurrentHashMap<>();
        playersByName = new ConcurrentHashMap<>();
        registeredServers = new ConcurrentHashMap<>();
        proxy = mockProxy(playersByUuid, playersByName, registeredServers);

        worlds = new PlayerWorldRepository(database);
        membership = new MembershipRepository(database);
        transferRequests = new TransferRequestRepository(database);
        bans = new WorldBanRepository(database);
        names = new PlayerNameRepository(database);
        nodeRepo = new NodeRepository(database);
        registry = new NodeRegistry(proxy, nodeRepo);
        transfers = new PendingTransferRepository(database);
        nodeCommands = new NodeCommandRepository(database);

        actions = new WorldActions(
                proxy,
                executors,
                worlds,
                membership,
                transferRequests,
                bans,
                names,
                transfers,
                registry,
                new Placement(nodeRepo, worlds),
                nodeCommands,
                () -> policy);
    }

    @AfterEach
    void tearDown() {
        executors.close();
        database.close();
    }

    @Test
    void createWorldEnforcesCap() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        for (int i = 0; i < policy.maxWorldsPerPlayer(); i++) {
            worlds.create(WorldId.random(), owner, "world" + i, 12345L, 5000, nl.gzmn.playerworlds.core.model.Visibility.PRIVATE);
        }

        ActionResult result = actions.create(player, "extra-world", null).get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("already own " + policy.maxWorldsPerPlayer() + " worlds");
    }

    private Player mockPlayer(UUID uuid, String name) {
        return (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Player.class},
                (proxyObj, method, args) -> {
                    if (method.getName().equals("getUniqueId")) return uuid;
                    if (method.getName().equals("getUsername")) return name;
                    if (method.getName().equals("hasPermission")) return true;
                    if (method.getName().equals("sendMessage")) {
                        messagesByPlayer.computeIfAbsent(uuid, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                                .add((Component) args[0]);
                        return null;
                    }
                    return null;
                });
    }

    private ProxyServer mockProxy(
            Map<UUID, Player> byUuid, Map<String, Player> byName, Map<String, RegisteredServer> servers) {
        return (ProxyServer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ProxyServer.class},
                (proxyObj, method, args) -> {
                    if (method.getName().equals("getPlayer")) {
                        if (args[0] instanceof UUID id) return Optional.ofNullable(byUuid.get(id));
                        if (args[0] instanceof String n) return Optional.ofNullable(byName.get(n));
                    }
                    if (method.getName().equals("getServer")) {
                        return Optional.ofNullable(servers.get(args[0]));
                    }
                    if (method.getName().equals("getAllServers")) {
                        return servers.values();
                    }
                    return null;
                });
    }
}
```

- [ ] **Step 3: Implement `WorldActions.java` and refactor `WorldCommand.java`**
Extract mutation and validation logic from `WorldCommand` into `WorldActions`, making `WorldCommand` a thin Brigadier layer calling `WorldActions` and forwarding chat responses.
- [ ] **Step 4: Run existing `WorldCommandTest` and new `WorldActionsTest`**
Run: `./gradlew :proxy:test`
Expected: ALL proxy tests pass, including all 22+ tests in `WorldCommandTest` unchanged.
- [ ] **Step 5: Commit**
```bash
git add proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/ActionResult.java proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldActions.java proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldCommand.java proxy/src/main/java/nl/gzmn/playerworlds/proxy/GzmnWorldsProxyPlugin.java proxy/src/test/java/nl/gzmn/playerworlds/proxy/command/WorldActionsTest.java
git commit -m "refactor: extract WorldActions domain logic from WorldCommand"
```

---

### Task 2: `:core` Menu Protocol & Codec

**Files:**
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/menu/MenuChannels.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/menu/OpenMenu.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/menu/FailureCode.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/menu/MenuIntent.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/menu/MenuResult.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/menu/MenuCodec.java`
- Create: `core/src/test/java/nl/gzmn/playerworlds/core/menu/MenuCodecTest.java`

- [ ] **Step 1: Write `MenuChannels`, `OpenMenu`, `FailureCode`, `MenuIntent`, `MenuResult` in `core.menu`**
Declare channel identifier `"gzmn:menu"` and sealed interfaces for intents and results.
`MenuIntent` records:
- `JoinWorld(WorldId worldId)`
- `CreateWorld(String name, @Nullable String seed)`
- `ArchiveWorld(String worldName)`
- `RestoreWorld(String worldName)`
- `InviteMember(String targetName, @Nullable WorldId worldId)`
- `KickMember(String targetName, @Nullable WorldId worldId)`
- `PromoteMember(String targetName, @Nullable WorldId worldId)`
- `SetVisibility(WorldId worldId, Visibility visibility)`
- `SetSetting(WorldId worldId, String settingKey, String value)`
- `BanPlayer(String targetName, @Nullable WorldId worldId, @Nullable String reason)`
- `UnbanPlayer(String targetName, @Nullable WorldId worldId)`
- `RequestTransfer(String targetName, @Nullable WorldId worldId)`
- `AcceptTransfer(String ownerName)`
- `DeclineTransfer(String ownerName)`
- `AcceptInvite(String ownerName)`

- [ ] **Step 2: Write failing unit test `MenuCodecTest.java`**
Test encode and decode round-trips for every intent variant, `OpenMenu`, `MenuResult.Ok`, `MenuResult.Failed`, null/optional fields, and corrupt payloads.
- [ ] **Step 3: Run test to verify it fails**
Run: `./gradlew :core:test --tests nl.gzmn.playerworlds.core.menu.MenuCodecTest`
Expected: FAIL
- [ ] **Step 4: Implement `MenuCodec.java`**
Implement binary serialization using `ByteArrayOutputStream`/`DataOutputStream` and `ByteArrayInputStream`/`DataInputStream`.
- [ ] **Step 5: Run test to verify it passes**
Run: `./gradlew :core:test --tests nl.gzmn.playerworlds.core.menu.MenuCodecTest`
Expected: PASS
- [ ] **Step 6: Commit**
```bash
git add core/src/main/java/nl/gzmn/playerworlds/core/menu/ core/src/test/java/nl/gzmn/playerworlds/core/menu/
git commit -m "feat(core): implement menu wire protocol and binary MenuCodec"
```

---

### Task 3: GUI-Only Mode in `:core` and `:backend`

**Files:**
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/config/NodeMode.java`
- Modify: `core/src/main/java/nl/gzmn/playerworlds/core/config/NodeConfig.java`
- Modify: `backend/src/main/java/nl/gzmn/playerworlds/backend/config/BackendConfig.java`
- Modify: `backend/src/main/java/nl/gzmn/playerworlds/backend/GzmnWorldsPlugin.java`
- Create: `backend/src/test/java/nl/gzmn/playerworlds/backend/config/BackendConfigModeTest.java`
- Modify: `backend/src/test/java/nl/gzmn/playerworlds/backend/PluginSmokeTest.java`

- [ ] **Step 1: Write `NodeMode.java` and update `NodeConfig.java`**
Define `NodeMode` with enum values `WORLDS` (default) and `GUI_ONLY`.
- [ ] **Step 2: Update `BackendConfig.java`**
Parse `node.mode` in `BackendConfig.node(...)`, defaulting to `NodeMode.WORLDS`.
- [ ] **Step 3: Write failing unit tests in `BackendConfigModeTest.java` and `PluginSmokeTest.java`**
Assert that `gui-only` configuration suppresses `NodeHeartbeat`, `WorldRegistry`, `LeaseCoordinator`, and `MaintenanceTask`.
- [ ] **Step 4: Update `GzmnWorldsPlugin.java`**
Branch during `onEnable()`: when mode is `GUI_ONLY`, connect to DB and start executors, but skip heartbeat registration and lifecycle workers.
- [ ] **Step 5: Run tests to verify they pass**
Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.config.BackendConfigModeTest --tests nl.gzmn.playerworlds.backend.PluginSmokeTest`
Expected: PASS
- [ ] **Step 6: Commit**
```bash
git add core/src/main/java/nl/gzmn/playerworlds/core/config/NodeMode.java core/src/main/java/nl/gzmn/playerworlds/core/config/NodeConfig.java backend/src/main/java/nl/gzmn/playerworlds/backend/config/BackendConfig.java backend/src/main/java/nl/gzmn/playerworlds/backend/GzmnWorldsPlugin.java backend/src/test/java/nl/gzmn/playerworlds/backend/config/BackendConfigModeTest.java backend/src/test/java/nl/gzmn/playerworlds/backend/PluginSmokeTest.java
git commit -m "feat(backend): add node.mode gui-only support and heartbeat suppression"
```

---

### Task 4: Proxy Channel Listener & Security (`MenuChannelListener`)

**Files:**
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/MenuChannelListener.java`
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/GzmnWorldsProxyPlugin.java`
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldCommand.java`
- Create: `proxy/src/test/java/nl/gzmn/playerworlds/proxy/menu/MenuChannelListenerTest.java`

- [ ] **Step 1: Write failing test `MenuChannelListenerTest.java`**
Verify:
1. Messages from client source (`Player`) are rejected and dropped.
2. Messages from `ServerConnection` extract caller `Player` from connection and invoke `WorldActions`.
3. Action completion sends encoded `MenuResult` back over the server connection.
- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew :proxy:test --tests nl.gzmn.playerworlds.proxy.menu.MenuChannelListenerTest`
Expected: FAIL
- [ ] **Step 3: Implement `MenuChannelListener.java` and wire into `GzmnWorldsProxyPlugin`**
Register channel identifier `gzmn:menu` and implement `PluginMessageEvent` subscriber with security source check and `WorldActions` dispatch.
Update `WorldCommand` and `GzmnWorldsProxyPlugin` to route `/worlds` and bare `/world` to `OpenMenu`, falling back to usage chat text if no backend GUI is available.
- [ ] **Step 4: Run tests to verify they pass**
Run: `./gradlew :proxy:test`
Expected: PASS
- [ ] **Step 5: Commit**
```bash
git add proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/MenuChannelListener.java proxy/src/main/java/nl/gzmn/playerworlds/proxy/GzmnWorldsProxyPlugin.java proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldCommand.java proxy/src/test/java/nl/gzmn/playerworlds/proxy/menu/MenuChannelListenerTest.java
git commit -m "feat(proxy): implement MenuChannelListener with source security check and command fallback"
```

---

### Task 5: Backend Menu Infrastructure (`MenuService`, `MenuChannel`, `MenuListener`)

**Files:**
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/MenuHolder.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/GuiScreen.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/MenuChannel.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/MenuService.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/MenuListener.java`
- Modify: `backend/src/main/java/nl/gzmn/playerworlds/backend/GzmnWorldsPlugin.java`
- Create: `backend/src/test/java/nl/gzmn/playerworlds/backend/gui/MenuChannelTest.java`
- Create: `backend/src/test/java/nl/gzmn/playerworlds/backend/gui/MenuServiceTest.java`

- [ ] **Step 1: Write `MenuHolder` and `GuiScreen` interfaces**
`GuiScreen` defines:
- `Inventory render(Player player)`
- `void handleClick(Player player, int slot, ClickType clickType)`
- `void refresh(Player player)`
- [ ] **Step 2: Write failing unit tests `MenuChannelTest.java` and `MenuServiceTest.java`**
Test `sendIntent` correlation ID matching, timeout handling, and `MenuListener` event cancellation.
- [ ] **Step 3: Run tests to verify they fail**
Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.gui.*`
Expected: FAIL
- [ ] **Step 4: Implement `MenuChannel`, `MenuService`, and `MenuListener`**
- `MenuChannel`: registers incoming/outgoing plugin channels, manages in-flight futures and timeout callbacks.
- `MenuListener`: cancels unwanted clicks/drags and cleans up closed menus.
- `MenuService`: loads data via `PluginExecutors.db()` and updates inventories via `PluginExecutors.mainScheduler()`.
- [ ] **Step 5: Run tests to verify they pass**
Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.gui.*`
Expected: PASS
- [ ] **Step 6: Commit**
```bash
git add backend/src/main/java/nl/gzmn/playerworlds/backend/gui/ backend/src/test/java/nl/gzmn/playerworlds/backend/gui/
git commit -m "feat(backend): implement MenuService, MenuChannel and MenuListener"
```

---

### Task 6: Core Screen Implementations (`MainMenu`, `MyWorldsMenu`, `WorldMenu`, `StorageMenu`, `ConfirmMenu`)

**Files:**
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/ItemUtil.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/MainMenu.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/MyWorldsMenu.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/WorldMenu.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/StorageMenu.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/ConfirmMenu.java`
- Create: `backend/src/test/java/nl/gzmn/playerworlds/backend/gui/screen/CoreScreensTest.java`

- [ ] **Step 1: Create `ItemUtil.java` helper**
Utility for building Bukkit `ItemStack`s with material, display name, lore lines, custom flags, and glowing/enchantment effects.
- [ ] **Step 2: Write failing unit test `CoreScreensTest.java`**
Verify with MockBukkit that:
1. `MainMenu` renders slots for My Worlds, Invites, Browse, and Storage summary.
2. `MyWorldsMenu` lists worlds, click-to-join, right-click to manage, and create button.
3. `WorldMenu` displays settings, members, visibility toggle, storage, archive, and join buttons.
4. `ConfirmMenu` executes confirmed action callback only on deliberate confirm slot click.
- [ ] **Step 3: Run test to verify it fails**
Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.gui.screen.CoreScreensTest`
Expected: FAIL
- [ ] **Step 4: Implement core screens**
Implement `MainMenu`, `MyWorldsMenu`, `WorldMenu`, `StorageMenu`, and `ConfirmMenu`.
- [ ] **Step 5: Run test to verify it passes**
Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.gui.screen.CoreScreensTest`
Expected: PASS
- [ ] **Step 6: Commit**
```bash
git add backend/src/main/java/nl/gzmn/playerworlds/backend/gui/ItemUtil.java backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/MainMenu.java backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/MyWorldsMenu.java backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/WorldMenu.java backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/StorageMenu.java backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/ConfirmMenu.java backend/src/test/java/nl/gzmn/playerworlds/backend/gui/screen/CoreScreensTest.java
git commit -m "feat(backend): implement core inventory GUI screens"
```

---

### Task 7: Member, Social & Settings Screens (`MembersMenu`, `InvitesMenu`, `BansMenu`, `SettingsMenu`)

**Files:**
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/MembersMenu.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/InvitesMenu.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/BansMenu.java`
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/SettingsMenu.java`
- Create: `backend/src/test/java/nl/gzmn/playerworlds/backend/gui/screen/SocialAndSettingsScreensTest.java`

- [ ] **Step 1: Write failing unit test `SocialAndSettingsScreensTest.java`**
Verify:
1. `MembersMenu` renders member list, kick, promote.
2. `InvitesMenu` renders pending invites with accept/decline.
3. `BansMenu` renders bans with unban actions.
4. `SettingsMenu` renders toggle buttons for PVP, difficulty, time/weather locks.
- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.gui.screen.SocialAndSettingsScreensTest`
Expected: FAIL
- [ ] **Step 3: Implement social and settings screens**
Implement `MembersMenu`, `InvitesMenu`, `BansMenu`, and `SettingsMenu`.
- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.gui.screen.SocialAndSettingsScreensTest`
Expected: PASS
- [ ] **Step 5: Commit**
```bash
git add backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/MembersMenu.java backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/InvitesMenu.java backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/BansMenu.java backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/SettingsMenu.java backend/src/test/java/nl/gzmn/playerworlds/backend/gui/screen/SocialAndSettingsScreensTest.java
git commit -m "feat(backend): implement members, invites, bans and settings screens"
```

---

### Task 8: Public Browse Screen (`BrowseMenu`)

**Files:**
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/BrowseMenu.java`
- Create: `backend/src/test/java/nl/gzmn/playerworlds/backend/gui/screen/BrowseMenuTest.java`

- [ ] **Step 1: Write failing unit test `BrowseMenuTest.java`**
Verify pagination and click-to-join on public world entries.
- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.gui.screen.BrowseMenuTest`
Expected: FAIL
- [ ] **Step 3: Implement `BrowseMenu.java`**
Implement paginated public worlds browsing.
- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.gui.screen.BrowseMenuTest`
Expected: PASS
- [ ] **Step 5: Commit**
```bash
git add backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/BrowseMenu.java backend/src/test/java/nl/gzmn/playerworlds/backend/gui/screen/BrowseMenuTest.java
git commit -m "feat(backend): implement paginated public BrowseMenu"
```

---

### Task 9: Full Suite Verification & Static Analysis

**Files:**
- Modify any files needing spotless formatting or error-prone cleanups.

- [ ] **Step 1: Run spotlessApply**
Run: `./gradlew spotlessApply`
- [ ] **Step 2: Run full build and test check**
Run: `./gradlew check`
Expected: BUILD SUCCESSFUL with 0 errors and all ArchUnit / licensee / test rules green.
- [ ] **Step 3: Commit**
```bash
git commit -am "chore: format and verify full build check suite"
```
