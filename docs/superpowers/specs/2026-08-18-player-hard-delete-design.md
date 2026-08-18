# Player-Facing Hard Delete Design Specification

**Date:** 2026-08-18  
**Author:** AI Pair Programmer & User  
**Status:** DRAFT / PROPOSED

---

## 1. Overview
Currently, `/world delete` and GUI world deletion perform a soft deletion (archival) to prevent accidental data loss. This specification adds a safe, two-step **hard deletion** mechanism that allows players to permanently destroy worlds they own once they are in the `ARCHIVED` state, both via the GUI and via chat command.

---

## 2. Requirements & Invariants

### Functional Requirements
1. **Precondition (Archived State)**: A world can only be permanently hard-deleted by a player if it is currently in `WorldState.ARCHIVED`. Active (`READY`, `CREATING`, `ARCHIVING`, `RESTORING`) worlds cannot be directly hard-deleted by players without archiving first.
2. **Ownership & Permissions**:
   - The caller must be the owner of the world (`owner_uuid == caller.uuid()`).
   - The caller must possess permission `gzmn.worlds.delete.hard` (defaults to true for players).
3. **GUI Flow**:
   - In `WorldMenu`, when `world.state() == WorldState.ARCHIVED`, slot 16 displays **"Permanently Delete World"** (Lava Bucket / TNT).
   - Clicking slot 16 opens a `ConfirmMenu` titled `"Permanently Delete <name>?"`.
   - Confirming sends `MenuIntent.HardDeleteWorld(world.id())`.
   - On success, displays confirmation message and navigates back to `MyWorldsMenu`.
4. **CLI Command Flow**:
   - `/world delete <name> hard` checks if the world is archived and warns that this permanently destroys all chunks and archives with no undo.
   - `/world delete <name> hard confirm` executes `WorldActions.deleteHard(player, name, true)`.

---

## 3. Wire Protocol & Architecture

### A. Wire Protocol (`:core`)
- Add sealed record to `MenuIntent.java`:
  ```java
  record HardDeleteWorld(WorldId worldId) implements MenuIntent {}
  ```
- Update `MenuCodec.java`:
  - Wire type ID `16`: `TYPE_HARD_DELETE_WORLD = 16`.
  - Encodes 16-byte `WorldId`.
  - Decodes `WorldId` to `HardDeleteWorld`.

### B. Proxy Handling (`:proxy`)
- In `WorldActions.java`:
  - `CompletableFuture<ActionResult> deleteHard(Player caller, String name, boolean confirmed)`
  - `CompletableFuture<ActionResult> deleteHard(Player caller, WorldId worldId)`
  - Verifies caller is owner, state is `ARCHIVED`, permission is granted, and invokes `PlayerWorldRepository.deleteHard(worldId)`.
- In `MenuChannelListener.java`:
  - Dispatches `MenuIntent.HardDeleteWorld` to `WorldActions.deleteHard(player, hardDelete.worldId())`.
- In `WorldCommand.java`:
  - Adds `/world delete <name> hard [confirm]` subcommand branch.

### C. Backend GUI (`:backend`)
- In `WorldMenu.java`:
  - When `world.state() == WorldState.ARCHIVED`:
    - Slot 10: "Restore World" (Golden Apple)
    - Slot 16: "Permanently Delete World" (Lava Bucket)
    - Clicking slot 16 opens `ConfirmMenu` modal executing `menuChannel.sendIntent(player, new MenuIntent.HardDeleteWorld(world.id()))`.

---

## 4. Test Plan
- `MenuCodecTest`: round-trip serialization of `HardDeleteWorld`.
- `WorldActionsTest`: hard delete of archived world (success), rejection of non-archived world (`STATE_CONFLICT`), rejection of non-owner (`PERMISSION_DENIED`).
- `WorldCommandTest`: `/world delete <name> hard confirm` CLI execution.
- `MenuChannelListenerTest`: intent dispatch and result mapping for `HardDeleteWorld`.
- `CoreScreensTest`: `WorldMenu` rendering and confirmation flow for hard deletion.
