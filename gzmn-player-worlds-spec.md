# GZMN Player Worlds - Technical Specification

Version 0.3 (draft). Multi-node with object storage is in scope.

Changes from 0.2, all from a technical review of that draft:

- Player profiles are now committed as part of a world snapshot rather than
  on an independent timer, closing the duplication window between PostgreSQL
  and object storage (section 5.4, MN-6a).
- Object storage is immutable: content-addressed data objects, write-once
  manifests, and a conditional PostgreSQL update as the single commit point.
  Fencing is now enforced by the storage layout rather than by a pre-write
  check (section 12.2, 12.3).
- Incremental sync uploads from a snapshot of the world folder, never from
  files the server is still writing (MN-5a).
- The set of synced paths is enumerated, including `entities/`, `poi/`,
  `data/` and `level.dat` (MN-2).
- Lease and liveness timings, the two loaded-world caps, and the split of
  commands between proxy and backend have been made consistent.

## 1. Goal

Allow a player on GZMN to create a private survival world, invite specific
other players to it, and play there in isolation from the rest of the network.
Players who are not members of a world must not be able to see, contact, or
detect the players inside it.

## 2. Scope and assumptions

- Target: Paper (latest stable), Java 21, Velocity proxy, Pelican Panel.
- Expected load: fewer than 5 player worlds loaded at the same time across the
  network, spread over two or more interchangeable `worlds` nodes.
  `nodes.max-worlds` (default 5) is a per-node ceiling, so the pool has
  headroom well above the expected load.
- Live worlds are plain Anvil folders on local node disk, treated as a
  disposable working copy. S3-compatible object storage (MinIO on GZMNServer)
  is the source of truth at rest. PostgreSQL holds metadata, leases and player
  profiles, never world data.
- Multi-node placement, leasing and object storage sync are in scope for v1
  (section 12).
- Out of scope for v1: moving a world to another network, world templates,
  per-world plugin sets, economy integration, resizing a world border after
  creation, live migration of a world with players still inside it.

## 3. Architecture

Three deployable units plus shared infrastructure.

### 3.1 `gzmn-worlds` (Paper plugin)

Runs on every `worlds` node. Nodes are interchangeable and identified by
`node.id`. Each node hosts only player worlds plus a small holding area, which
is where a joining player waits while the asynchronous lookup and load in
FR-11 complete. Owns world lifecycle, lease acquisition and heartbeat, snapshot
commits, membership *enforcement* in-world, per-world profiles, and visibility
isolation. It does not own the management commands: it applies their effects
when told to.

### 3.2 `gzmn-worlds-proxy` (Velocity plugin)

Runs on the proxy. Owns every command in section 6 except `/world leave` and
`/world report`, membership lookups from any server, the placement service
(which node a world should live on), dynamic node registration, and the
handoff into the chosen node. Management commands live here rather than on the
backend because a world is unloaded most of the time (FR-25) and its owner is
correspondingly usually somewhere else on the network; a backend-registered
command would be unreachable exactly when it is most needed. Communicates with
nodes over the database plus plugin messaging.

### 3.3 Pelican panel extension (optional, v1.1)

Read-mostly admin view: list worlds, owner, assigned node, size on disk and in
object storage, last played, member count, with actions for force-unload,
migrate, archive, and delete. Reads the same PostgreSQL schema. Not required
for the plugin to function.

### 3.4 Shared infrastructure

- PostgreSQL: metadata, membership, leases, node heartbeats, player profiles.
- MinIO on GZMNServer: world objects and cold archives.
- Pelican API: starting, stopping and provisioning `worlds` nodes.

## 4. Data model

