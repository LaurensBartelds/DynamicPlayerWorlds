# Design: `/world list` Command

## Overview
Reintroduce the `/world list` command to the Velocity proxy plugin (`WorldCommand`), allowing players to view the worlds they own and worlds they are members of.

## Goals
- Allow players on any server across the network to run `/world list` from the proxy.
- Display a clearly categorized breakdown of owned worlds and shared world memberships.
- Ensure all database interactions adhere to asynchronous guidelines (NFR-2) without stalling the main/command thread.
- Preserve isolation and privacy (FR-24b, §5.5) by only surfacing worlds the caller has legitimate access to.

## Command Specification

### Syntax & Registration
- **Command:** `/world list`
- **Brigadier Registration:** `WorldCommand.build()` registers `.then(BrigadierCommand.literalArgumentBuilder("list").executes(...))`
- **SUBCOMMANDS list:** Updated to include `"list"`.
- **Permission:** Standard player access (no elevated permissions required).

### UX & Output Display
1. **Empty State (no owned or shared worlds):**
   ```text
   You do not own or belong to any worlds yet. Use /world create <name> to create one.
   ```
2. **Standard State:**
   ```text
   Your worlds:
     • <world_name> [<STATE>] (visibility: <VISIBILITY>)
   
   Shared worlds (member):
     • <world_name> (Owner: <owner_name>) - <ROLE>
   ```
3. If only one category is empty, it displays `(none)` under that section heading.

## Technical Architecture & Flow

1. **Invocation:**
   - When a player runs `/world list`, the handler checks that the source is a player (`playerOrNull(context)`).
   - Offloads work to `executors.db()`.
2. **Database Queries:**
   - Retrieve all worlds owned by the caller: `worlds.listOwnedBy(caller.getUniqueId())`.
   - Retrieve all memberships of the caller: `membership.membershipsOf(caller.getUniqueId())`.
   - Filter memberships for entries where `member.role() != Role.OWNER` (to exclude own worlds).
   - For shared memberships, load the corresponding `PlayerWorld` entries (e.g. via `worlds.findById(member.worldId())`).
   - Batch resolve owner UUIDs using `names.namesOf(...)` with fallback to UUID string if cache misses.
3. **Response Delivery:**
   - Send Kyori Adventure formatted messages back to the caller using `info(...)` and `NamedTextColor`.

## Error Handling & Edge Cases
- **Database Failure:** Logs the `SQLException` and outputs a user-friendly error message (`error(caller, "that did not work; the failure is in the proxy log")`).
- **Missing Owner Name:** If an owner UUID cannot be resolved from the player name cache, gracefully format using the raw UUID string.
- **Archived Worlds:** Owned worlds in `ARCHIVED` state are shown with `[ARCHIVED]` status to inform the player that the world can be restored using `/world restore <name>`.
- **Console / Non-Player execution:** Refused with the standard message: `"/world acts on the caller's own worlds and must be run by a player"`.
