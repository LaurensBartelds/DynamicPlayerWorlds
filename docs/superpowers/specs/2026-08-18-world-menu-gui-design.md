# World Menu GUI — Design

**Status:** approved in brainstorming, awaiting spec review
**Date:** 2026-08-18
**Spec references:** §6 (commands and permissions), §5.5 (isolation), FR-27, FR-30a, FR-32, NFR-2, MN-17
**Supersedes nothing.** Adds a second interaction surface over the existing command logic.

## 1. Goal

`/worlds` (and bare `/world`) opens an inventory GUI from which a player can see and
change everything the player-facing `/world` subcommands do today: their worlds, joining,
creating, archiving, restoring, storage usage, members, invites, transfers, per-world
settings, visibility and bans.

The `/world admin` subtree is **out of scope** and stays command-only. Administrators are
comfortable with commands, and the admin tree is where FR-37's typed confirmation carries
the most weight — see §7.

## 2. The constraint this design exists to solve

Two facts are in tension, and every decision below follows from them.

1. **Velocity cannot draw an inventory.** `velocity-api-4.0.0.jar` contains no `Inventory`
   and no `ItemStack` — not a gap in our code, an absence in the platform. A chest GUI can
   only be drawn by a Paper server running `:backend`.
2. **The `/world` commands live on the proxy on purpose** (§6). The proxy is the only
   component that can answer questions about a world without that world being loaded, and
   by FR-25 a world is unloaded most of the time.

So the GUI cannot live where the logic lives. Something has to cross the gap.

### Why not let the GUI write to the database directly

It was considered and rejected. The backend already shades `:core` and could perform every
mutation itself, asking the proxy only for routing. That would re-implement the world cap
(FR-32), the storage quota (§4), isolation (§5.5) and ban checks a second time.

Two validation paths drift. This repository has already produced that failure twice:
`PlayerWorldRepository.updateStorageBytes` and `LocalObjectCache.evictLru` were both
written, both tested, and both called by nothing — so a configured limit did nothing. A GUI
with its own copy of the rules is the same defect with worse consequences, because the
copy that silently permits more is the one players will use.

**One implementation of every rule. That is the load-bearing decision of this design.**

## 3. Architecture

```text
:backend  gui/          renders inventories, handles clicks
             │ reads ──────────────► database        (direct, no round trip)
             │ mutations
             ▼  plugin message: MenuIntent + correlation id
:proxy    MenuChannelListener ──► WorldActions ◄── WorldCommand
             ▲                     (every rule)     (thin Brigadier layer)
             └── MenuResult ─────────┘
:core     menu/  MenuIntent, MenuResult, codec — shared by both plugins
```

Reads take the fast path. The backend queries the database on its own db executor and never
consults the proxy, because a menu that waits on a round trip to list a player's worlds
feels broken. Only **mutations** cross the channel.

### 3.1 `:core` — `nl.gzmn.playerworlds.core.menu`

Sits alongside the existing `core.control` package, which it deliberately resembles without
sharing: `control` addresses a *node* over the database and survives restarts; `menu`
addresses a *player* over their own connection and does not.

Three message types across **two directions**, which is worth stating plainly because the
directions have different trust properties (§5):

| Message | Direction | Purpose |
| --- | --- | --- |
| `OpenMenu` | proxy → backend | The player typed `/worlds`; open their main menu. |
| `MenuIntent` | backend → proxy | The player clicked something; do it. |
| `MenuResult` | proxy → backend | What happened. |

- `MenuIntent` — a sealed interface, one record per action: `JoinWorld`, `CreateWorld`,
  `ArchiveWorld`, `RestoreWorld`, `InviteMember`, `KickMember`, `PromoteMember`,
  `SetVisibility`, `SetSetting`, `BanPlayer`, `UnbanPlayer`, `RequestTransfer`,
  `AcceptTransfer`, `DeclineTransfer`, `AcceptInvite`.
- `MenuResult` — `Ok(String message)` or `Failed(FailureCode code, String message)`.
  It carries **no view state**: on success the backend re-reads from the database and
  rebuilds the screen. Shipping rendered state over the wire would put a second copy of
  "what a world looks like" on the proxy, which is the duplication this design exists to
  avoid.