```sql
CREATE TABLE player_world (
  id            UUID PRIMARY KEY,
  owner_uuid    UUID NOT NULL,          -- authoritative owner; see FR-31a
  name          TEXT NOT NULL,
  folder        TEXT NOT NULL UNIQUE,   -- derived from id, never from name; see FR-2a
  seed          BIGINT NOT NULL,
  border_radius INT NOT NULL DEFAULT 5000,
  visibility    TEXT NOT NULL DEFAULT 'PRIVATE'
                CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
  description   TEXT,                   -- shown in the browse list
  settings      JSONB NOT NULL DEFAULT '{}'::jsonb,  -- per-world owner settings, FR-9e
  assigned_node TEXT,                   -- node holding the lease, NULL if unleased
  lease_expires TIMESTAMPTZ,            -- heartbeat extends this while leased
  generation    BIGINT NOT NULL DEFAULT 0,  -- bumped on every lease acquisition, used for fencing
  manifest_key  TEXT,                   -- current committed snapshot; NULL until first upload
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_played   TIMESTAMPTZ,
  state         TEXT NOT NULL
                CHECK (state IN ('CREATING', 'READY', 'ARCHIVING',
                                 'ARCHIVED', 'RESTORING')),
  UNIQUE (owner_uuid, name)
);

CREATE TABLE player_world_ban (
  world_id   UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  uuid       UUID NOT NULL,
  banned_by  UUID NOT NULL,
  reason     TEXT,
  banned_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (world_id, uuid)
);

CREATE TABLE player_world_ownership_log (
  id            BIGSERIAL PRIMARY KEY,
  world_id      UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  from_uuid     UUID NOT NULL,
  to_uuid       UUID NOT NULL,
  reason        TEXT NOT NULL,          -- MANUAL, ADMIN, INACTIVITY
  transferred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE player_world_archive (
  world_id     UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  object_key   TEXT NOT NULL,           -- path or object storage key, not the blob
  size_bytes   BIGINT NOT NULL,
  checksum     TEXT NOT NULL,           -- sha256 of the archive
  archived_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  restore_count INT NOT NULL DEFAULT 0,
  PRIMARY KEY (world_id, archived_at)   -- a world may be archived more than once
);

CREATE TABLE player_world_member (
  world_id   UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  uuid       UUID NOT NULL,
  role       TEXT NOT NULL CHECK (role IN ('OWNER', 'BUILDER', 'VISITOR')),
  invited_by UUID,
  joined_at  TIMESTAMPTZ,
  PRIMARY KEY (world_id, uuid)
);

-- "which worlds is this player in" is the hot lookup: the proxy runs it on
-- every join and on every tab completion (FR-24c). The primary key above
-- cannot serve it.
CREATE INDEX player_world_member_uuid_idx ON player_world_member (uuid);

CREATE TABLE player_world_invite (
  world_id   UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  uuid       UUID NOT NULL,
  invited_by UUID NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (world_id, uuid)
);

CREATE INDEX player_world_invite_uuid_idx ON player_world_invite (uuid);

-- One row per player per world snapshot. (generation, sequence) identifies
-- the snapshot this profile is consistent with; profiles are only ever
-- committed in the same transaction as that snapshot's manifest pointer.
-- See section 5.4 and MN-6a.
CREATE TABLE player_world_profile (
  world_id       UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  uuid           UUID NOT NULL,
  generation     BIGINT NOT NULL,
  sequence       INT NOT NULL,
  format_version INT NOT NULL,          -- FR-17
  data           BYTEA NOT NULL,        -- inventory, ender chest, xp, health, food, location
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (world_id, uuid, generation, sequence)
);

-- Pruning older snapshots (profiles.retain-snapshots) walks this index.
CREATE INDEX player_world_profile_snapshot_idx
  ON player_world_profile (world_id, generation, sequence);

CREATE TABLE player_world_transfer_request (
  world_id   UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  to_uuid    UUID NOT NULL,
  from_uuid  UUID NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,      -- transfers.pending-expiry-days, FR-32
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (world_id, to_uuid)
);

CREATE INDEX player_world_transfer_request_to_idx
  ON player_world_transfer_request (to_uuid);

-- Backs the resume prompt in FR-13. Written when a player leaves a world.
CREATE TABLE player_last_world (
  uuid     UUID PRIMARY KEY,
  world_id UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  left_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE worlds_node (
  node_id       TEXT PRIMARY KEY,
  address       TEXT NOT NULL,
  loaded_worlds INT NOT NULL DEFAULT 0,
  online_players INT NOT NULL DEFAULT 0,
  heap_percent  INT,
  tps           NUMERIC(4,1),
  draining      BOOLEAN NOT NULL DEFAULT false,
  last_seen     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pending_transfer (
  uuid       UUID PRIMARY KEY,
  world_id   UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  node_id    TEXT NOT NULL,             -- node the proxy routed to
  generation BIGINT NOT NULL,           -- lease generation the route was resolved against
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`pending_transfer` is written by the proxy plugin immediately before it sends
a player to a `worlds` node, and consumed by that node on join. Rows older
than `transfers.expiry-seconds` (default 60) are treated as expired. It
carries the target `node_id` so a player who lands on the wrong node (a stale
Velocity route, a node restart mid-handoff) is bounced to lobby rather than
triggering a second load of a world that is leased elsewhere. It also carries
the `generation` the route was resolved against: a node that receives a
handoff naming a generation other than the one it currently holds sends the
player back to lobby, because the world was taken over between routing and
arrival.

Two derived facts are deliberately not stored as columns:

- **Whether a world is loaded** is derived from the lease
  (`assigned_node IS NOT NULL AND lease_expires > now()`), never from a
  `state` value. A node that crashes cannot update its own row, so a
  `LOADED` state would stay set forever while the lease expired underneath
  it; placement, `/world browse` (FR-9g) and the admin list would then all
  disagree with reality. `state` covers only the lifecycle the lease cannot
  express, and includes the transitional values `ARCHIVING` and `RESTORING`
  so that a crash part-way through FR-35 or FR-36 is recoverable.
- **A member's ownership** is `player_world.owner_uuid`. The `OWNER` value in
  `player_world_member.role` is a denormalised convenience for role lookups
  and must be updated in the same transaction (FR-31a).

## 5. Functional requirements

### 5.1 World creation

- FR-1: A player may create a world with `/world create <name>`, subject to a
  configurable cap on the number of worlds they **own** (`worlds.max-per-player`,
  default 2) and to the per-node capacity limits in MN-15. Membership of
  someone else's world never counts against this cap, so joining a public
  world can never block a player from creating their own.
- FR-1a: Creation is a network operation, not a backend-local one. The proxy
  resolves a node through the placement service (MN-14), that node acquires
  the lease (MN-8) **before** any world folder is created, and only then does
  generation begin. A world that is created without a lease has no owner in
  the storage sense and cannot be safely uploaded. On success the node commits
  an initial snapshot (MN-6a) so that a world exists in object storage before
  the first player ever enters it.
- FR-2: Creation generates a random seed unless the player supplies one. A
  world always logically has all three dimensions — overworld, nether and end
  — sharing that seed and stored as `<folder>`, `<folder>_nether` and
  `<folder>_the_end`. The three are a single unit for every other purpose in
  this document: membership, borders, the visibility group (5.5), idle unload
  (FR-25), sync (MN-6a) and archival all treat them together. Only their
  *materialisation* is deferred, per FR-4: the overworld is created at
  `/world create`, the other two on first transit. A dimension that has not
  been materialised yet is created on demand with the world's stored seed, so
  the result is identical to having created it up front.
- FR-2a: `folder` is derived from the world's UUID (for example
  `pw_<id-with-dashes-stripped>`), never from the player-supplied `name`.
  Deriving it from the name is unsafe in three separate ways: the sibling
  folders in FR-2 mean a world whose folder is `foo_nether` collides with the
  nether of a world whose folder is `foo`, and the `UNIQUE` constraint on
  `folder` does not catch that; player-supplied text reaches a filesystem
  path; and a case-insensitive filesystem collapses names the database treats
  as distinct. `name` remains free text for display and for
  `/world join <owner> [name]`.
- FR-3: A world border of `border_radius` is applied to the overworld and the
  end, and `border_radius / 8` to the nether so the two line up in world
  coordinates. Borders are applied whenever a dimension is materialised
  (FR-2) and re-asserted on every load, because a border is persisted in
  `level.dat` and must not be trusted to survive a restore from object
  storage. Borders are enforced server-side and fixed after creation in v1.
  Note that this interacts with FR-9f: a world created private at radius 5000
  and later made public cannot be enlarged, which is the most likely reason
  border resizing returns as a v1.1 request.
- FR-3a: Portal linking between a world's own dimensions must be handled
  explicitly in `PlayerPortalEvent` and `EntityPortalEvent`. Bukkit's default
  portal search resolves against the server's primary world, so without this a
  player entering a nether portal in their own world lands in the wrong
  dimension or in another player's world. Coordinate scaling (8:1 overworld to
  nether) must be applied manually, and the end portal and return portal must
  target the correct per-world end.
- FR-3b: Ender dragon state (`hasBeenKilled`, gateway generation, respawn) is
  per world and must survive an unload and reload cycle.
- FR-4: World generation is the most disruptive main-thread operation in the
  plugin and must be budgeted, not waved through. `createWorld` blocks the
  main thread for as long as it takes, FR-2 requires three of them, and the
  node is simultaneously ticking other players' worlds — so a naive
  implementation freezes every player on the node for several seconds every
  time someone runs `/world create`. Required behaviour:
  - Only the overworld is created eagerly. The nether and end are created
    lazily on first transit, which the portal handling in FR-3a already
    intercepts, and on creation of the end portal room. This turns one
    multi-second stall into three smaller ones spread over the world's life.
  - Spawn-area pre-generation is capped at `worlds.pregen-spawn-chunks`
    (default a 3x3 chunk area) and runs through Paper's asynchronous chunk
    loading API, never a synchronous `getChunkAt` loop.
  - Measured stall per `createWorld` call is a release-gating number. If it
    exceeds `worlds.create-stall-budget-ms` (default 1500) on target
    hardware, creation is routed to a node with no other loaded worlds.
  - Show the player a progress title throughout.
- FR-5: On success the player is teleported to the world spawn with a fresh
  profile (empty inventory, full health, 0 XP).

### 5.2 Membership and invites

- FR-6: The owner may invite with `/world invite <player>`. This writes a
  `player_world_invite` row with a default 10 minute expiry and notifies the
  target if they are online anywhere on the network. The notification is
  delivered by the proxy plugin, which is the only component that can see
  players on other backends; a backend node cannot message across the
  network by itself. This is why `/world invite` and the other management
  commands are registered proxy-side (section 6).
- FR-7: The invitee accepts with `/world accept <owner>`, which promotes the
  invite to a `player_world_member` row and sends them to the world.
- FR-8: The owner may run `/world kick <player>` and `/world members`. Kicking
  an online member removes them from the world immediately and returns them
  to lobby.
- FR-9: Roles: OWNER (full control), BUILDER (build and break), VISITOR
  (interact only, no block placement or breaking, no container access unless
  the owner enables it). All three roles ship in v1, because public worlds
  depend on VISITOR being a real role rather than a placeholder.

### 5.2a Public worlds and browsing

- FR-9a: A world has a `visibility` of PRIVATE (default) or PUBLIC. The owner
  toggles it with `/world public on|off` and may set a one-line description.
- FR-9b: `/world browse` lists PUBLIC worlds with name, owner, description and
  current online count. Invite-only worlds never appear here and their online
  counts are never exposed anywhere.
- FR-9c: Joining a PUBLIC world requires no invite and grants VISITOR on first
  join. The owner may promote a visitor to BUILDER with `/world promote`.
  Invites continue to work alongside this and may grant BUILDER directly.
- FR-9d: The owner may ban a player from their world with `/world ban`, which
  removes membership, ejects them if online, and blocks rejoin even while the
  world is public. Bans are per world and independent of network bans.
- FR-9e: Per-world settings the owner controls: PVP on or off, whether visitors
  can open containers, whether visitors can use redstone and doors, and mob
  griefing. Defaults are safe (PVP off, containers locked to BUILDER and above).
  These live in `player_world.settings` as JSONB, are applied to all three
  dimensions on load, and are re-asserted after a restore from object storage
  for the same reason as FR-3: PVP and the mob-griefing gamerule are persisted
  in `level.dat`, so the database value must win over whatever the restored
  folder carries.
- FR-9h: Making a world public requires `gzmn.worlds.public`, which is not
  granted by default. A public world admits strangers to a node whose other
  tenants are private worlds protected only by the isolation logic in 5.5, so
  the ability to open that door is gated until that logic has been exercised
  against real unfamiliar players. The placement service treats this as a
  scoring term (MN-15a) rather than a hard partition.
- FR-9f: Turning a world from PUBLIC back to PRIVATE does not remove existing
  members. It only stops new players from browsing in. The owner must kick
  explicitly if they want people gone.
- FR-9g: A public world counts against the loaded-world cap like any other, and
  the browse list must show whether a world is currently loaded so a player is
  not silently refused after picking one.

### 5.3 Joining and leaving

- FR-10: `/world join <owner> [name]` works from any server on the network.
  The proxy plugin validates membership, writes `pending_transfer`, then
  connects the player to `worlds`.
- FR-11: On `PlayerJoinEvent` the backend places the player in the holding
  area (section 3.1) and does no blocking work in the event handler, because
  NFR-2 forbids database access on the main thread. The lookup of
  `pending_transfer`, the world load if it is not already loaded, and the
  profile restore all run asynchronously; the teleport is scheduled back onto
  the main thread on completion. The holding area exists precisely to give the
  player somewhere to be during this window, and it is not a world they can
  interact with or see anyone else from.
  - If the transfer row is missing or expired, send the player to lobby with
    an explanatory message.
  - If `node_id` does not match this node, or `generation` does not match the
    lease this node holds, send the player to lobby: the world moved between
    routing and arrival.
  - If the sequence does not complete within `transfers.holding-timeout-seconds`
    (default 30), send the player to lobby rather than leaving them in the
    holding area, per section 9.
- FR-12: `/world leave` returns the player to lobby. Their profile is
  persisted by the snapshot commit this triggers (FR-15).
- FR-13: Disconnecting inside a player world triggers the same snapshot commit
  and writes `player_last_world`, so a rejoin from lobby offers a resume
  prompt.

### 5.4 Inventory and state separation

- FR-14: A player's inventory, armour, offhand, ender chest, XP level and
  progress, health, hunger, saturation, potion effects and last location are
  scoped per `(uuid, world_id)`.
- FR-15: **Profiles are only ever persisted as part of a world snapshot
  commit.** There is no independent profile autosave timer. A snapshot commit
  (MN-6a) is triggered by:
  - the periodic incremental sync (`storage.sync-minutes`),
  - any player leaving the world, by `/world leave`, kick, ban or disconnect,
  - unload, migration and archival.

  Between commits a player's live state exists only in memory, and is lost
  together with the world state it belongs to if the node dies. That symmetry
  is the entire point of the rule.
- FR-15a: The reason for FR-15 is that profiles and world data live in
  different storage systems with different failure domains, and any skew
  between their durability points is an item duplication bug. Concretely,
  with a 5-minute profile autosave against a 10-minute world sync: a player
  empties a chest at T+9, the profile autosave records the items at T+10, the
  node dies at T+11, and recovery rolls the world back to the T+0 manifest —
  restoring the chest's contents while the player also still holds them. The
  same mechanism run in reverse (deposit, autosave, crash) destroys items
  instead. Every crash would produce some of each. Committing both through
  one transaction removes the window rather than narrowing it.
- FR-15b: A profile row is keyed by `(world_id, uuid, generation, sequence)`,
  identifying the snapshot it is consistent with. On load, the node reads the
  profiles belonging to the snapshot named by `player_world.manifest_key` —
  the same snapshot whose manifest it just restored — so world and player
  state always come back from the same instant. A player with no row for that
  snapshot has never played in the world and gets a fresh profile per FR-5.
- FR-15c: Snapshots older than `profiles.retain-snapshots` (default 3) are
  pruned by the maintenance job in FR-40, along with their manifests. Keeping
  more than one is what makes FR-16a possible.
- FR-16: Writes must be atomic per player, and the commit that carries them is
  atomic across all players in the world (MN-6a). A failed write must not
  leave a partially serialised profile, and the plugin must refuse to load a
  profile it cannot deserialise, sending the player to lobby with an error
  rather than granting an empty inventory.
- FR-16a: FR-16 alone would lock a player out of a world permanently, since a
  profile that cannot be deserialised will fail identically on every attempt
  and no player-facing command can clear it. There must therefore be an admin
  repair path: `/world admin profile <world> <player>` shows the retained
  snapshots for that player, and `/world admin profile rollback` restores an
  earlier one. Because FR-15c retains several snapshots, this is a rollback
  rather than a wipe. Wiping to a fresh profile remains available as an
  explicit last resort with typed confirmation.
- FR-17: Serialisation format is version-tagged in the `format_version`
  column, so future schema changes can migrate rather than discard. The tag is
  a column rather than a field inside the payload so that a payload which
  cannot be parsed at all can still be identified and migrated.
- FR-17a: Profile payloads are stored as `BYTEA`, not `JSONB`. Item stacks
  carry arbitrary NBT, including raw binary, so a JSON encoding requires
  base64 anyway and buys no queryability. More concretely, PostgreSQL rejects
  the null byte U+0000 inside `jsonb` string values with `unsupported Unicode
  escape sequence`, and serialised item NBT will contain null bytes.

### 5.5 Visibility isolation

A visibility group is one player world, meaning all three of its dimensions
treated as a single unit. Moving between overworld, nether and end must not
change who a player can see.

Resolved: lobby players and player-world players are mutually invisible, as are
players in the main survival server and players in any player world. Because
lobby, main survival and `worlds` are separate backends behind Velocity, most
of that isolation is already free: a Paper backend only knows about the players
connected to it, so tab list, entity visibility, chat and name completion do
not cross between them by default. The work is therefore split:

- Backend requirements (FR-18 to FR-24) isolate one player world from another
  within the `worlds` backend.
- Proxy requirements (FR-24a to FR-24d) close the network-level leaks that
  would otherwise expose presence across backends.

Requirements:

- FR-18: On join and on every `PlayerChangedWorldEvent`, recompute visibility
  in both directions using `Player#hidePlayer` and `showPlayer`, so players in
  different groups never appear in each other's tab list or as entities.
