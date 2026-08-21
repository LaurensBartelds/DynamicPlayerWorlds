# Standalone Zero-Database Lobby Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone, zero-database lobby plugin (`:lobby`) targeting Java 21 and Paper 1.21+, driven entirely by proxy-side menu view construction and plugin messaging (`gzmn:menu`).

**Architecture:** The lobby server acts as a stateless, dumb-terminal chest GUI renderer with zero database credentials or heavy dependencies. All data queries, quota evaluations, permissions, and business logic run on the Velocity proxy (`:proxy`), which serializes menu screen models (`RenderMenuPayload`) and sends them over `gzmn:menu`. Clicks are returned as `MenuClickIntent` and processed by `WorldActions`.

**Tech Stack:** Java 21 toolchain (Lobby), Java 25 (Backend/Core), Gradle Kotlin DSL, Paper API (1.21+), Velocity API (4.0.0), Adventure Text & Component API, MockBukkit.

---

### File Structure Map

```text
DynamicPlayerWorlds/
├── core/
│   └── src/main/java/nl/gzmn/playerworlds/core/menu/
│       ├── MenuItemDescriptor.java             # Data descriptor for a single GUI item slot
│       ├── RenderMenuPayload.java              # Full screen view payload (title, size, items)
│       ├── MenuClickIntent.java                # Click event payload (actionTag, sequence)
│       ├── CloseMenuMessage.java               # Instruction to close player's menu
│       ├── MenuClosedNotice.java               # Notification from lobby when player closes menu
│       └── MenuCodec.java                      # Binary serialization/deserialization for all messages
│   └── src/test/java/nl/gzmn/playerworlds/core/menu/
│       └── MenuCodecTest.java                  # Unit tests for message serialization
├── proxy/
│   └── src/main/java/nl/gzmn/playerworlds/proxy/menu/
│       ├── MenuViewService.java                # Proxy-side service managing async data & screen builds
│       ├── ScreenBuilder.java                  # Interface/builders for each screen type
│       ├── screens/
│       │   ├── MainScreenBuilder.java          # Overview, world count, quota bar, invites
│       │   ├── MyWorldsScreenBuilder.java      # Paginated player worlds grid
│       │   ├── WorldDetailScreenBuilder.java   # Join, settings, members, archive buttons
│       │   ├── SettingsScreenBuilder.java      # Settings toggles
│       │   ├── MembersScreenBuilder.java       # Member heads & role management
│       │   ├── StorageScreenBuilder.java       # Quota breakdown
│       │   ├── InvitesScreenBuilder.java       # Pending invites & transfers
│       │   ├── BansScreenBuilder.java          # World bans
│       │   ├── BrowseScreenBuilder.java        # Public worlds browser
│       │   └── ConfirmScreenBuilder.java       # Reversible action confirmation
│       └── MenuChannelListener.java            # Velocity plugin message handler & dispatcher
│   └── src/test/java/nl/gzmn/playerworlds/proxy/menu/
│       └── MenuViewServiceTest.java            # Tests for screen generation and click routing
├── lobby/
│   ├── build.gradle.kts                        # Java 21 toolchain, paper-api 1.21 compileOnly, :core
│   └── src/main/
│       ├── resources/
│       │   └── plugin.yml                      # Bukkit plugin descriptor (gzmn-worlds-lobby)
│       └── java/nl/gzmn/playerworlds/lobby/
│           ├── LobbyPlugin.java                # Entry point, channel registration, listener setup
│           ├── LobbyMenuChannel.java           # PluginMessageListener for incoming RenderMenuPayload
│           ├── LobbyMenuListener.java          # InventoryClick / Drag / Close event listener
│           └── LobbyItemUtil.java              # Converts MenuItemDescriptor to Bukkit ItemStack
│   └── src/test/java/nl/gzmn/playerworlds/lobby/
│       └── LobbyPluginTest.java                # MockBukkit test verifying GUI rendering & clicks
└── settings.gradle.kts                         # Includes :lobby module
```

---

### Task 1: Protocol Extension in `:core`

