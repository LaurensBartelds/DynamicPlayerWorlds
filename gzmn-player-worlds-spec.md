# GZMN Player Worlds - Technical Specification

Version 0.2 (draft). Multi-node with object storage is in scope.

## 1. Goal

Allow a player on GZMN to create a private survival world, invite specific
other players to it, and play there in isolation from the rest of the network.
Players who are not members of a world must not be able to see, contact, or
detect the players inside it.

## 2. Scope and assumptions

- Target: Paper (latest stable), Java 21, Velocity proxy, Pelican Panel.
- Expected load: fewer than 5 player worlds loaded at the same time, spread
  across two or more interchangeable `worlds` nodes.
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
`node.id`. Each node hosts only player worlds plus a small holding area used
while a world loads. Owns world lifecycle, lease acquisition and heartbeat,
object storage sync, membership enforcement, per-world profiles, and
visibility isolation.

### 3.2 `gzmn-worlds-proxy` (Velocity plugin)

Runs on the proxy. Owns network-level commands, membership lookups from any
server, the placement service (which node a world should live on), dynamic
node registration, and the handoff into the chosen node. Communicates with
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
  owner_uuid    UUID NOT NULL,
  name          TEXT NOT NULL,
  folder        TEXT NOT NULL UNIQUE,
  seed          BIGINT NOT NULL,
  border_radius INT NOT NULL DEFAULT 5000,
  visibility    TEXT NOT NULL DEFAULT 'PRIVATE',  -- PRIVATE, PUBLIC
  description   TEXT,                   -- shown in the browse list
  assigned_node TEXT,                   -- node currently holding the world, NULL if unloaded
  lease_expires TIMESTAMPTZ,            -- heartbeat extends this while loaded
  generation    BIGINT NOT NULL DEFAULT 0,  -- bumped on every lease acquisition, used for fencing
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_played   TIMESTAMPTZ,
  state         TEXT NOT NULL,          -- CREATING, READY, LOADED, ARCHIVED
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
  world_id     UUID PRIMARY KEY REFERENCES player_world(id) ON DELETE CASCADE,
  object_key   TEXT NOT NULL,           -- path or object storage key, not the blob
  size_bytes   BIGINT NOT NULL,
  checksum     TEXT NOT NULL,           -- sha256 of the archive
  archived_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  restore_count INT NOT NULL DEFAULT 0
);

CREATE TABLE player_world_member (
  world_id   UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  uuid       UUID NOT NULL,
  role       TEXT NOT NULL,             -- OWNER, BUILDER, VISITOR
  invited_by UUID,
  joined_at  TIMESTAMPTZ,
  PRIMARY KEY (world_id, uuid)
);

CREATE TABLE player_world_invite (
  world_id   UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  uuid       UUID NOT NULL,
  invited_by UUID NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (world_id, uuid)
);

CREATE TABLE player_world_profile (
  world_id   UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  uuid       UUID NOT NULL,
  data       JSONB NOT NULL,            -- inventory, ender chest, xp, health, food, location
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (world_id, uuid)
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
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`pending_transfer` is written by the proxy plugin immediately before it sends
a player to a `worlds` node, and consumed by that node on join. Rows older
than 60 seconds are treated as expired. It carries the target `node_id` so a
player who lands on the wrong node (a stale Velocity route, a node restart
mid-handoff) is bounced to lobby rather than triggering a second load of a
world that is leased elsewhere.

## 5. Functional requirements

### 5.1 World creation

- FR-1: A player may create a world with `/world create <name>`, subject to a
  configurable per-player cap (default 1) and a global loaded-world cap
  (default 5).
- FR-2: Creation generates a random seed unless the player supplies one, and
  creates all three dimensions immediately: overworld, nether and end. They
  share the same seed and are stored as `<folder>`, `<folder>_nether` and
  `<folder>_the_end`.
- FR-3: A world border of `border_radius` is applied to the overworld and the
  end at creation, and `border_radius / 8` to the nether so the two line up in
  world coordinates. Borders are enforced server-side and fixed after creation
  in v1.
- FR-3a: Portal linking between a world's own dimensions must be handled
  explicitly in `PlayerPortalEvent` and `EntityPortalEvent`. Bukkit's default
  portal search resolves against the server's primary world, so without this a
  player entering a nether portal in their own world lands in the wrong
  dimension or in another player's world. Coordinate scaling (8:1 overworld to
  nether) must be applied manually, and the end portal and return portal must
  target the correct per-world end.