- FR-19: **All server-wide broadcasts** are suppressed globally and re-emitted
  only to the player's own group. Enumerating three of them is not enough,
  because every one of them leaks presence and names between two worlds on the
  same node in exactly the same way. At minimum: join, quit, death, advancement
  announcements (the `announceAdvancements` gamerule broadcasts to every player
  on the server, not per world), sleep and "players skipping night" messages,
  raid start and defeat, and `/me`. The rule is that a broadcast reaching
  `Bukkit.broadcast` or `Server#getOnlinePlayers` is a defect unless it has
  been explicitly routed through the group filter; treat the list above as the
  known cases rather than the complete one.
- FR-20: Chat recipients are scoped to the group by mutating the viewer set on
  `AsyncChatEvent` rather than cancelling and rebroadcasting.
- FR-21: Name completion is filtered via `AsyncTabCompleteEvent`, and commands
  the player may not use are hidden via `PlayerCommandSendEvent`.
- FR-22: Command access inside a player world is an **allow-list**, not a
  deny-list of known offenders. A list of EssentialsX commands to block
  (`/list`, `/seen`, `/near`, `/msg`, `/tpa`, `/tphere`) drifts the moment a
  plugin is added or updated, and it misses the surfaces that ship with the
  server: vanilla `/list` and `/tell`, and target selectors (`@a`, `@p`) for
  anyone holding the permission to use them. Commands permitted inside a
  player world are therefore explicitly enumerated in config, everything else
  is denied, and the presence-revealing ones are replaced with group-scoped
  equivalents provided by this plugin. `gzmn.worlds.admin` is exempt.