**Files:**
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/menu/MenuItemDescriptor.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/menu/RenderMenuPayload.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/menu/MenuClickIntent.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/menu/CloseMenuMessage.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/menu/MenuClosedNotice.java`
- Modify: `core/src/main/java/nl/gzmn/playerworlds/core/menu/MenuCodec.java`
- Modify: `core/src/test/java/nl/gzmn/playerworlds/core/menu/MenuCodecTest.java`

- [ ] **Step 1: Write failing unit tests for new protocol messages in `MenuCodecTest`**
```java
@Test
void roundTripsRenderMenuPayload() {
    List<MenuItemDescriptor> items = List.of(
        new MenuItemDescriptor(0, "GRASS_BLOCK", 1, "§aWorld 1", List.of("§7Lore line 1", "§7Lore line 2"), null, "ACTION:JOIN:test-id"),
        new MenuItemDescriptor(4, "PLAYER_HEAD", 1, "§eProfile", List.of("§7Player info"), UUID.randomUUID(), "NAV:PROFILE")
    );
    RenderMenuPayload payload = new RenderMenuPayload(1001L, "MAIN", "§8Main Menu", 54, items);
    byte[] encoded = MenuCodec.encodeRenderMenu(payload);
    RenderMenuPayload decoded = MenuCodec.decodeRenderMenu(encoded);

    assertThat(decoded.correlationId()).isEqualTo(1001L);
    assertThat(decoded.screenType()).isEqualTo("MAIN");
    assertThat(decoded.title()).isEqualTo("§8Main Menu");
    assertThat(decoded.size()).isEqualTo(54);
    assertThat(decoded.items()).hasSize(2);
    assertThat(decoded.items().get(0).materialName()).isEqualTo("GRASS_BLOCK");
    assertThat(decoded.items().get(0).actionTag()).isEqualTo("ACTION:JOIN:test-id");
}

@Test
void roundTripsMenuClickIntent() {
    MenuClickIntent intent = new MenuClickIntent(2002L, "ACTION:JOIN:test-id", 5);
    byte[] encoded = MenuCodec.encodeClickIntent(intent);
    MenuClickIntent decoded = MenuCodec.decodeClickIntent(encoded);

    assertThat(decoded.correlationId()).isEqualTo(2002L);
    assertThat(decoded.actionTag()).isEqualTo("ACTION:JOIN:test-id");
    assertThat(decoded.screenSequence()).isEqualTo(5);
}