- FR-3b: Ender dragon state (`hasBeenKilled`, gateway generation, respawn) is
  per world and must survive an unload and reload cycle.
- FR-4: World generation must not block the main thread for more than one
  tick beyond what `createWorld` itself requires. Show the player a progress
  title while the world initialises, and pre-generate a small spawn area only.
- FR-5: On success the player is teleported to the world spawn with a fresh
  profile (empty inventory, full health, 0 XP).

### 5.2 Membership and invites

- FR-6: The owner may invite with `/world invite <player>`. This writes a
  `player_world_invite` row with a default 10 minute expiry and notifies the
  target if they are online anywhere on the network.
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
- FR-11: On `PlayerJoinEvent` on the backend, the plugin reads
  `pending_transfer`, loads the world if not already loaded, restores the
  player's profile for that world, and teleports them. If the transfer row is
  missing or expired, send the player back to lobby with an explanatory
  message.
- FR-12: `/world leave` returns the player to lobby and saves their profile.
- FR-13: Disconnecting inside a player world must save the profile and record
  the world for reconnect, so a rejoin from lobby offers a resume prompt.

### 5.4 Inventory and state separation

- FR-14: A player's inventory, armour, offhand, ender chest, XP level and
  progress, health, hunger, saturation, potion effects and last location are
  scoped per `(uuid, world_id)`.
- FR-15: Profiles are written on world exit, on quit, and on a periodic
  autosave (default every 5 minutes for players in a loaded world).
- FR-16: Writes must be atomic per player. A failed write must not leave a
  partially serialised profile, and the plugin must refuse to load a profile
  it cannot deserialise, sending the player to lobby with an error rather
  than granting an empty inventory.
- FR-17: Serialisation format must be version-tagged so future schema changes
  can migrate rather than discard.

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
- FR-19: Join, quit and death messages are suppressed globally and re-emitted
  only to the player's own group.
- FR-20: Chat recipients are scoped to the group by mutating the viewer set on
  `AsyncChatEvent` rather than cancelling and rebroadcasting.
- FR-21: Name completion is filtered via `AsyncTabCompleteEvent`, and commands
  the player may not use are hidden via `PlayerCommandSendEvent`.
- FR-22: Presence-revealing commands from other plugins are disabled by
  permission (EssentialsX `/list`, `/seen`, `/near`, `/msg`, `/tpa`, `/tphere`)
  and replaced with group-scoped equivalents provided by this plugin.
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
  dimensions unload together, with save. Any join into any dimension of that
  world resets the timer and cancels the pending unload.
- FR-25a: Unload order is end, then nether, then overworld. Each call checks
  the boolean return of `Bukkit.unloadWorld(world, true)`. A false return means
  something still holds the world (a lingering player, a force-loaded chunk, a
  plugin ticket). Log it with the holding cause where determinable, abort the
  remaining unloads for that world, and retry on the next idle cycle.
- FR-25b: The plugin must never cache `World` references across an unload.
  Worlds are resolved by name or UUID through `Bukkit.getWorld` at use time.
- FR-25c: Loaded worlds run with spawn chunks disabled (`spawnChunkRadius` 0)
  so an idle world costs no meaningful tick time during the grace period.
- FR-25d: Accepted consequence: an unloaded world is frozen. Furnaces, crop
  growth, mob spawning and time of day do not advance while the owner is
  offline. This is documented player-facing behaviour, not a bug.
- FR-26: If the global loaded-world cap is reached, a new join request is
  refused with a clear message rather than evicting an active world. The cap
  counts worlds, not dimensions, so 5 loaded worlds means 15 Bukkit `World`
  instances.
- FR-27: `/world delete` requires typed confirmation, archives the folder to a
  configured path, and marks the row ARCHIVED. Hard deletion is a manual admin
  action, never automatic.