- FR-23: Any player-count placeholder used on scoreboards or in MOTDs resolves
  to the group count, not the server count. The plugin registers its own
  PlaceholderAPI expansion for this.
- FR-24: The Discord bridge must not relay join, quit or chat events
  originating inside a player world.
- FR-24a: No network-wide tab list plugin may be installed on the proxy. If one
  is ever added it will override backend `hidePlayer` calls and break FR-18.
  Tab list authority stays on the backend.
- FR-24b: Proxy-level commands that enumerate players (`/glist`, `/server`,
  `/send`, any friend or party plugin, cross-server `/msg`) must exclude
  players inside player worlds for anyone who is not a member of that world.
  Admins with `gzmn.worlds.admin` are exempt.
- FR-24c: Proxy tab completion of player names excludes players in worlds the
  requester is not a member of.
- FR-24d: The network MOTD player count is a design decision, not a leak to
  fix: it reveals only a total, never identities. Decide whether it counts
  player-world players at all, and document the choice.

### 5.6 Lifecycle and cleanup

- FR-25: A world whose three dimensions contain no players enters an idle
  state. After a configurable grace period (default 10 minutes) all three
  dimensions unload together, preceded by a snapshot commit (MN-6a) and
  followed by the lease release. Any join into any dimension of that world
  resets the timer and cancels the pending unload.
- FR-25a: Unload order is end, then nether, then overworld. Each call checks
  the boolean return of `Bukkit.unloadWorld(world, true)`. A false return means
  something still holds the world (a lingering player, a force-loaded chunk, a
  plugin ticket). Log it with the holding cause where determinable, abort the
  remaining unloads for that world, and retry on the next idle cycle. The
  retry after `worlds.unload-retry-minutes` re-attempts the **whole world**,
  including dimensions that unloaded successfully on the previous attempt and
  have since been reloaded, because a partially unloaded world has a split
  visibility group and must not be left in that state. The lease is held, and
  the snapshot is not committed, until all three dimensions are down.
- FR-25b: The plugin must never cache `World` references across an unload.
  Worlds are resolved by name or UUID through `Bukkit.getWorld` at use time.
- FR-25c: Loaded worlds run with spawn chunks disabled (`spawnChunkRadius` 0)
  so an idle world costs no meaningful tick time during the grace period. This
  is applied on every load, not once at creation: the value is persisted in
  `level.dat` and so arrives from object storage with whatever the folder
  happened to carry.
- FR-25d: Accepted consequence: an unloaded world is frozen. Furnaces, crop
  growth, mob spawning and time of day do not advance while the owner is
  offline. This is documented player-facing behaviour, not a bug.
- FR-26: Loaded-world capacity is a **per-node** limit, `nodes.max-worlds`
  (default 5), enforced by the placement service at routing time (MN-15).
  There is no separate global cap: in a pool of interchangeable nodes a
  network-wide ceiling configured in a backend's `config.yml` has no
  well-defined owner, and it was the source of the ambiguity between
  `worlds.max-loaded` and `nodes.max-worlds` in v0.2. If every node is at
  capacity the join is refused with a clear message rather than evicting an
  active world. The cap counts worlds, not dimensions, so 5 loaded worlds on
  a node means up to 15 Bukkit `World` instances on it.
- FR-27: `/world delete` requires typed confirmation and performs the archival
  flow in FR-35 — acquiring the lease, packing all three dimensions to object
  storage under `archive.*`, and setting `state` to ARCHIVED. Hard deletion is
  a manual admin action, never automatic.
- FR-28: On server shutdown every loaded world commits a final snapshot
  (MN-6a) and releases its lease before the plugin disables, so a planned
  restart loses nothing and leaves no lease to expire.

### 5.7 Ownership transfer

- FR-29: The owner may run `/world transfer <player>`, which requires typed
  confirmation and that the target is already a member of the world.
- FR-30: The transfer must check the target's per-player world cap. If it would
  put them over, the transfer is refused with a clear message rather than
  silently exceeding the cap.
- FR-31: On transfer the target becomes OWNER, the previous owner is demoted to
  BUILDER (not removed), and a row is written to
  `player_world_ownership_log`. The world's `folder` and id never change, so
  profiles, bans and members survive intact.
- FR-31a: A transfer updates `player_world.owner_uuid` and both
  `player_world_member.role` rows in a single transaction.
  `owner_uuid` is authoritative; the `OWNER` role value is a denormalised
  convenience. Any code that has to choose between them chooses `owner_uuid`.
- FR-32: If the target is offline, the transfer is written to
  `player_world_transfer_request` and offered to them on next login, expiring
  after `transfers.pending-expiry-days` (default 7). Expired rows are removed
  by the maintenance job in FR-40. The cap check in FR-30 is repeated at
  acceptance time, not only at offer time, since the target may have acquired
  another world in the interim.
- FR-33: Admins with `gzmn.worlds.admin` may force a transfer with reason
  ADMIN, for the case where an owner has left GZMN and the remaining members
  want to keep the world.

### 5.8 Archival and storage

- FR-34: A world with no logins for `archive.after-days` (default 90) is
  archived automatically. The owner is warned in game and, if the Discord
  bridge supports it, by DM at 14 days and 3 days before.
- FR-35: Archiving **acquires the lease first** (MN-8), exactly like a load
  would. Without it there is nothing to stop a node acquiring the lease and
  loading the world while the archiver is part-way through deleting its
  folders. With the lease held, the archiver sets `state` to ARCHIVING,
  unloads the world if it is loaded, packs all three dimension folders into a
  single compressed archive, writes the object key, size and sha256 to
  `player_world_archive`, sets `state` to ARCHIVED, and removes the live
  folders and the per-world object prefix only after the checksum of the
  written archive verifies. The lease is released last. A crash at any point
  leaves `state` at ARCHIVING with an expired lease, which the maintenance job
  in FR-40 retries from the beginning — safe, because nothing is deleted
  before the checksum verifies.
- FR-36: `/world restore <name>` by the owner, or by an admin, acquires the
  lease, sets `state` to RESTORING, unpacks the archive, verifies the
  checksum, uploads the result as a fresh snapshot (MN-6a), and sets `state`
  to READY. Restore is subject to the same free-space check as creation. As
  with FR-35, a crash leaves `state` at RESTORING with an expired lease and
  the job is retried from the archive, which is never deleted by this flow.
- FR-37: Archives are never deleted automatically. Hard deletion is an admin
  action with typed confirmation.
- FR-38: Storage layout. Live worlds sit on the local filesystem as plain
  Anvil folders, because that is the only format the server can tick from.
  Archives go to object storage (S3-compatible, for example MinIO on
  GZMNServer) or to a filesystem path, selected by config. PostgreSQL stores
  metadata and the object key only. World data is never stored as a blob in
  PostgreSQL: it would bloat the database, wreck backup and restore times, and
  buy nothing, since the server has to materialise the folder on disk before it
  can load the world anyway.

### 5.9 Moderation and scheduled maintenance

- FR-39: A player inside a player world may run `/world report <player>
  <reason>`, which writes world id, reporter, target, reason and timestamp to
  a table network staff can read, and captures the recent group-scoped chat
  log for that world. This exists because the isolation in 5.5 is deliberately
  total: by design nobody outside a world can see what happens inside it, so
  without an explicit channel a public world has no route to staff at all.
  Adding public worlds (5.2a) is what makes this necessary — before that,
  every participant was invited by the owner.
