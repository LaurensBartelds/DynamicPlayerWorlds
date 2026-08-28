# Standalone Zero-Database Lobby Plugin — Design

**Status:** approved in brainstorming, awaiting spec review  
**Date:** 2026-08-20  
**Spec references:** §6 (commands and permissions), §5.5 (isolation), FR-27, FR-30a, FR-32, NFR-2, MN-17, ADR 0003  
**Supersedes:** The embedded `gui-only` mode in `:backend` for lobby servers, establishing a dedicated `:lobby` artifact.

---

## 1. Context & Motivation

When deploying the backend plugin `gzmn-worlds` onto a Paper 1.21 lobby server running Java 21, the server fails during startup with:
```
Caused by: java.lang.IllegalArgumentException: Unsupported class file major version 69
        at org.objectweb.asm.ClassReader.<init>(ClassReader.java:200)
```
- **Major version 69 is Java 25**. Paper 26 API requires a Java 25 toolchain, whereas Paper 1.21 runs on Java 21 with ASM 9.7.
- Furthermore, a lobby server only hosts menus and portals; it never loads player worlds, never manages leases, and has no need for AWS S3 SDK, Zstandard compression, or chunk-level Paper 26 platform bindings.
- Requiring database credentials (`config.yml` with PostgreSQL connection, pool sizing, Flyway migrations) on a lobby server adds operational complexity.

This design introduces a **stateless, zero-database standalone lobby plugin** (`:lobby`) targeting **Java 21** and Paper 1.21+.

---

## 2. Architecture: Dumb Terminal UI & Proxy Orchestration

The `:lobby` plugin operates as a "dumb terminal" chest inventory renderer. All database operations, permission checks, quota calculations, and business rules remain exclusively on the Velocity proxy (`:proxy`).

```text
[ Player on Lobby Server (Paper 1.21+, Java 21) ]
       │  Types /world, /worlds, or clicks an inventory slot
       ▼
┌────────────────────────────────────────────────────────┐
│ :lobby Plugin (Lightweight UI Renderer, ~50 KB)       │
│ - Zero database configuration                          │
│ - Listens to InventoryClickEvent / InventoryCloseEvent  │
│ - Renders Chest GUI using Adventure Components & Items │
└───────────────────────┬────────────────────────────────┘
                        │ Plugin Messaging Channel ('gzmn:menu')
                        │ (Connection-Bound)
                        ▼
┌────────────────────────────────────────────────────────┐
│ :proxy Plugin (Velocity 4.0.0, Java 21/25)             │
│ - Connects to PostgreSQL (WorldRepo, MembershipRepo...)│
│ - Evaluates storage quotas, limits, permissions        │
│ - Builds menu view models (Screen data + item lists)   │
│ - Handles player actions via WorldActions              │
└────────────────────────────────────────────────────────┘
```

### 2.1 Reads & View Construction
1. When a player executes `/worlds` or clicks to navigate, the proxy receives the command/action.
2. Velocity queries the database asynchronously via its connection pool.
3. Velocity builds a `RenderMenuPayload` describing the target screen (title, slot dimensions, items, names, lore, skull textures, and action tags).
4. Velocity sends the payload across the `gzmn:menu` channel to the player's current server.
5. `:lobby` parses the payload, updates or opens the chest `Inventory`, and presents it to the player.

### 2.2 Mutations & Actions
1. When a player clicks a slot, `:lobby` cancels the event to prevent item theft.
2. `:lobby` reads the `actionTag` associated with that slot and sends a `MenuClickIntent` back across `gzmn:menu`.
3. Velocity validates the request source (only accepting messages from `ServerConnection`), runs `WorldActions`, mutates the database, and responds with a fresh `RenderMenuPayload` or a status notice.

---

## 3. Protocol Specification (`:core` / `nl.gzmn.playerworlds.core.menu`)

All messages are transmitted over the plugin message channel `gzmn:menu`.

