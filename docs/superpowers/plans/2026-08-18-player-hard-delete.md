# Player Hard Delete Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement safe, two-step permanent hard deletion for archived worlds owned by a player, available in the GUI via `WorldMenu` (gated by `ConfirmMenu`) and via CLI command `/world delete <name> hard confirm`.

**Architecture:**
- `:core`: Extends `MenuIntent` with sealed record `HardDeleteWorld(WorldId worldId)` and binary serialization in `MenuCodec` (discriminator `16`).
- `:proxy`: Adds `deleteHard` domain action to `WorldActions` enforcing ownership, `ARCHIVED` state, and `gzmn.worlds.delete.hard` permission; wires CLI command in `WorldCommand` and intent dispatch in `MenuChannelListener`.
- `:backend`: Updates `WorldMenu` to show "Permanently Delete World" when `world.state() == WorldState.ARCHIVED`, opening `ConfirmMenu` modal to dispatch `HardDeleteWorld` intent.

**Tech Stack:** Java 25, Gradle, PaperMC API, Velocity API, PostgreSQL, Adventure Component API, MockBukkit.

---

### Task 1: Wire Protocol & Codec in `:core`

**Files:**
- Modify: `core/src/main/java/nl/gzmn/playerworlds/core/menu/MenuIntent.java`
- Modify: `core/src/main/java/nl/gzmn/playerworlds/core/menu/MenuCodec.java`
- Test: `core/src/test/java/nl/gzmn/playerworlds/core/menu/MenuCodecTest.java`

- [x] **Step 1: Write failing test in `MenuCodecTest.java`**

```java
@Test
void hardDeleteWorldRoundTrip() {
    WorldId worldId = new WorldId(UUID.randomUUID());
    MenuIntent.HardDeleteWorld original = new MenuIntent.HardDeleteWorld(worldId);

    byte[] encoded = MenuCodec.encodeIntent(42L, original);
    IntentEnvelope decoded = MenuCodec.decodeIntent(encoded);

    assertEquals(42L, decoded.correlationId());
    assertInstanceOf(MenuIntent.HardDeleteWorld.class, decoded.intent());
    MenuIntent.HardDeleteWorld result = (MenuIntent.HardDeleteWorld) decoded.intent();
    assertEquals(worldId, result.worldId());
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests nl.gzmn.playerworlds.core.menu.MenuCodecTest.hardDeleteWorldRoundTrip`  
Expected: Compilation failure or FAIL (record `HardDeleteWorld` does not exist).

- [x] **Step 3: Implement `HardDeleteWorld` and update `MenuCodec`**

In `MenuIntent.java`:
```java
record HardDeleteWorld(WorldId worldId) implements MenuIntent {
    public HardDeleteWorld {
        Objects.requireNonNull(worldId, "worldId");
    }
}
```

In `MenuCodec.java`:
Add discriminator `private static final byte TYPE_HARD_DELETE_WORLD = 16;` and handle in switch:
```java
case MenuIntent.HardDeleteWorld hardDelete -> {
    dos.writeByte(TYPE_HARD_DELETE_WORLD);
    writeWorldId(dos, hardDelete.worldId());
}
```
And in decoder switch:
```java
case TYPE_HARD_DELETE_WORLD -> new MenuIntent.HardDeleteWorld(readWorldId(dis));
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests nl.gzmn.playerworlds.core.menu.MenuCodecTest`  
Expected: PASS (all roundtrip tests green).

- [x] **Step 5: Commit**

```bash
git add core/src/main/java/nl/gzmn/playerworlds/core/menu/ core/src/test/java/nl/gzmn/playerworlds/core/menu/
git commit -m "feat(core): add HardDeleteWorld intent to wire protocol and MenuCodec"
```

---

### Task 2: `WorldActions.deleteHard` & CLI Command in `:proxy`

**Files:**
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldActions.java`
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/WorldCommand.java`
- Test: `proxy/src/test/java/nl/gzmn/playerworlds/proxy/command/WorldActionsTest.java`

- [x] **Step 1: Write failing unit tests in `WorldActionsTest.java`**