- FR-40: Periodic work runs on exactly one process at a time, elected with a
  PostgreSQL advisory lock. Every node in an interchangeable pool would
  otherwise run every job simultaneously, duplicating archival and racing on
  cleanup. The maintenance job covers: auto-archival and its warnings (FR-34),
  retry of interrupted ARCHIVING and RESTORING states, expiry of invites,
  `pending_transfer` rows and pending ownership transfers, snapshot and
  manifest pruning (FR-15c), object storage garbage collection (MN-2b), and
  quarantine retention (MN-13a). Holding the advisory lock is a prerequisite
  for each; losing it mid-job aborts the job rather than continuing.

## 6. Commands and permissions

Management commands are registered on the **proxy**, not on the backend. In
v0.2 most of them were backend-only, which meant an owner could not invite,
ban, promote or configure their own world unless they were standing in it —
and by FR-25 a world is unloaded most of the time, so the normal case was that
none of these commands were reachable. They are all database operations, so
the proxy can serve them from anywhere on the network. Where a live world must
also react — ejecting a banned player, kicking an online member, applying a
changed setting — the proxy notifies the holding node over plugin messaging,
and the command succeeds whether or not the world is currently loaded.

`owner` in the Permission column means the caller is `player_world.owner_uuid`
for the target world. `gzmn.worlds.admin` overrides every `owner` and `member`
check in this table.

| Command | Where | Permission | Notes |
| --- | --- | --- | --- |
| `/world create <name> [seed]` | proxy | `gzmn.worlds.create` | Placement and lease first (FR-1a) |
| `/world join <owner> [name]` | proxy | `gzmn.worlds.join` | Membership checked proxy-side |
| `/world invite <player>` | proxy | owner | Target may be on any server |
| `/world accept <owner>` | proxy | `gzmn.worlds.join` | Consumes invite |
| `/world kick <player>` | proxy | owner | Ejects via the holding node if online |
| `/world members` | proxy | member | |
| `/world leave` | backend | member | Returns to lobby; commits a snapshot |
| `/world delete <name>` | proxy | owner | Confirmation required |
| `/world browse` | proxy | `gzmn.worlds.join` | PUBLIC worlds only |
| `/world public on\|off [desc]` | proxy | owner + `gzmn.worlds.public` | Toggles visibility (FR-9h) |
| `/world promote <player>` | proxy | owner | VISITOR to BUILDER |
| `/world ban <player> [reason]` | proxy | owner | Per world, ejects if online |
| `/world unban <player>` | proxy | owner | |
| `/world set <setting> <value>` | proxy | owner | PVP, containers, mob griefing |
| `/world transfer <player>` | proxy | owner | Confirmation, target must be a member |
| `/world report <player> <reason>` | backend | member | FR-39, in-world only |
| `/world restore <name>` | proxy | owner | Unpacks an archived world |
| `/world admin list` | proxy | `gzmn.worlds.admin` | |
| `/world admin unload <id>` | proxy | `gzmn.worlds.admin` | Routed to the holding node |
| `/world admin migrate <id> <node>` | proxy | `gzmn.worlds.admin` | MN-21 |
| `/world admin transfer <id> <player>` | proxy | `gzmn.worlds.admin` | Reason ADMIN |
| `/world admin profile <id> <player>` | proxy | `gzmn.worlds.admin` | FR-16a, list and roll back |

## 7. Configuration

Backend `config.yml`:

- `worlds.max-per-player` (default 2, counts **owned** worlds only)
- `worlds.idle-unload-minutes` (default 10, grace period after the last player
  leaves all three dimensions)
- `worlds.unload-retry-minutes` (default 2)
- `worlds.default-border-radius` (default 5000)
- `worlds.dimensions` (list, default `[overworld, nether, end]`)
- `worlds.nether-border-divisor` (default 8)
- `worlds.pregen-spawn-chunks` (default 3, side length of the pre-generated
  spawn square, FR-4)
- `worlds.create-stall-budget-ms` (default 1500, FR-4)
- `worlds.storage-path` (live Anvil folders)
- `worlds.default-visibility` (default `PRIVATE`)
- `worlds.public.browse-page-size` (default 10)
- `worlds.allowed-commands` (allow-list enforced inside player worlds, FR-22)
- `archive.backend` (`filesystem` or `s3`)
- `archive.path` or `archive.s3.*` (endpoint, bucket, credentials)
- `archive.after-days` (default 90)
- `archive.warn-days` (default `[14, 3]`)
- `archive.compression` (default `zstd-3`; region files are already
  zlib-compressed internally, so the higher levels cost several times the CPU
  for a few percent of size — confirm against real worlds in milestone 11)
- `profiles.retain-snapshots` (default 3, FR-15c; there is deliberately no
  `profiles.autosave-minutes` — see FR-15)
- `invites.expiry-minutes` (default 10)
- `transfers.pending-expiry-days` (default 7)
- `transfers.expiry-seconds` (default 60, `pending_transfer` row lifetime)
- `transfers.holding-timeout-seconds` (default 30, FR-11)
- `maintenance.interval-minutes` (default 5, FR-40)
- `database.*` (host, port, database, user, password, pool size)

Proxy `config.toml`: database credentials, lobby server name, transfer expiry
seconds. Note there is no `worlds` server name: nodes register themselves
dynamically (MN-17) and are resolved through the placement service (MN-14),
so a fixed backend name in proxy config would defeat both.

## 8. Non-functional requirements

- NFR-1: World load has two budgets, because they differ by orders of
  magnitude and a single number cannot describe both:
  - **Warm load** (the node has a local working copy matching the current
    manifest): 5 seconds, and must not drop the backend below 18 TPS.
  - **Cold load** (the world must be fetched from object storage):
    `storage.cold-load-budget-seconds`, default 60, dominated by transfer
    time and therefore a function of world size and link speed rather than
    of this plugin. Exceeding the warm budget here is expected, not a defect.
    A progress message is mandatory throughout (MN-25), and the placement
    service prefers a node with a warm copy where one exists (MN-15a).

  v0.2 stated a flat 5-second budget that MN-25 then conceded, and that a
  multi-gigabyte cold fetch cannot meet on any link.
- NFR-2: All database access is off the main thread. No blocking JDBC call in
  an event handler. See FR-11 for how this shapes the join path.
- NFR-3: Disk usage per world is bounded by the borders across all three
  dimensions, but the bound is much larger than it sounds and must be planned
  with a real figure. At the default `border_radius` of 5000 the overworld is
  10 000 x 10 000 blocks, roughly 380 region files if fully explored, and
  region files run to tens of megabytes each — so a well-played world is
  several gigabytes per dimension, with the nether growing faster per block
  explored than the overworld and the end typically small. Sizing follows from
  that number, not from the world count: local scratch
  (`storage.local-cache-max-gb`), the object storage bucket, the cold-load
  budget in NFR-1 and the sync interval in MN-6 all have to be derived from
  it. Measure a real world in milestone 6 and record the figure here. Monitor
  total usage and refuse creation below a configurable free-space threshold.
- NFR-3a: 15 loaded Bukkit worlds carry a fixed per-world memory and tick cost
  even when empty. Verify heap headroom on the node before raising
  `nodes.max-worlds`. Note that unloading a world's dimensions *separately* is
  not available as a remedy in v1: FR-25 unloads all three together, FR-25a
  treats a partial unload as a state to retry out of, and 5.5 defines the
  visibility group as all three dimensions as one unit. If profiling shows
  idle dimensions are too expensive, the v1 answer is a shorter
  `worlds.idle-unload-minutes` or a lower `nodes.max-worlds`; per-dimension
  unload is a v1.1 change that would have to revisit all three of those
  requirements together.