- FR-28: On server shutdown all loaded worlds save and all profiles flush
  before the plugin disables.

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
- FR-32: If the target is offline, the transfer is offered as a pending request
  they accept on next login, expiring after 7 days.
- FR-33: Admins with `gzmn.worlds.admin` may force a transfer with reason
  ADMIN, for the case where an owner has left GZMN and the remaining members
  want to keep the world.

### 5.8 Archival and storage

- FR-34: A world with no logins for `archive.after-days` (default 90) is
  archived automatically. The owner is warned in game and, if the Discord
  bridge supports it, by DM at 14 days and 3 days before.
- FR-35: Archiving unloads the world, packs all three dimension folders into a
  single compressed archive (zstd), writes the object key, size and sha256 to
  `player_world_archive`, sets `state` to ARCHIVED, and removes the live
  folders only after the checksum of the written archive verifies.
- FR-36: `/world restore <name>` by the owner, or by an admin, unpacks the
  archive back to the live path, verifies the checksum, and sets state to
  READY. Restore is subject to the same free-space check as creation.
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

## 6. Commands and permissions

| Command | Where | Permission | Notes |
| --- | --- | --- | --- |
| `/world create <name> [seed]` | proxy | `gzmn.worlds.create` | Subject to per-player cap |
| `/world join <owner> [name]` | proxy | `gzmn.worlds.join` | Membership checked proxy-side |
| `/world invite <player>` | backend | owner role | Target may be on any server |
| `/world accept <owner>` | proxy | `gzmn.worlds.join` | Consumes invite |
| `/world kick <player>` | backend | owner role | |
| `/world members` | backend | member | |
| `/world leave` | backend | member | Returns to lobby |
| `/world delete <name>` | backend | owner role | Confirmation required |
| `/world browse` | proxy | `gzmn.worlds.join` | PUBLIC worlds only |
| `/world public on\|off [desc]` | backend | owner role | Toggles visibility |
| `/world promote <player>` | backend | owner role | VISITOR to BUILDER |
| `/world ban <player> [reason]` | backend | owner role | Per world, ejects if online |
| `/world unban <player>` | backend | owner role | |
| `/world set <setting> <value>` | backend | owner role | PVP, containers, mob griefing |
| `/world transfer <player>` | backend | owner role | Confirmation, target must be a member |
| `/world restore <name>` | proxy | owner role | Unpacks an archived world |
| `/world admin list` | either | `gzmn.worlds.admin` | |
| `/world admin unload <id>` | backend | `gzmn.worlds.admin` | |
| `/world admin transfer <id> <player>` | either | `gzmn.worlds.admin` | Reason ADMIN |

## 7. Configuration

Backend `config.yml`:

- `worlds.max-per-player` (default 1)
- `worlds.max-loaded` (default 5)
- `worlds.idle-unload-minutes` (default 10, grace period after the last player
  leaves all three dimensions)
- `worlds.unload-retry-minutes` (default 2)
- `worlds.default-border-radius` (default 5000)
- `worlds.dimensions` (list, default `[overworld, nether, end]`)
- `worlds.nether-border-divisor` (default 8)
- `worlds.storage-path` (live Anvil folders)
- `worlds.default-visibility` (default `PRIVATE`)
- `worlds.public.browse-page-size` (default 10)
- `archive.backend` (`filesystem` or `s3`)
- `archive.path` or `archive.s3.*` (endpoint, bucket, credentials)
- `archive.after-days` (default 90)
- `archive.warn-days` (default `[14, 3]`)
- `archive.compression` (default `zstd-9`)
- `profiles.autosave-minutes` (default 5)
- `invites.expiry-minutes` (default 10)
- `transfers.pending-expiry-days` (default 7)
- `database.*` (host, port, database, user, password, pool size)

Proxy `config.toml`: database credentials, `worlds` server name, lobby server
name, transfer expiry seconds.

## 8. Non-functional requirements

- NFR-1: Loading an existing world must complete within 5 seconds on the
  target hardware, and must not drop the backend below 18 TPS while doing so.
- NFR-2: All database access is off the main thread. No blocking JDBC call in
  an event handler.