```java
@Test
void deleteHardArchivedWorldSucceeds() throws Exception {
    WorldId worldId = new WorldId(UUID.randomUUID());
    Player player = mockPlayer("Player1");
    // insert archived world owned by player
    // invoke actions.deleteHard(player, "archivedworld", true)
    // assert ActionResult.Ok and world removed from repo
}

@Test
void deleteHardActiveWorldFailsWithConflict() throws Exception {
    Player player = mockPlayer("Player1");
    // insert READY world
    // invoke actions.deleteHard(player, "activeworld", true)
    // assert ActionResult.Failed with STATE_CONFLICT
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :proxy:test --tests nl.gzmn.playerworlds.proxy.command.WorldActionsTest`  
Expected: FAIL (method `deleteHard` not present).

- [x] **Step 3: Implement `deleteHard` in `WorldActions.java` and `WorldCommand.java`**

In `WorldActions.java`:
```java
public static final String HARD_DELETE_PERMISSION = "gzmn.worlds.delete.hard";

public CompletableFuture<ActionResult> deleteHard(Player caller, String name, boolean confirmed) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(name, "name");
    return CompletableFuture.supplyAsync(() -> {
        try {
            Optional<PlayerWorld> worldOpt = worlds.findByOwnerAndName(caller.getUniqueId(), name);
            if (worldOpt.isEmpty()) {
                return ActionResult.failure("WORLD_NOT_FOUND", error(caller, "you do not own a world called '" + name + "'"));
            }
            return executeDeleteHard(caller, worldOpt.get(), confirmed);
        } catch (SQLException e) {
            log.error("deleteHard failed for {}", caller.getUsername(), e);
            return ActionResult.failure("DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
        }
    }, executors.db());
}

public CompletableFuture<ActionResult> deleteHard(Player caller, WorldId worldId) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(worldId, "worldId");
    return CompletableFuture.supplyAsync(() -> {
        try {
            Optional<PlayerWorld> worldOpt = worlds.findById(worldId);
            if (worldOpt.isEmpty()) {
                return ActionResult.failure("WORLD_NOT_FOUND", error(caller, "world not found"));
            }
            PlayerWorld world = worldOpt.get();
            if (!world.ownerUuid().equals(caller.getUniqueId())) {
                return ActionResult.failure("PERMISSION_DENIED", error(caller, "you are not the owner of this world"));
            }
            return executeDeleteHard(caller, world, true);
        } catch (SQLException e) {
            log.error("deleteHard by id failed for {}", caller.getUsername(), e);
            return ActionResult.failure("DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
        }
    }, executors.db());
}

private ActionResult executeDeleteHard(Player caller, PlayerWorld world, boolean confirmed) throws SQLException {
    if (!caller.hasPermission(HARD_DELETE_PERMISSION)) {
        return ActionResult.failure("PERMISSION_DENIED", error(caller, "you do not have permission to permanently delete worlds"));
    }
    if (world.state() != WorldState.ARCHIVED) {
        return ActionResult.failure("STATE_CONFLICT", error(caller, "'" + world.name() + "' must be archived before it can be permanently deleted"));
    }
    if (!confirmed) {
        info(caller, "this permanently destroys '" + world.name() + "' and all backup archives. This cannot be undone.");
        return ActionResult.failure("UNCONFIRMED", info(caller, "type /world delete " + world.name() + " hard confirm to permanently delete"));
    }
    if (!worlds.deleteHard(world.id())) {
        return ActionResult.failure("DATABASE_ERROR", error(caller, "could not delete world; please try again"));
    }
    log.info("world {} ('{}') permanently deleted by owner {}", world.id(), world.name(), caller.getUsername());
    return ActionResult.success(Component.text("Permanently deleted world '" + world.name() + "'.", NamedTextColor.GREEN));
}
```

In `WorldCommand.java`:
Update `/world delete <name>` to support `hard` and `hard confirm` arguments.

- [x] **Step 4: Run tests to verify they pass**