- NFR-4: World folders are included in the existing backup schedule. An
  archived world must be restorable from backup without manual DB surgery.
- NFR-5: All player-facing messages are configurable and use MiniMessage.
- NFR-6: Structured logging for create, join, invite, kick, unload, delete,
  lease acquire and release, sync start and finish, and every fencing abort.
  Suitable for ingestion into your existing monitoring.
- NFR-7: Object storage transfers run off the main thread and are bounded by
  `storage.parallel-transfers`. A cold world load must not block the tick loop
  beyond the `createWorld` call itself.
- NFR-8: Every write path to object storage is idempotent and safe to retry.
- NFR-9: The system must tolerate losing all local node disk without losing a
  world. This is a testable property: wipe a node's scratch directory with the
  world unloaded, then load it and verify it is intact.

## 9. Failure modes to handle explicitly

- Backend `worlds` server is down when a player runs `/world join`: proxy
  reports the outage and leaves the player where they are.
- Database unreachable: the backend refuses new joins and creations and keeps
  already-loaded worlds playable, but it **cannot commit a snapshot**, since
  the manifest pointer and the profiles are written to PostgreSQL (MN-6a).
  There is deliberately no disk-backed queue of pending profile writes: local
  disk is a disposable working copy (MN-1) that MN-13 quarantines on the next
  startup, so a queue written there is lost exactly when it is needed, and
  replaying it later would reintroduce the skew FR-15a exists to remove.
  Instead, the node keeps playing on the last committed snapshot, alerts, and
  after `storage.max-sync-failure-minutes` performs a forced unload, ejecting
  players to lobby with an explanatory message. Progress since the last
  snapshot is lost, consistently across world and profiles. This is the same
  bound as a node crash, and it is stated rather than hidden.
- Player is transferred but the world fails to load: send to lobby, log, do
  not spawn them in the holding area indefinitely. Bounded by
  `transfers.holding-timeout-seconds` (FR-11).
- Two proxies or a restart leaving a stale `pending_transfer`: rows expire
  after `transfers.expiry-seconds`, and a row naming a stale node or
  generation is rejected on arrival (FR-11).
- Player kicked mid-save: the snapshot commit that carries their profile
  completes before the session is released, bounded by
  `storage.commit-timeout-seconds` (default 15). If the commit does not
  complete in that window the kick proceeds anyway and the player's progress
  since the last snapshot is lost, because an unbounded hold on the kick path
  turns a slow database into an unkickable player.

## 10. Decisions

- OQ-1: Resolved. Lobby and player-world players are mutually invisible. Most
  of this comes free from the backend split; see section 5.5.
- OQ-2: Resolved. Every player world gets its own nether and end, created at
  the same time as the overworld.
- OQ-3: Resolved. Worlds are archived after 90 days of inactivity to
  compressed archives in object storage, with metadata in PostgreSQL and a
  self-service restore. World data is never stored as a blob in the database.
- OQ-4: Resolved. Worlds may be PUBLIC and browsable or PRIVATE, with invites
  working in both modes. Public joiners get VISITOR by default.
- OQ-5: Resolved. Ownership is transferable by the owner, or by an admin when
  the owner has left.

- OQ-6: Resolved. The network MOTD counts player-world players. It is a single
  integer with no identities attached, excluding them makes the network look
  emptier than it is, and a discrepancy between the MOTD count and the counts
  in `/world browse` would itself be a signal worth avoiding. FR-24d is
  answered.
- OQ-7: Resolved. Making a world public requires `gzmn.worlds.public`,
  ungranted by default (FR-9h). One config line now, and reversible; the
  opposite order is not.
- OQ-8: Resolved. `/world report` plus a staff-readable table and captured
  chat log (FR-39). The isolation in 5.5 is total by design, so without an
  explicit channel a public world has no route to staff at all.
- OQ-9: Resolved. `worlds.max-per-player` is 2 and counts owned worlds only
  (FR-1). At 1 a player could never receive a transfer, and the ambiguity
  over whether membership counted meant joining one public world could block
  a player from creating their own.
- OQ-11: Resolved in principle, to be measured. The sync interval is also the
  duplication window under FR-15a, so tightening it improves both. With the
  dirty-region tracking in MN-5a a 3 to 5 minute interval should be
  affordable; the default drops to 5 minutes, to be confirmed against a real
  world in milestone 6.

Still open, because both are facts about the deployment rather than design
decisions:

- OQ-10: How many `worlds` nodes at launch, and are they separate Pelican
  instances on GZMNServer or on separate hardware? Two instances on one host
  gives you the code path but none of the resilience.
- OQ-12: Does MinIO run on the same host as the nodes? If so, a host failure
  takes both the working copy and the source of truth, which undermines the
  point of the split. Note that NFR-9's test passes either way, so it is not
  evidence on this question.

## 11. Suggested milestones

1. Backend plugin: create a world, materialise its nether and end on first
   transit (FR-2, FR-4), load, unload, borders, teleport, and correct portal
   linking in both directions. Hardcoded owner, no database. Proves the
   lifecycle, and portal linking is the part most likely to surprise you, so
   do it first. Measure the `createWorld` stall here — it sets
   `worlds.create-stall-budget-ms` and decides whether lazy materialisation is
   sufficient on its own.
2. PostgreSQL schema plus membership and invites. Still single-server.
3. Visibility isolation (FR-18 to FR-24). Test with three accounts across two
   worlds and the lobby.
4. Per-world profiles, built against the snapshot-commit model in FR-15 from
   the start. The crash test here must be written against that model rather
   than against an independent autosave timer, or it will validate a design
   that milestone 6 then replaces — and on a single node with no sync interval
   it cannot observe the duplication in FR-15a at all, because both stores
   fail together. Re-run it as a two-store test in milestone 6.
5. Proxy plugin plus `pending_transfer` handoff, one `worlds` node registered
   in Pelican. Close the proxy-level leaks (FR-24a to FR-24d) in the same
   milestone, since that is when they first become reachable.
6. Object storage: content-addressed objects, manifests, snapshot commit,
   download on load, local cache. Still one node. Two acceptance tests beyond
   "it loads":
   - Prove a world survives a full wipe of local scratch (NFR-9). Use a
     mob-heavy world and verify the mobs are still there — an empty
     `entities/` directory loads perfectly and looks fine until you go
     looking for it (MN-2a).
   - Measure a real world's size and sync cost and record it in NFR-3, since
     the cold-load budget, cache sizing and sync interval all follow from it.
7. Leases and fencing (MN-8 to MN-13a), still on one node. Write the fencing
   tests here. The SIGSTOP test is a data-integrity test, not a liveness one:
   stop a node holding a world, let another take the lease and commit, resume
   the first, let it finish its upload and attempt its commit, then assert
   that the world still matches what the takeover node wrote. Under MN-2 and
   MN-3a that holds by construction, which is the point of the layout.
8. Second node: heartbeats, placement service, dynamic Velocity registration,
   `/world admin migrate`, node draining.
9. Public worlds: visibility toggle, `/world browse`, VISITOR role enforcement,
   per-world settings, bans.
10. Ownership transfer.
11. Cold archival and restore, including a full round trip on a real world.
12. Pelican panel view, if still wanted.

Milestones 6 and 7 deliberately land before the second node exists. Both are
far easier to debug when there is only one writer, and both are the parts that
lose data if they are wrong.

## 12. Multi-node operation

In scope for v1. The `worlds` role is a pool of interchangeable Paper nodes
rather than a single instance, and worlds move between them via object storage.

### 12.1 The constraint everything follows from

An Anvil world folder may be open in exactly one server process at a time.
There is no file locking and no coordination; two JVMs writing the same region
files corrupt them, often silently and often only visibly weeks later.
Therefore a loaded world lives on exactly one node, and every member of that
world is routed to that node. The unit of load balancing is the world, not the
player. A single world never spans nodes.