- NFR-3: Disk usage per world is bounded by the borders across all three
  dimensions. Budget for roughly three world folders per player world, with the
  end typically small and the nether growing faster per block explored than the
  overworld. Monitor total usage and refuse creation below a configurable
  free-space threshold.
- NFR-3a: 15 loaded Bukkit worlds carry a fixed per-world memory and tick cost
  even when empty. Verify heap headroom on the `worlds` instance before raising
  `worlds.max-loaded`, and consider unloading a world's nether and end
  separately if profiling shows idle dimensions are expensive.
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
- Database unreachable: the backend refuses new joins and creations, keeps
  already-loaded worlds playable, and queues profile writes in memory with a
  disk-backed fallback.
- Player is transferred but the world fails to load: send to lobby, log, do
  not spawn them in the holding area indefinitely.
- Two proxies or a restart leaving a stale `pending_transfer`: rows expire.
- Player kicked mid-save: profile write completes before the session is
  released.

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

Still open:

- OQ-6: Does the network MOTD count player-world players (FR-24d)?
- OQ-7: Does making a world public require a permission or rank, or can any
  player do it? A public world invites strangers onto a backend where your
  isolation logic is the only thing protecting other worlds.
- OQ-8: Do public worlds need a report or moderation path, given players can
  now be exposed to people they did not invite?
- OQ-9: Is `worlds.max-per-player` still 1 now that transfers exist? A player
  at the cap cannot receive a transfer.
- OQ-10: How many `worlds` nodes at launch, and are they separate Pelican
  instances on GZMNServer or on separate hardware? Two instances on one host
  gives you the code path but none of the resilience.
- OQ-11: Is 10 minutes an acceptable data-loss window on a node crash
  (`storage.sync-minutes`), or should it be tighter at the cost of more
  upload churn?
- OQ-12: Does MinIO run on the same host as the nodes? If so, a host failure
  takes both the working copy and the source of truth, which undermines the
  point of the split.

## 11. Suggested milestones

1. Backend plugin: create all three dimensions, load, unload, borders,
   teleport, and correct portal linking in both directions. Hardcoded owner, no
   database. Proves the lifecycle, and portal linking is the part most likely
   to surprise you, so do it first.
2. PostgreSQL schema plus membership and invites. Still single-server.
3. Visibility isolation (FR-18 to FR-24). Test with three accounts across two
   worlds and the lobby.
4. Per-world profiles with a deliberate crash test to verify no dupe or loss.
5. Proxy plugin plus `pending_transfer` handoff, one `worlds` node registered
   in Pelican. Close the proxy-level leaks (FR-24a to FR-24d) in the same
   milestone, since that is when they first become reachable.
6. Object storage: manifests, upload on unload, download on load, checksum
   verification, local cache. Still one node. Prove a world survives a full
   wipe of local scratch.
7. Leases and fencing (MN-8 to MN-13), still on one node. Write the fencing
   tests here, including the SIGSTOP takeover test described below.
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
- MN-2: Live worlds are stored as individual objects per file under a
  per-world prefix (`worlds/<world_id>/<dimension>/region/r.0.0.mca` and so
  on), not as one archive. This is what makes incremental sync possible. Cold
  archives (section 5.8) remain single compressed tarballs, since they are only
  ever restored whole.
- MN-3: Each world has a manifest object recording, per file, its size,
  mtime, sha256, and the `generation` of the lease that wrote it. The manifest
  is written last and atomically; a world's state is defined by its manifest,
  never by a directory listing.
- MN-4: On load, the node downloads the world into
  `<local-scratch>/<world_id>/`, verifying against the manifest. Files already
  present locally with a matching checksum are not re-downloaded, so a node
  that recently held a world warm-starts.
- MN-5: On unload, the node runs `World#save`, uploads every file whose
  checksum differs from the manifest, writes the new manifest, then releases
  the lease. Local files are retained as a warm cache, subject to a size cap
  with least-recently-used eviction.
- MN-6: While a world is loaded, an incremental sync runs every
  `storage.sync-minutes` (default 10): save the world, upload changed files,
  write a new manifest. This bounds data loss on a node crash to one sync
  interval. Accept and document that bound.
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
  Suggested lease 60 seconds, heartbeat 20 seconds.