Run: `./gradlew :proxy:test`  
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add proxy/src/main/java/nl/gzmn/playerworlds/proxy/command/ proxy/src/test/java/nl/gzmn/playerworlds/proxy/command/
git commit -m "feat(proxy): implement player deleteHard domain action and CLI command"
```

---

### Task 3: `MenuChannelListener` Hard Delete Dispatch in `:proxy`

**Files:**
- Modify: `proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/MenuChannelListener.java`
- Test: `proxy/src/test/java/nl/gzmn/playerworlds/proxy/menu/MenuChannelListenerTest.java`

- [x] **Step 1: Write failing test in `MenuChannelListenerTest.java`**

```java
@Test
void hardDeleteWorldIntentDispatchesToActions() {
    WorldId worldId = new WorldId(UUID.randomUUID());
    MenuIntent.HardDeleteWorld intent = new MenuIntent.HardDeleteWorld(worldId);
    // send intent over fake server connection
    // assert WorldActions.deleteHard(player, worldId) called and MenuResult returned
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :proxy:test --tests nl.gzmn.playerworlds.proxy.menu.MenuChannelListenerTest`  
Expected: FAIL (unhandled switch case or missing implementation).

- [x] **Step 3: Implement dispatch in `MenuChannelListener.java`**

In `MenuChannelListener.java`:
```java
case MenuIntent.HardDeleteWorld hardDelete -> actions.deleteHard(player, hardDelete.worldId());
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :proxy:test --tests nl.gzmn.playerworlds.proxy.menu.MenuChannelListenerTest`  
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add proxy/src/main/java/nl/gzmn/playerworlds/proxy/menu/ proxy/src/test/java/nl/gzmn/playerworlds/proxy/menu/
git commit -m "feat(proxy): dispatch HardDeleteWorld in MenuChannelListener"
```

---

### Task 4: Backend `WorldMenu` GUI Hard Deletion & `ConfirmMenu` in `:backend`

**Files:**
- Modify: `backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/WorldMenu.java`
- Test: `backend/src/test/java/nl/gzmn/playerworlds/backend/gui/screen/CoreScreensTest.java`

- [x] **Step 1: Write failing test in `CoreScreensTest.java`**

```java
@Test
void archivedWorldShowsPermanentDeleteButtonAndOpensConfirmModal() {
    // create WorldMenu with ARCHIVED world
    // verify slot 16 contains LAVA_BUCKET "Permanently Delete World"
    // click slot 16
    // verify ConfirmMenu opens with confirmation action dispatching MenuIntent.HardDeleteWorld
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.gui.screen.CoreScreensTest`  
Expected: FAIL.

- [x] **Step 3: Update `WorldMenu.java`**

In `WorldMenu.java`:
When `world.state() == WorldState.ARCHIVED`:
- Slot 10: "Restore World" (Golden Apple)
- Slot 16: "Permanently Delete World" (`Material.LAVA_BUCKET` or `Material.TNT`) with lore:
  - `Component.text("⚠ Irreversible Action", NamedTextColor.RED, TextDecoration.BOLD)`
  - `Component.text("Permanently destroys all chunks and backup archives.", NamedTextColor.GRAY)`
  - `Component.text("▶ Click to delete permanently", NamedTextColor.DARK_RED)`
- In `handleClick`:
  - When slot 16 is clicked on an `ARCHIVED` world:
    ```java
    menuService.openConfirmMenu(
        player,
        "Permanently Delete " + world.name() + "?",
        Component.text("Permanently destroy '" + world.name() + "'? All archives will be lost forever.", NamedTextColor.RED),
        () -> {
            if (menuChannel != null) {
                menuChannel.sendIntent(player, new MenuIntent.HardDeleteWorld(world.id()))
                    .thenAcceptAsync(result -> {
                        switch (result) {
                            case MenuResult.Ok ok -> {
                                player.sendMessage(Component.text(ok.message(), NamedTextColor.GREEN));
                                menuService.openMyWorldsMenu(player);
                            }
                            case MenuResult.Failed failed -> {
                                player.sendMessage(Component.text(failed.message(), NamedTextColor.RED));
                                menuService.openWorldMenu(player, world.id());
                            }
                        }
                    }, menuService.executors().main());
            }
        },
        () -> menuService.openWorldMenu(player, world.id())
    );
    ```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests nl.gzmn.playerworlds.backend.gui.screen.CoreScreensTest`  
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java/nl/gzmn/playerworlds/backend/gui/screen/ backend/src/test/java/nl/gzmn/playerworlds/backend/gui/screen/
git commit -m "feat(backend): add permanent deletion button and confirmation flow to WorldMenu"
```

---

### Task 5: Full Suite Verification & Build

**Files:**
- None (verification task)

- [x] **Step 1: Run code formatters and static checks**

Run: `./gradlew spotlessApply check`  
Expected: BUILD SUCCESSFUL with 0 violations.

- [x] **Step 2: Re-run all tests from scratch**

Run: `./gradlew test --rerun`  
Expected: 100% tests passed.