### 12.2 Storage model: object storage plus local copy

- MN-1: Object storage (S3-compatible, MinIO on GZMNServer) is the source of
  truth for world data at rest. Local node disk is a working copy only, and is
  treated as disposable.
- MN-2: **Object storage is immutable.** Live worlds are stored as individual
  objects per file, not as one archive, which is what makes incremental sync
  possible — but objects are content-addressed rather than path-addressed. A
  file's bytes live at `worlds/<world_id>/data/<sha256>`, and the manifest
  maps logical path to hash. Every object is therefore write-once: a node can
  only ever create an object whose contents already hash to its key, so no
  writer can corrupt or overwrite another writer's data. This is the property
  that makes MN-10's fencing real rather than advisory. Cold archives (section
  5.8) remain single compressed tarballs, since they are only ever restored
  whole.
- MN-2a: The synced set is the whole world folder, not just `region/`.
  Enumerating it, because omitting any of these is a silent data loss rather
  than a degradation:

  | Path | Contents lost if omitted |
  | --- | --- |
  | `region/` | Blocks and tile entities |
  | `entities/` | All mobs, item frames, armour stands, dropped items, boats, minecarts |
  | `poi/` | Villager workstation bindings; villages break subtly |
  | `data/` | Raids, maps, structure references |
  | `level.dat` | Spawn, time, weather, gamerules, **and the dragon fight state FR-3b requires** |

  Anything else present in the folder is synced too unless it matches
  `storage.exclude-globs` (default: `session.lock`, `uid.dat`).

  Note that Bukkit does not lay multi-world folders out the way vanilla does.
  For a world folder `foo`, the nether's regions are at
  `foo_nether/DIM-1/region/` and the end's at `foo_the_end/DIM1/region/`, each
  dimension folder carrying its own `level.dat` at its root. The path builder
  has to account for the `DIM-1` and `DIM1` segments.
- MN-2b: Because data objects are immutable they are never deleted by a
  writer. A garbage collection pass in the maintenance job (FR-40) removes any
  `worlds/<world_id>/data/<sha256>` not referenced by a retained manifest.
  Orphans are expected in normal operation: they are what a fenced node's
  uploads become.
- MN-3: A snapshot is described by a manifest object at
  `worlds/<world_id>/manifest/<generation>-<sequence>.json`, recording per
  file its logical path, size, mtime and sha256. `generation` is the lease
  generation that wrote it and `sequence` counts syncs within that lease, so
  manifest keys are also write-once: a node holding generation N cannot write
  a manifest for generation N+1. A world's state is defined by its manifest,
  never by a directory listing.
- MN-3a: **The commit point is a single conditional `UPDATE` in PostgreSQL**,
  not the manifest write. After uploading data objects and the new manifest,
  the node runs, in one transaction, the profile inserts for that snapshot
  (FR-15b) and:

  ```sql
  UPDATE player_world
     SET manifest_key = $new_key, last_played = now()
   WHERE id = $world_id
     AND assigned_node = $my_node_id
     AND generation = $my_generation;
  ```

  Zero rows affected means the lease was lost and the whole transaction rolls
  back, profiles included. The uploaded objects become orphans for MN-2b to
  collect and nothing that any reader can see has changed. This is what makes
  `player_world` the single linearization point for the system: the lease
  acquisition in MN-8 and the snapshot commit here are both atomic statements
  against the same row, so there is no ordering between nodes left to get
  wrong.
- MN-4: On load, the node reads `manifest_key`, fetches exactly that manifest,
  and materialises the world into `<local-scratch>/<world_id>/`. Content
  addressing makes the warm-start check cheap: a file whose hash is already in
  the local object cache is hard-linked or copied locally rather than
  downloaded. Local files are validated by size and mtime against the manifest
  rather than rehashed, since rehashing gigabytes is the last thing wanted
  during the join that NFR-1's budget applies to; a clean unload writes a
  completion marker, and a world whose marker is absent is fully rehashed
  before use.
- MN-5: On unload, the node commits a final snapshot (MN-6a) and then releases
  the lease. Local files are retained as a warm cache, subject to
  `storage.local-cache-max-gb` with least-recently-used eviction.
- MN-5a: **Uploads never read a file the server may still be writing.** Anvil
  region files are mutated in place — a chunk save rewrites the sector header
  and may relocate sectors — so reading one concurrently with a chunk save
  yields a torn region: a header pointing at sectors holding different chunk
  data. It also breaks MN-3's contract, because the bytes hashed and the bytes
  uploaded can differ. The procedure is therefore:
  1. `World#save()` on the main thread (it cannot be moved off it; only the
     upload can, per NFR-7).
  2. Snapshot the dirty files with `cp --reflink=auto`, which is close to free
     on XFS and btrfs and falls back to a plain copy elsewhere. **Hard links
     do not work here** — a hard link shares the inode, so an in-place region
     write is visible through it. This is the obvious first implementation and
     it silently does nothing.
  3. Hash and upload from the snapshot copies, never from the live folder.
- MN-5b: Sync tracks dirty regions rather than diffing the whole world. Chunk
  saves are hooked to record which region files were touched since the last
  snapshot; only those are copied, hashed and uploaded. Without this, each
  sync rehashes several gigabytes per world (NFR-3) and will not fit in the
  interval alongside normal play.
- MN-6: While a world is loaded, an incremental sync runs every
  `storage.sync-minutes` (default 5). This bounds data loss on a node crash to
  one sync interval, for world data and player profiles alike. Accept and
  document that bound.
- MN-6a: A **snapshot commit** is the unit of durability for the whole system,
  and is the only way either world data or profiles reach durable storage. It
  is: save the world, snapshot dirty files (MN-5a), upload their objects,
  write the manifest, then commit profiles and the manifest pointer in one
  transaction (MN-3a). It is triggered by the periodic sync, by any player
  leaving the world, by unload, migration, archival and shutdown (FR-15). Its
  atomicity is what removes the duplication window described in FR-15a.
- MN-7: Rejected alternative: a shared network filesystem (NFS or SMB) with
  the lease preventing concurrent access. Rejected because Minecraft chunk IO
  over a network filesystem is slow enough to be felt as chunk-load stutter,
  and because it makes the corruption failure mode quieter rather than louder.

### 12.3 Leases and fencing

- MN-8: `player_world` carries `assigned_node`, `lease_expires` and
  `generation`. A node acquires a lease with a single atomic conditional
  update (assign only where `assigned_node IS NULL` or `lease_expires < now()`)
  that increments `generation`. Acquisition is the only way to begin loading a
  world.
- MN-9: A node holding a loaded world heartbeats to extend `lease_expires`.
  Lease 180 seconds, heartbeat 30 seconds. v0.2's 60/20 pairing tolerated only
  two missed heartbeats, and a node ticking up to 15 Bukkit worlds can
  plausibly stall longer than that on a bad GC or a disk hiccup — at which
  point it loses a world it is still playing. The timings must also satisfy
  `nodes.dead-after-seconds < nodes.lease-seconds` (see MN-18), so a node is
  excluded from new placements before its lease can be taken.
- MN-10: Fencing is enforced by the storage layout, not by a pre-write check.
  A node that has lost its lease cannot damage a world, because data objects
  and manifests are write-once (MN-2, MN-3) and the commit is a conditional
  `UPDATE` guarded on `assigned_node` and `generation` (MN-3a). A stalled or
  partitioned node that resumes and completes its upload produces orphaned
  objects and a manifest nothing references.

  v0.2 required the node to re-check the lease before each write. That is
  necessary but not sufficient: a check and a write that are not one atomic
  operation do not fence anything, since a node can pass the check, stall
  (GC pause, disk stall, SIGSTOP), lose the lease while suspended, and then
  complete the write — which is exactly the scenario the check exists to
  prevent. The check is retained anyway, for a different purpose: it is how a
  node *discovers* it has been fenced, promptly rather than at commit time.
  On discovering it, the node stops ticking the world, drops its players to
  lobby, moves its local copy to quarantine, and alerts.