- `MenuCodec` — byte encode/decode for all three, following the hand-rolled style already
  used by `EjectPayload` and `ArchivePayload` rather than introducing a serialisation
  library.

`MenuIntent` carries **no player UUID**. See §5.

### 3.2 `:proxy` — `WorldActions` and the channel listener

`WorldCommand.java` is ~2,000 lines and its logic lives *inside* Brigadier handlers, bound
to `CommandContext` and replying through `caller.sendMessage`. None of it is callable from
anywhere else, which is the immediate reason the GUI cannot reuse it and a good enough
reason to change it on its own.

`WorldActions` takes the body of each handler and returns a structured result:

```java
public final class WorldActions {
    public ActionResult create(Player caller, String name, @Nullable String seed);
    public ActionResult archive(Player caller, String worldName);
    public ActionResult restore(Player caller, String worldName);
    public ActionResult invite(Player caller, String targetName, WorldId worldId);
    // … one per player-facing subcommand
}
```

`WorldCommand` becomes a thin Brigadier layer that calls `WorldActions` and renders the
result as chat. `MenuChannelListener` calls the same methods and renders the result into
the menu. Neither owns a rule.

**This refactor lands first, as its own behaviour-preserving commit, with the existing
`WorldCommandTest` unchanged as the proof that nothing moved.** No GUI code is written
until that commit is green. If the GUI is later reconsidered, the refactor still stands on
its own merit and nothing needs unpicking.

### 3.3 `:backend` — `nl.gzmn.playerworlds.backend.gui`

- `MenuService` — opens menus and tracks which menu each player has open.
- `MenuListener` — `InventoryClickEvent` and `InventoryCloseEvent`.
- `MenuChannel` — sends intents, correlates replies by id, times them out.
- Screens: `MainMenu`, `MyWorldsMenu`, `WorldMenu`, `MembersMenu`, `SettingsMenu`,
  `StorageMenu`, `InvitesMenu`, `BansMenu`, `ConfirmMenu`, and `BrowseMenu` last (§8).

Every screen renders from a plain data object built off the main thread. Screens do no IO
and hold no database types, so they are testable without a database.

## 4. GUI-only mode

The GUI must open wherever a player stands, including the lobby. The lobby is not a worlds
node and must never become one.

`:backend` gains `node.mode`, read by `BackendConfig` into `NodeConfig`:

| `node.mode` | Behaviour |
| --- | --- |
| `worlds` (default) | Today's behaviour exactly. Nothing changes for existing deployments. |
| `gui-only` | Connects to the database and registers the menu listener and channel. Publishes **no `worlds_node` heartbeat**, so placement (MN-17) can never route a world here. No leases, no snapshot engine, no `MaintenanceTask`, no world registry. |

Suppressing the heartbeat is what makes this safe: placement selects from live `worlds_node`
rows, so a node that never publishes one is invisible to it. The mode is not a set of
feature flags to be kept in step — it is one branch at enable, taken once.

## 5. Security

Plugin messages arrive over the client's own connection and a modded client can forge them.
Two rules, both non-negotiable:

1. **Source check.** `PluginMessageEvent.getSource()` returns a `ChannelMessageSource`.
   `ServerConnection` and `Player` both implement it. The listener accepts a `MenuIntent`
   **only** when the source is a `ServerConnection` — a message whose source is a `Player`
   came from the client and is dropped. The event is then marked
   `setResult(ForwardResult.handled())` so it is never forwarded on.
2. **Identity comes from the connection, never the payload.** `MenuIntent` carries no player
   UUID by construction, so there is no field to forge. The acting player is
   `((ServerConnection) source).getPlayer()`. A payload-supplied UUID would turn `/worlds`
   into "act as any player you can name".

The `OpenMenu` direction needs no equivalent check. Paper delivers plugin messages from the
proxy and from the client through the same listener and cannot distinguish them, so a modded
client can forge an `OpenMenu`. That is harmless by construction: it opens the requesting
player's own menu, built from that player's own data, and every action taken from it still
crosses back as a `MenuIntent` under rule 1. Forging it buys an attacker a menu they could
have opened by typing `/worlds`.

Every intent then runs the same permission, cap, quota, isolation and ban checks the command
path runs, because it calls the same `WorldActions` method the command calls. §5.5's
isolation rules are enforced by that shared code, not re-stated here.

## 6. Commands