### 3.1 Proxy to Lobby (`ProxyToBackendMessage`)
* **`RenderMenuPayload`**:
  * `screenType` (Enum: `MAIN`, `MY_WORLDS`, `WORLD_DETAILS`, `STORAGE`, `MEMBERS`, `SETTINGS`, `INVITES`, `BANS`, `BROWSE`, `CONFIRM`).
  * `title` (`Component`): Formatted title of the chest GUI.
  * `size` (`int`): Inventory size (multiple of 9, e.g. 27, 36, 45, 54).
  * `items` (`List<MenuItemDescriptor>`):
    * `slot` (`int`): 0-based inventory slot index.
    * `materialName` (`String`): Standard Bukkit material identifier (e.g. `GRASS_BLOCK`, `PLAYER_HEAD`, `BARRIER`).
    * `amount` (`int`): Stack count (1–64).
    * `displayName` (`Component`): Styled display name.
    * `lore` (`List<Component>`): Formatted lore lines.
    * `skullOwner` (`@Nullable UUID` or `@Nullable String`): Owning player for player heads.
    * `actionTag` (`String`): Action identifier returned upon click (e.g., `NAV:MY_WORLDS`, `ACTION:JOIN:<worldId>`, `ACTION:DELETE:<worldId>`).
* **`CloseMenuMessage`**: Instructs the client server to close the player's active inventory.

### 3.2 Lobby to Proxy (`BackendToProxyMessage`)
* **`MenuClickIntent`**:
  * `actionTag` (`String`): The action token of the clicked slot.
  * `screenSequence` (`int`): Monotonic sequence number to drop out-of-order responses.
* **`MenuClosedNotice`**: Sent when the player closes the inventory.

### 3.3 Security Invariants
* **Zero UUID in Payloads**: The client/lobby payload carries no player UUID. Velocity derives player identity strictly from `PluginMessageEvent.getSource().getPlayer()`.
* **Source Checking**: Messages are only processed if the source is a `ServerConnection`.

---

## 4. Module & Build Structure

### 4.1 New Subproject: `:lobby`
* **Gradle Path**: `:lobby` (root directory `lobby/`).
* **Toolchain**: Java 21 (`JavaLanguageVersion.of(21)`).
* **Dependencies**:
  * `compileOnly(libs.paper.api)` (Java 21 compatible coordinate).
  * `implementation(project(":core"))` (Menu protocol, codecs, data records).
  * No PostgreSQL, no HikariCP, no AWS S3, no Zstd.
* **Artifact**: `gzmn-worlds-lobby-0.1.0-SNAPSHOT.jar` (~50 KB).

### 4.2 `:proxy` Additions
* **`MenuViewService`**: Asynchronously queries database and produces `RenderMenuPayload`s.
* **Screen Builders**: Format `MainMenu`, `MyWorldsMenu`, `WorldMenu`, `SettingsMenu`, `MembersMenu`, `StorageMenu`, `InvitesMenu`, `BansMenu`, `BrowseMenu`, and `ConfirmMenu`.
* **`MenuChannelListener`**: Handles incoming `MenuClickIntent` messages and coordinates with `WorldActions`.

---

## 5. Verification & Testing Plan

1. **Protocol Unit Tests (`:core`)**:
   - Verify `MenuCodec` encodes and decodes `RenderMenuPayload` and `MenuClickIntent` with all variations (nulls, player skulls, complex lore).
2. **Proxy Menu Engine Tests (`:proxy`)**:
   - Verify `MenuViewService` queries repositories asynchronously and formats correct `MenuItemDescriptor`s for each screen type.
   - Verify `MenuChannelListener` rejects `Player`-sourced packets and processes `ServerConnection`-sourced clicks.
3. **Lobby UI Tests (`:lobby`)**:
   - Using MockBukkit (Java 21), verify that `RenderMenuPayload` builds the inventory with matching items and titles.
   - Verify that clicking a slot cancels the event and dispatches the correct `MenuClickIntent`.