- MN-10a: A node must self-fence on losing the lease, not only when it is
  about to write. A fenced node that keeps ticking a world keeps accepting
  joins into it and keeps generating state that will be discarded, so the
  heartbeat failing to extend the lease is itself the trigger for MN-10's
  shutdown path.
- MN-11: Because objects are immutable and the pointer moves last, an upload
  interrupted at any point leaves the previous snapshot current and the world
  loadable. No staging area is needed — v0.2 required one because objects were
  path-addressed and therefore overwritable.
- MN-12: On clean unload the lease is released (`assigned_node` set NULL) after
  the final snapshot commit. On crash it simply expires, and the next node to
  acquire it starts from the snapshot named by `manifest_key`.
- MN-13: On node startup, any local scratch directory not covered by a lease
  this node currently holds is quarantined, not deleted and not uploaded. It is
  crash debris and may be newer or older than object storage.
- MN-13a: Quarantine is bounded by `storage.quarantine-max-gb` and
  `storage.quarantine-retain-days` (default 7), enforced by the maintenance
  job (FR-40), oldest first. `storage.local-cache-max-gb` governs only the warm
  cache, so without this a crash-looping node fills its disk with quarantined
  copies and then fails NFR-3's free-space check — turning a recoverable fault
  into an unrecoverable one.

### 12.4 Placement and routing

- MN-14: `/world join` resolves through a placement service, never a
  hardcoded server name. If the world holds a live lease, route to that node.
  If not, select a node and acquire the lease before routing.
- MN-15: Node selection considers loaded world count against `nodes.max-worlds`
  (FR-26), online players, and reported heap and TPS from the node heartbeat.
  A node above a configured threshold on any of these is excluded from new
  placements.
- MN-15a: Two further scoring terms, both preferences rather than hard
  constraints:
  - **Warm copy.** A node that already holds a local copy matching the
    world's current manifest turns a cold load into a warm one (NFR-1), which
    is the single largest lever on join latency.
  - **Public and private separation.** Prefer not to place a public world on
    a node that is holding private worlds. A public world admits players
    nobody vetted onto a node where the isolation logic in 5.5 is the only
    thing standing between them and other people's private worlds; keeping
    them apart where capacity allows bounds the blast radius of any FR-18 to
    FR-24 defect. A preference, not a partition, so that a small pool still
    functions.
- MN-16: All members of a world always resolve to the same node, public worlds
  included.
- MN-17: Nodes register with Velocity dynamically
  (`ProxyServer#registerServer`) on startup and deregister on shutdown, and are
  started and stopped through the Pelican API. `velocity.toml` is not edited to
  add capacity.
- MN-18: Each node publishes a heartbeat row (node id, address, loaded worlds,
  players, heap, TPS, last seen). A node missing its heartbeat for
  `nodes.dead-after-seconds` is excluded from placement. Takeover eligibility
  is governed solely by lease expiry (MN-8), and `nodes.dead-after-seconds`
  (60) must stay strictly below `nodes.lease-seconds` (180). v0.2 had these the
  wrong way round — 90 against a 60 second lease — which opened a 30 second
  window in which a world could be taken from a node the system still
  considered alive and which was still ticking it.

### 12.5 Migration and rebalancing

- MN-19: Moving a loaded world between nodes is an offline operation: eject
  players to lobby, commit a final snapshot (MN-6a), unload, release the lease,
  acquire the lease on the target, load, return the players. The ejection
  happens before the commit so that the players' profiles are captured by it
  and come back with the world on the target node. This is several seconds of
  visible interruption and is never done silently under a player.
- MN-20: Only idle worlds are migrated automatically. Ordinary rebalancing is
  passive: worlds unload after their grace period and are placed fresh on the
  next join.
- MN-21: `/world admin migrate <id> <node>` performs MN-19 manually, with a
  warning and a countdown shown to any players inside.
- MN-22: Draining a node for maintenance unloads its worlds in place (players
  to lobby, each with a snapshot commit) rather than live-migrating them, then
  deregisters the node. A drained node's worlds are placed fresh on the next
  join, per MN-20.

### 12.6 Player experience

- MN-23: A Velocity server switch always shows a brief loading screen, and the
  1.20.5+ transfer packet does not remove it. Since players only ever change
  node at world-join time, present it as the world loading, which matches what
  they already expect.
- MN-24: Per-world player state is node-independent, since profiles live in
  PostgreSQL keyed by `(uuid, world_id, ...)` rather than in a backend's
  `playerdata` folder. Inventories follow a world to any node. Being
  node-independent is not the same as being crash-consistent with the world
  they belong to, however, and it does not come for free: the snapshot keying
  in FR-15b and the shared commit in MN-3a are what make a profile and the
  world it was recorded in come back from the same instant.
- MN-25: Cold-load time (download plus load) must stay within the NFR-1 budget
  from the player's point of view. If it cannot, show a progress message rather
  than leaving the player on a blank connecting screen.

### 12.7 Failure modes specific to multi-node

- Object storage unreachable on load: refuse the join with a clear message. Do
  not load a partial world.
- Object storage unreachable during an incremental sync: log, alert, retry with
  backoff, and keep playing. The world is still safe locally. Escalate to a
  forced unload if sync has failed for `storage.max-sync-failure-minutes`.
- Node crashes with a loaded world: up to one sync interval of progress is
  lost, for world data and profiles together (FR-15). Players see a disconnect
  and can rejoin, landing on whichever node takes the lease.
- Two nodes both believe they hold the lease: possible, and survivable. MN-8
  guarantees only that two nodes cannot *acquire* it, not that a stalled node
  learns promptly that it lost it. The design assumption is therefore that a
  fenced node may keep running for some time: MN-2 and MN-3 make its writes
  harmless, MN-3a makes its commit fail, and MN-10a makes it stop as soon as
  it notices. Test this directly rather than assuming it — see milestone 7.
- Manifest and objects disagree: cannot arise for data objects, since the
  object key is the content hash and the manifest is written after the objects
  it references. A missing object listed in the manifest still fails the load,
  which is the correct outcome and indicates object storage has lost data.
- A manifest exists that `manifest_key` does not point at: normal. It is
  either an older retained snapshot or a fenced node's abandoned commit.
  MN-2b collects it.
- MinIO on GZMNServer is a single point of failure for the whole feature. Its
  own backup and restore path must be tested before this goes live.

### 12.8 Configuration additions

- `node.id` (unique per node, also used as the Velocity server name)
- `node.address`, `node.heartbeat-seconds` (default 30)
- `nodes.lease-seconds` (default 180)
- `nodes.dead-after-seconds` (default 60; must be strictly less than
  `nodes.lease-seconds`, MN-18)
- `nodes.max-worlds` (default 5, the loaded-world cap, FR-26),
  `nodes.max-heap-percent`, `nodes.min-tps`
- `storage.s3.*` (endpoint, bucket, access key, secret key, region)
- `storage.local-scratch-path`
- `storage.local-cache-max-gb` (LRU eviction above this)
- `storage.quarantine-max-gb`, `storage.quarantine-retain-days` (default 7,
  MN-13a)
- `storage.sync-minutes` (default 5, OQ-11)
- `storage.max-sync-failure-minutes` (default 30)
- `storage.commit-timeout-seconds` (default 15, section 9)
- `storage.cold-load-budget-seconds` (default 60, NFR-1)
- `storage.manifest-retention-count` (default 3, aligned with
  `profiles.retain-snapshots`)
- `storage.exclude-globs` (default `session.lock`, `uid.dat`, MN-2a)
- `storage.parallel-transfers` (default 4)