The proxy keeps ownership of both verbs; the backend registers neither.

| Input | Behaviour |
| --- | --- |
| `/worlds` | Opens the GUI. |
| `/world` (bare) | Opens the GUI. |
| `/world <sub> …` | Unchanged. Every existing subcommand keeps working. |

Bare invocation sends an `OpenMenu` message toward the player's current backend. **If no
reply arrives within the timeout, the proxy prints the existing usage text instead.** A
lobby without the plugin therefore degrades to today's behaviour rather than to silence,
and the GUI-only deployment becomes an enhancement rather than a prerequisite.

The usage text is therefore **demoted, not deleted**: it stops being what `/world` normally
prints, and becomes the fallback for when no GUI can be reached.

## 7. Confirmation, and a deliberate softening of FR-27

FR-27 requires *typed* confirmation for `/world delete`: the player types the world's name a
second time. Clicking a button is not typing.

**Decision: the GUI uses a click-to-confirm screen.** `ConfirmMenu` states what will happen
and requires a second, deliberate click on a distinct button. This is a real softening of
FR-27 and is recorded here as such rather than left to be discovered.

It is bounded by three things:

- Archival is now **reversible**. Since milestone 11 the world is packed to cold storage and
  `/world restore` brings it back; the archive is never deleted by that flow.
- FR-37's **hard deletion**, the one irreversible action, is an admin command and is not in
  the GUI at all. Its typed confirmation is untouched.
- The **command path keeps typed confirmation** unchanged. This softening applies only to
  the GUI surface.

The rejected alternative was an anvil text-entry screen, which would have preserved FR-27
exactly at the cost of an extra screen type.

## 8. Scope and sequencing

Ordered by dependency. Each step leaves the tree green.

1. **`WorldActions` extraction** — behaviour-preserving, no GUI code, existing tests unchanged.
2. **`:core` menu protocol** — intents, results, codec.
3. **Channel + security** — listener, source checking, correlation, timeouts.
4. **GUI-only mode** — `node.mode`, heartbeat suppression.
5. **Core screens** — main, my worlds, world, storage, confirm.
6. **Member and social screens** — members, invites, bans, transfers.
7. **`BrowseMenu`** — last, and severable.

`BrowseMenu` is sequenced last deliberately: `/world browse` already paginates public
worlds, and a browse GUI is the most screen-heavy part of this design for the least gain.
If effort has to be cut, this is the piece to cut. **This was proposed and not explicitly
confirmed — flagged for spec review.**

### Not in scope

- The `/world admin` subtree.
- Any change to the command surface other than retiring the bare-`/world` usage text.
- Pagination beyond the existing `browse.page-size` policy setting.

## 9. Error handling

- **Failures render in the menu**, not in chat. A `MenuResult.Failed` carries a code and a
  human message; the screen shows it without closing.
- **Timeouts.** An intent with no reply resolves as a failure naming the timeout. The
  correlation entry is dropped on player disconnect.
- **Stale state.** A world may change between rendering a screen and clicking it. The
  existing conditional updates — generation and state guards — already refuse in that case,
  so the GUI does not attempt its own optimistic locking. It re-renders on failure.
- **NFR-2 holds throughout.** Screens are built from data fetched on the db executor and
  applied to the inventory on the main thread. No database call happens on a tick.

## 10. Testing

| Module | Coverage |
| --- | --- |
| `:core` | `MenuCodec` round-trips every intent and result, including absent optional fields. |
| `:proxy` | `WorldActions` behaviour. The **existing `WorldCommandTest` is the regression net for step 1** and must pass unchanged. New tests: the channel listener rejects a `Player`-sourced message, and takes identity from the connection. |
| `:backend` | MockBukkit rendering — a given world list produces the expected inventory contents; a click on a known slot produces the expected intent. `gui-only` mode publishes no heartbeat. |

**All 492 existing tests stay green at every step.** The refactor in step 1 is the risk in
this design, and the existing suite is what makes it a manageable one.

## 11. Open questions for spec review

1. **`BrowseMenu` sequencing** (§8) — ship it last, or drop it and leave browsing on the
   command?
2. **GUI-only on the lobby** is assumed to be a deployment you are willing to make. The
   usage-text fallback in §6 means the GUI degrades gracefully without it, so this is a
   preference rather than a blocker.