- MN-10: Before any upload, incremental sync, or manifest write, the node
  re-checks that it still holds the lease at the same generation. If it does
  not, it aborts the write, moves its local copy to a quarantine path, alerts,
  and drops its players to lobby. This fencing check is the only thing
  preventing a stalled or partitioned node from overwriting a world another
  node has already taken over.
- MN-11: Uploads are staged and the manifest written last, so an upload
  interrupted at any point leaves the previous manifest valid and the world
  loadable.
- MN-12: On clean unload the lease is released (`assigned_node` set NULL). On
  crash it simply expires, and the next node to acquire it starts from the last
  manifest.
- MN-13: On node startup, any local scratch directory not covered by a lease
  this node currently holds is quarantined, not deleted and not uploaded. It is
  crash debris and may be newer or older than object storage.

### 12.4 Placement and routing

- MN-14: `/world join` resolves through a placement service, never a
  hardcoded server name. If the world holds a live lease, route to that node.
  If not, select a node and acquire the lease before routing.
- MN-15: Node selection considers loaded world count, online players, and
  reported heap and TPS from the node heartbeat. A node above a configured
  threshold on any of these is excluded from new placements.
- MN-16: All members of a world always resolve to the same node, public worlds
  included.
- MN-17: Nodes register with Velocity dynamically
  (`ProxyServer#registerServer`) on startup and deregister on shutdown, and are
  started and stopped through the Pelican API. `velocity.toml` is not edited to
  add capacity.
- MN-18: Each node publishes a heartbeat row (node id, address, loaded worlds,
  players, heap, TPS, last seen). A node missing its heartbeat for
  `nodes.dead-after-seconds` is excluded from placement and its worlds become
  eligible for lease takeover.

### 12.5 Migration and rebalancing

- MN-19: Moving a loaded world between nodes is an offline operation: eject
  players to lobby, unload with save and upload, release lease, acquire the
  lease on the target, load, return the players. This is several seconds of
  visible interruption and is never done silently under a player.
- MN-20: Only idle worlds are migrated automatically. Ordinary rebalancing is
  passive: worlds unload after their grace period and are placed fresh on the
  next join.
- MN-21: `/world admin migrate <id> <node>` performs MN-19 manually, with a
  warning and a countdown shown to any players inside.
- MN-22: Draining a node for maintenance unloads its worlds in place (players
  to lobby) rather than live-migrating them, then deregisters the node.

### 12.6 Player experience

- MN-23: A Velocity server switch always shows a brief loading screen, and the
  1.20.5+ transfer packet does not remove it. Since players only ever change
  node at world-join time, present it as the world loading, which matches what
  they already expect.
- MN-24: Per-world player state is already node-independent, since profiles
  live in PostgreSQL keyed by `(uuid, world_id)` rather than in a backend's
  `playerdata` folder. Inventories follow a world to any node with no extra
  work.
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
  lost. Players see a disconnect and can rejoin, landing on whichever node
  takes the lease.
- Two nodes both believe they hold the lease: impossible by MN-8 if
  acquisition is a single atomic statement. Test this directly rather than
  assuming it.
- Manifest and objects disagree: the manifest wins. Files not in the manifest
  are ignored, and a missing file listed in the manifest fails the load.
- MinIO on GZMNServer is a single point of failure for the whole feature. Its
  own backup and restore path must be tested before this goes live.

### 12.8 Configuration additions

- `node.id` (unique per node, also used as the Velocity server name)
- `node.address`, `node.heartbeat-seconds` (default 20)
- `nodes.lease-seconds` (default 60)
- `nodes.dead-after-seconds` (default 90)
- `nodes.max-worlds`, `nodes.max-heap-percent`, `nodes.min-tps`
- `storage.s3.*` (endpoint, bucket, access key, secret key, region)
- `storage.local-scratch-path`
- `storage.local-cache-max-gb` (LRU eviction above this)
- `storage.sync-minutes` (default 10)
- `storage.max-sync-failure-minutes` (default 30)
- `storage.parallel-transfers` (default 4)