@Test
void roundTripsCloseMenuMessageAndClosedNotice() {
    CloseMenuMessage closeMsg = new CloseMenuMessage(3003L);
    assertThat(MenuCodec.decodeCloseMenu(MenuCodec.encodeCloseMenu(closeMsg)).correlationId()).isEqualTo(3003L);

    MenuClosedNotice notice = new MenuClosedNotice(4004L);
    assertThat(MenuCodec.decodeClosedNotice(MenuCodec.encodeClosedNotice(notice)).correlationId()).isEqualTo(4004L);
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew :core:test --tests "nl.gzmn.playerworlds.core.menu.MenuCodecTest"`
Expected: Compilation failure due to missing classes and methods.

- [ ] **Step 3: Implement data records and binary codec methods**
Create `MenuItemDescriptor.java`, `RenderMenuPayload.java`, `MenuClickIntent.java`, `CloseMenuMessage.java`, `MenuClosedNotice.java`.
Extend `MenuCodec.java` with `MSG_RENDER_MENU = 5`, `MSG_CLOSE_MENU = 6`, `MSG_CLICK_INTENT = 7`, `MSG_CLOSED_NOTICE = 8`, and their respective encode/decode routines.

- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew :core:test --tests "nl.gzmn.playerworlds.core.menu.MenuCodecTest"`
Expected: All tests PASS.

- [ ] **Step 5: Commit**
```bash
git add core/src/main/java/nl/gzmn/playerworlds/core/menu/ core/src/test/java/nl/gzmn/playerworlds/core/menu/
git commit -m "feat(core): add menu rendering and click protocol messages"
```

---

### Task 2: Proxy Screen Builders & `MenuViewService` in `:proxy`

**Files:**
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/screens/MainScreenBuilder.java`
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/screens/MyWorldsScreenBuilder.java`
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/screens/WorldDetailScreenBuilder.java`
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/screens/SettingsScreenBuilder.java`
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/screens/MembersScreenBuilder.java`
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/screens/StorageScreenBuilder.java`
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/screens/InvitesScreenBuilder.java`
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/screens/BansScreenBuilder.java`
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/screens/BrowseScreenBuilder.java`
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/screens/ConfirmScreenBuilder.java`
- Create: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/MenuViewService.java`
- Create: `proxy/src/test/java/nl/gzmn/playerworlds/proxy/menu/MenuViewServiceTest.java`

- [ ] **Step 1: Write unit tests for `MenuViewService` and screen builders**
```java
@Test
void buildsMainScreenPayloadCorrectly() {
    MenuViewService service = new MenuViewService(worldRepo, memberRepo, transferRepo, banRepo, nameRepo, policySupplier, dbExecutor);
    RenderMenuPayload payload = service.buildMainMenu(playerUuid, 1001L).join();

    assertThat(payload.screenType()).isEqualTo("MAIN");
    assertThat(payload.size()).isEqualTo(27);
    assertThat(payload.items()).anyMatch(i -> i.actionTag().equals("NAV:MY_WORLDS"));
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew :proxy:test --tests "nl.gzmn.playerworlds.proxy.menu.MenuViewServiceTest"`
Expected: Compilation failure due to missing service.

- [ ] **Step 3: Implement `MenuViewService` and Screen Builders**
Port the UI rendering logic from `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/` to generate `RenderMenuPayload` objects using pure data (no Bukkit ItemStack dependencies on Velocity, only material strings and Adventure components).

- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew :proxy:test --tests "nl.gzmn.playerworlds.proxy.menu.MenuViewServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/ proxy/src/test/java/nl/gzmn/playerworlds/proxy/menu/
git commit -m "feat(proxy): implement MenuViewService and screen payload builders"
```

---

### Task 3: Proxy Channel Listener & Command Integration in `:proxy`

**Files:**
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/MenuChannelListener.java`
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldCommand.java`
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/GzmnWorldsProxyPlugin.java`
- Modify: `proxy/src/test/java/nl/gzmn/playerworlds/proxy/menu/MenuChannelListenerTest.java`

- [ ] **Step 1: Write test for incoming `MenuClickIntent` dispatching in `MenuChannelListenerTest`**
Verify that a `MenuClickIntent` from a `ServerConnection` executes `WorldActions` and replies with the updated screen payload over `gzmn:menu`.

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew :proxy:test --tests "nl.gzmn.playerworlds.proxy.menu.MenuChannelListenerTest"`

- [ ] **Step 3: Implement `MenuChannelListener` message handling and `/worlds` command wire-up**
Wire `MenuViewService` into `GzmnWorldsProxyPlugin`. When a player executes `/worlds` or bare `/world`, `MenuViewService` generates the main menu payload and sends it to the player's server. Handle navigation tags (`NAV:*`, `PAGE:*`) and action tags (`ACTION:*`).

- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew :proxy:test`
Expected: All proxy tests PASS.

- [ ] **Step 5: Commit**
```bash
git add proxy/src/main/java/nl/gzmn/playerworlds/proxy/ proxy/src/test/java/nl/gzmn/playerworlds/proxy/
git commit -m "feat(proxy): wire MenuViewService into MenuChannelListener and commands"
```

---

### Task 4: Subproject `:lobby` Build Configuration

**Files:**
- Modify: `settings.gradle.kts`
- Create: `lobby/build.gradle.kts`
- Create: `lobby/src/main/resources/plugin.yml`

- [ ] **Step 1: Add `:lobby` to `settings.gradle.kts`**
Include `:lobby` in `settings.gradle.kts`.

- [ ] **Step 2: Create `lobby/build.gradle.kts` targeting Java 21**
Configure toolchain `languageVersion.set(JavaLanguageVersion.of(21))` and dependencies: `compileOnly(libs.paper.api)` and `implementation(project(":core"))`.

- [ ] **Step 3: Create `lobby/src/main/resources/plugin.yml`**
Declare plugin name `gzmn-worlds-lobby`, version `0.1.0-SNAPSHOT`, main class `nl.gzmn.playerworlds.lobby.LobbyPlugin`, api-version `1.21`.

- [ ] **Step 4: Run gradle build to verify configuration**
Run: `./gradlew :lobby:tasks`
Expected: Build succeeds and tasks list correctly.

- [ ] **Step 5: Commit**
```bash
git add settings.gradle.kts lobby/
git commit -m "build(lobby): create standalone lobby subproject targeting Java 21"
```

---

### Task 5: Implement Lightweight Lobby Plugin (`:lobby`)

**Files:**
- Create: `lobby/src/main/java/nl/gzmn/playerworlds/lobby/LobbyItemUtil.java`
- Create: `lobby/src/main/java/nl/gzmn/playerworlds/lobby/LobbyMenuChannel.java`
- Create: `lobby/src/main/java/nl/gzmn/playerworlds/lobby/LobbyMenuListener.java`
- Create: `lobby/src/main/java/nl/gzmn/playerworlds/lobby/LobbyPlugin.java`
- Create: `lobby/src/test/java/nl/gzmn/playerworlds/lobby/LobbyPluginTest.java`

- [ ] **Step 1: Write MockBukkit unit test for `LobbyPlugin`**
```java
@Test
void rendersInventoryFromPayloadAndSendsClickIntent() {
    ServerMock server = MockBukkit.mock();
    LobbyPlugin plugin = MockBukkit.load(LobbyPlugin.class);
    PlayerMock player = server.addPlayer();

    List<MenuItemDescriptor> items = List.of(
        new MenuItemDescriptor(11, "GRASS_BLOCK", 1, "§aWorld 1", List.of("§7Click to join"), null, "ACTION:JOIN:test-id")
    );
    RenderMenuPayload payload = new RenderMenuPayload(1001L, "MAIN", "§8Dynamic Player Worlds", 27, items);

    plugin.menuChannel().handleRenderPayload(player, payload);

    assertThat(player.getOpenInventory().getTitle()).isEqualTo("§8Dynamic Player Worlds");
    assertThat(player.getOpenInventory().getItem(11)).isNotNull();
    assertThat(player.getOpenInventory().getItem(11).getType()).isEqualTo(Material.GRASS_BLOCK);
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew :lobby:test`
Expected: Fails due to missing implementation classes.

- [ ] **Step 3: Implement Lobby components**
Implement `LobbyItemUtil`, `LobbyMenuChannel`, `LobbyMenuListener`, and `LobbyPlugin`.
- In `LobbyMenuChannel`: listen for `RenderMenuPayload` and render/update the Bukkit `Inventory`.
- In `LobbyMenuListener`: intercept `InventoryClickEvent`, cancel the click, and send `MenuClickIntent` to proxy. Send `MenuClosedNotice` on close.

- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew :lobby:test`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add lobby/src/
git commit -m "feat(lobby): implement zero-database inventory GUI renderer"
```

---

### Task 6: Full Verification & Build Artifact Validation

**Files:**
- Modify: `README.md` (documenting the separate `:lobby` artifact)

- [ ] **Step 1: Run full test suite across all modules**
Run: `./gradlew test`
Expected: All tests across `:core`, `:proxy`, `:backend`, and `:lobby` PASS.

- [ ] **Step 2: Build release jars and verify bytecodes & sizes**
Run: `./gradlew assemble`
Verify `gzmn-worlds-lobby-0.1.0-SNAPSHOT.jar` is compiled with Java 21 bytecode (class major version 65) and is lightweight.

- [ ] **Step 3: Commit**
```bash
git add README.md
git commit -m "docs: document standalone lobby plugin and verification"
```
