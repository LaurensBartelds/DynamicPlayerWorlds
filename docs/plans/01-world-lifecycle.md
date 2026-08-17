# Implementation Plan 01 — World Lifecycle

Status: implemented; three acceptance items need a live node (§6)
Covers: spec milestone 1 (§11.1) — create a world, materialise its nether and
end on first transit, borders, portal linking in both directions, idle unload,
and the `createWorld` stall measurement
Spec baseline: `docs/spec/v0.4.md`
Predecessor: `00-repo-foundation.md` (F0–F12, complete)

---

## 0. What this milestone is

The first gameplay behaviour in the repository. Milestone 0 built seams;
this fills the one they were shaped around — a world's life from
`/pworld create` to idle unload, on a single node.

Spec §11 puts portal linking first and says why: *"portal linking is the part
most likely to surprise you, so do it first"*. Everything else here exists to
give portals a world to link.

Requirements in scope:

| Area | Requirements |
| --- | --- |
| Creation | FR-1 (cap only), FR-2, FR-2a, FR-4, FR-5 (teleport half) |
| Dimensions | FR-2 lazy materialisation, FR-3 borders, FR-3a portals, FR-3b dragon state |
| Lifecycle | FR-25, FR-25a, FR-25b, FR-25c, FR-25d, FR-26 (per-node cap), FR-28 (unload half) |
| Version gate | MN-26 read side — a world whose `data_version` exceeds this node's is refused |

Deliberately **not** in scope, with the milestone that owns each:

- **Leases and fencing** (MN-8 to MN-13a) — milestone 7. FR-1a's "acquire the
  lease **before** any world folder is created" is therefore not implemented;
  see §5 finding 3.
- **Object storage, snapshots, manifests** (MN-2 to MN-6a) — milestone 6. FR-25's
  "preceded by a snapshot commit" and FR-28's commit half are absent.
- **Profiles** (FR-14 to FR-17) — milestone 4. FR-5's "fresh profile" half is
  therefore not implemented; see §5 finding 2.
- **Membership, invites, visibility isolation, public worlds, the proxy** —
  milestones 2, 3, 5 and 9.

---

## 1. Two decisions taken before writing code

### D7 — Milestone 1 is database-backed, not the spec's in-memory staging

Spec §11.1 says "hardcoded owner, no database". That staging was written before
the foundation existed; F3 has already delivered `player_world`, `Database`,
`Repository` and a Testcontainers harness against it. Writing an in-memory world
registry now would be a throwaway implementation of a table that is already
there and already tested.

So `/pworld create` writes a real `player_world` row and load resolves from it.
The owner is the command sender rather than a hardcoded UUID — one less piece of
scaffolding to remove in milestone 2.

The cost is that lifecycle debugging now needs PostgreSQL running. Accepted: the
e2e harness (F11) already boots one, and the alternative was two implementations
of the same lookup.

### D8 — The backend command root is `/pworld`, not `/world`

Spec §6 registers `/world` on the **proxy**, and OQ-15 records that a Velocity
plugin claiming `/world` takes the whole namespace. Milestone 1 has no proxy, so
it needs its own root.

`/pworld` is a developer and operator surface — permission `gzmn.worlds.dev`,
not granted by default — and it does not collide with the proxy's claim when
milestone 5 lands. The two backend-side survivors in §6 (`/world leave` and
`/world report`) stay unimplemented until OQ-15 is answered, rather than being
pre-empted here by a registration that would have to be torn back.

---

## 2. Where the code goes

```
core/
  model/PlayerWorld.java        # the player_world row, as a record
  model/WorldState.java         # CREATING | READY | ARCHIVING | ARCHIVED | RESTORING
  model/Visibility.java         # PRIVATE | PUBLIC
  db/PlayerWorldRepository.java # the statements, hand-shaped (plan 00 §3)

backend/
  platform/WorldLifecycle.java      # createOrLoad / unload / pregen / resolve — the seam
  platform/PaperWorldLifecycle.java # WorldCreator, Bukkit.unloadWorld, getChunkAtAsync
  world/WorldFolders.java           # folder <-> WorldId, base folder from a Bukkit name
  world/LoadedWorld.java            # per-world node state; holds no World reference
  world/WorldRegistry.java          # WorldId -> LoadedWorld, and the idle counters
  world/WorldLifecycleService.java  # create, load, unload orchestration
  world/IdleUnloadTask.java         # FR-25 grace period and FR-25a retry
  world/PortalListener.java         # FR-3a, and lazy materialisation on transit
  config/BackendConfig.java         # config.yml -> NodeConfig
  command/PworldCommand.java        # D8
```

The split follows plan 00 §5.2. `backend.world` orchestrates and may use stable
Bukkit API (`Bukkit.getWorld`, `Player#teleportAsync`, events); anything
version-sensitive — creating a world, unloading one, async chunk loading —
goes behind `WorldLifecycle` in `backend.platform`, next to `WorldRuntime`.

`WorldLifecycle` is a **new interface on the seam**, not a method on
`WorldRuntime`: `WorldRuntime` is documented as operations on an already-loaded
world, and creation is exactly the surface that has to move when Paper changes
`WorldCreator` or renames the async chunk API.

---

## 3. The shape of each path

### 3.1 Create (FR-1, FR-2, FR-2a, FR-4, FR-5)

Off the main thread except where marked.

1. **db** — count worlds owned by the sender; refuse above
   `worlds.max-per-player` (FR-1). Refuse a duplicate `(owner_uuid, name)`
   before insert so the player gets a message rather than a constraint
   violation.
2. **db** — check `nodes.max-worlds` against the registry size (FR-26).
3. **db** — insert `player_world` with `state = 'CREATING'`, a random seed
   unless supplied, `folder` derived from the id (FR-2a), and
   `border_radius` from policy.
4. **main** — `createWorld` for the **overworld only** (FR-4). Timed; see §3.5.
5. **main** — apply border (FR-3), spawn-chunk disable (FR-25c) and the FR-9e
   gamerule defaults.
6. **off-main** — pre-generate the spawn square through the asynchronous chunk
   API, bounded to `worlds.pregen-spawn-chunks` (FR-4).
7. **db** — `state = 'READY'`.
8. **main** — teleport the player to the world spawn (FR-5, teleport half).

A failure between 3 and 7 deletes the row it inserted, so a failed create does
not consume the player's cap. A failure of the *process* between 3 and 7 leaves
a `CREATING` row, which is FR-40's sweep to reclaim — noted, not implemented.

### 3.2 Load (FR-3, FR-25b, FR-25c, MN-26)

Loading resolves the row, refuses when `data_version` exceeds this node's
(MN-26 read side, logged as `version.refused`), then materialises **whichever
dimensions already exist on disk** — never all three. A world created but never
entered through a portal has one folder, and loading it must not silently
generate the other two, because that would defeat FR-4 in the other direction.

Border, spawn-chunk radius and gamerules are re-asserted on every load, because
all three are persisted in `level.dat` and FR-3 / FR-25c / FR-9e all say the
database value wins over whatever the folder carries.

### 3.3 Portals (FR-3a) and lazy materialisation (FR-2)

`PlayerPortalEvent` and `EntityPortalEvent`, both on the main thread:

1. Identify the source world as a player world through `WorldFolders`; ignore
   everything else so the lobby and any other world on the node behave normally.
2. Map the event cause to `PortalRouting.PortalType` and resolve the target
   through the seam. The nether 8:1 scaling and the end coordinates come from
   `DefaultPortalRouting`, which F5 already unit-tested.
3. If the target dimension is not yet materialised, create it **now**, on this
   thread, with the world's stored seed (FR-2). This is the accepted stall FR-4
   budgets for, and the reason `LoadedWorld` caches the seed: the portal handler
   runs on the main thread and cannot read the database (NFR-2).
4. `setTo` the resolved location, with portal creation and search radius set so
   the return portal lands inside the same world.

The seed is cached at load, not read at transit, which is the whole reason
`LoadedWorld` exists.

### 3.4 Idle unload (FR-25, FR-25a, FR-25d)

One repeating main-thread task, default every 20 seconds:

- A world with zero players across all three dimensions accumulates idle time;
  any join to any dimension resets it (FR-25).
- Past `worlds.idle-unload-minutes`, unload **end, then nether, then overworld**
  (FR-25a), each through `Bukkit.unloadWorld(world, true)` with its boolean
  return checked.
- A `false` return is logged with the holding cause where determinable —
  players present, force-loaded chunks, plugin chunk tickets, all readable
  through the API — the remaining unloads for that world are abandoned, and the
  **whole world** is retried after `worlds.unload-retry-minutes`, including
  dimensions that came down last time and have since been reloaded.
- Ticks are counted rather than clocks read. The grace period is node-local
  policy, not a lease decision, but counting ticks keeps `Instant.now()` out of
  the lifecycle code entirely and makes the task deterministic in tests.

FR-25d's consequence — an unloaded world is frozen — is documented player-facing
behaviour and needs no code.

### 3.5 The `createWorld` stall (FR-4)

Every call into `WorldLifecycle#createOrLoad` is timed with `System.nanoTime`
around the main-thread section only, recorded to `create_stall_ms`
(`WorldsMetrics#createStall`, which F8 already registered), and logged at WARN
above `worlds.create-stall-budget-ms`.

The measurement is continuous rather than a one-off benchmark, per plan 00
§10.2, so the release-gating number in FR-4 comes from production rather than
from one run on a quiet server. **The number itself requires a live Paper node**
— see §6.

### 3.6 Shutdown (FR-28, unload half)

`onDisable` unloads every registered world in FR-25a order before the executors
drain, so a planned restart leaves no world half-saved. The snapshot-commit and
lease-release halves of FR-28 belong to milestones 6 and 7.

---

## 4. Configuration

Milestone 1 is the first code that needs `config.yml` to exist, so enable gains
the load-and-validate path F4 built and left unwired:

```
node.id / node.address / node.heartbeat-seconds
database.*                        (url, user, password, pool-size, timeout)
storage.local-scratch-path        (blank = the server's world container; see §5.1)
storage.local-cache-path / storage.quarantine-path / storage.min-free-space-bytes
storage.s3.*                      (present but unused until milestone 6)
metrics.bind / metrics.port
```

Network policy still comes from `network_setting` through `NetworkPolicy`
(ADR 0007), read once at enable and cached. `ConfigValidator.validate` runs
before anything opens, and an invalid config refuses the enable rather than
running with a default that violates a safety property (plan 00 §8.2).

---

## 5. Findings against the specification

Each of these is a place the spec does not cover what the code has to do. Per
`CLAUDE.md` they are reported rather than filled in silently.

### 5.1 `storage.local-scratch-path` and Bukkit's world container are the same directory

Spec §12.8 describes `storage.local-scratch-path` as a directory the plugin owns
and materialises world folders into. Bukkit offers no API to create a world
anywhere other than `Bukkit.getWorldContainer()` — `WorldCreator` takes a name,
never a path. The two are therefore necessarily the same directory, and the spec
never says so.

Resolution taken: `storage.local-scratch-path` defaults to the server's world
container, and a configured value that resolves elsewhere refuses the enable
with a message that says why. This matters beyond milestone 1 — MN-13's
quarantine and MN-5a's snapshot directory are both specified relative to the
scratch path, and both must stay on the same filesystem as the container for
reflink copies to work at all (plan 00 §9.1).

### 5.2 FR-5's "fresh profile" cannot be honoured before milestone 4

FR-5 requires the player arrive "with a fresh profile (empty inventory, full
health, 0 XP)". Profiles are FR-14 to FR-17, milestone 4. Clearing a player's
inventory on entry *before* there is a store to save their lobby inventory into
is unrecoverable item loss, so milestone 1 implements the teleport and leaves
the inventory alone. Milestone 4 completes FR-5.

### 5.3 FR-1a's ordering is not implementable until milestone 7

FR-1a requires the lease be acquired **before** any world folder is created,
because "a world that is created without a lease has no owner in the storage
sense and cannot be safely uploaded". Leases are milestone 7 and object storage
is milestone 6, so neither half of that hazard exists yet. Milestone 1 leaves
`assigned_node`, `lease_expires` and `generation` untouched at their defaults
rather than writing a half-lease that reads like a real one; milestone 7 fills
them through MN-8's conditional `UPDATE`.

### 5.4 The end spawn platform on a per-world end needs live verification

FR-3a requires "the end portal and return portal must target the correct
per-world end". Routing to the right world is settled — `DefaultPortalRouting`
handles it and is unit-tested. What cannot be settled off a server is whether
Paper still generates the obsidian arrival platform when the destination world
is supplied by a plugin rather than resolved by the server's own portal search.
If it does not, a player entering the end falls into the void.

Milestone 1 targets the vanilla end spawn point and logs the arrival; **this is
the single item in this milestone that must be checked on a real node before it
is believed.** Carried in the spec as OQ-17.

### 5.5 FR-1's cap and archived worlds

FR-1 caps the worlds a player **owns**, and FR-27 / FR-37 keep an archived world
as a row rather than deleting it. Whether an ARCHIVED world still counts is never
stated, and the two readings differ sharply: if it counts, `/world delete` — which
*is* the archival flow — never frees a slot, and a player who hits the cap can
never make room. Implemented as "ARCHIVED does not count", which is the only
reading under which delete does its job. Carried as OQ-18.

### 5.6 The scratch path is the server's world container

Recorded above in §5.1 and carried in the spec as OQ-19, because it outlives this
milestone: MN-13's quarantine and MN-5a's snapshot directory are both specified
relative to a scratch path the plugin does not actually get to choose.

---

## 5a. Found while building

Two of these are defects in foundation code that milestone 1 was the first caller
to exercise. Both were caught by gates F0–F12 put in place, which is the gates
working rather than luck.

### 5a.1 A small `database.pool-size` deadlocked startup

`Schema.migrate` holds one pooled connection for the FR-40 advisory lock across
the whole migration, and Flyway independently takes two more — one for its
schema-history table, one to run migrations on. A pool of three or fewer
therefore hands out every connection and then waits, for the full
`database.connection-timeout-seconds`, for one that cannot arrive. The node then
refuses to enable with a message about connection timeouts that says nothing
about the real cause.

The default of 8 hid this; a pool of 2 in the first bootstrap test found it
immediately. `DatabaseSettings` now refuses anything below
`MIN_POOL_SIZE` (4) with a message naming the reason.

### 5a.2 The capability probe ran on the main thread

Plan 00 §10.4's probe does a database round trip, a free-space stat and a reflink
trial copy. Enable called it inline, which is the tick thread. `MainThread`
caught it — `Database` asserts on every entry point — and turned what would have
been an invisible startup stall into a refused enable with a named cause. The
probe now runs on the io pool under a 30-second budget.

Worth stating plainly: NFR-2's guard earned its place here on the first real
caller, in the exact way plan 00 §9 argued it would.

### 5a.3 `SQLException` crosses the module boundary, and nothing else does

The backend ArchUnit rule banned all of `java.sql`. The first orchestration code
tripped it in two different ways, only one of which was a real violation:

- **Real** — the service composed transactions by passing lambdas that took a
  `java.sql.Connection`, so backend code was holding a JDBC handle.
  `PlayerWorldRepository` now offers transaction-owning overloads
  (`create`, `markReadyAndPlayed`, `touchLastPlayed`, `deleteIfCreating`), the
  `Connection`-taking versions stay for MN-3a's composition inside `:core`, and
  the service no longer takes a `Database` at all.
- **Not real** — catching `SQLException`, which `:core`'s repositories declare
  and every caller must therefore name. The rule now bans everything in
  `java.sql` and `javax.sql` *except* `SQLException`, and still bans Hikari
  outright.

### 5a.4 A MockBukkit test can vanish into a skip

`ServerMock.getWorldContainer()` throws `UnimplementedOperationException`, and
MockBukkit reports that as a **skipped** test rather than a failed one. The
plugin smoke test silently stopped testing anything the moment enable began
asking for the world container. `worldContainer()` is now a `protected` hook the
test overrides, alongside the `detectIdentity()` hook that already existed for
the same reason.

The general caution matters more than this instance: a MockBukkit assertion that
reaches an unimplemented surface does not go red, it goes quiet. Anything load-
bearing needs a real node or a `:core` test.

---

## 6. What can and cannot be verified without a live node

| Layer | Verifiable here |
| --- | --- |
| `:core` model and repository | Yes — Testcontainers PostgreSQL |
| Portal routing maths, folder naming, idle policy, unload ordering | Yes — plain unit tests |
| Plugin enable, command registration | Yes — MockBukkit |
| Real `createWorld` stall (FR-4's number) | **No** — needs Paper on target hardware |
| End arrival platform (§5.4) | **No** — needs Paper |
| Dragon fight surviving unload/reload (FR-3b) | **No** — MockBukkit has no `DragonBattle` |

The three "no" rows are the milestone's acceptance criteria and are the reason
spec §11.1 says to measure the stall *here*.

---

## 7. Work breakdown

| ID | Task | Done when | State |
| --- | --- | --- | --- |
| M1-1 | `core` model + `PlayerWorldRepository` | Testcontainers test covers insert, cap count, state transition, duplicate-name refusal | done — 13 tests |
| M1-2 | `config.yml` + `BackendConfig` + enable wiring | An invalid config refuses the enable; a valid one opens the pool, migrates and validates | done — 3 MockBukkit + Testcontainers tests |
| M1-3 | `WorldLifecycle` seam + Paper implementation | ArchUnit still confines version-sensitive surfaces; stall timing lands on `create_stall_ms` | done |
| M1-4 | `WorldFolders`, `LoadedWorld`, `WorldRegistry` | Unit tests; no field anywhere holds a `World` (existing ArchUnit rule) | done — 11 tests |
| M1-5 | `WorldLifecycleService` — create, load, unload | Create is transactional against the row; unload follows FR-25a order | done; end-to-end behaviour needs a node |
| M1-6 | `IdleUnloadTask` | Unit test over the policy: grace period, reset on join, whole-world retry | done — 10 tests |
| M1-7 | `PortalListener` + lazy materialisation | Unit tests over cause mapping and target resolution | done; transit needs a node |
| M1-8 | `/pworld` command | Descriptor declares it, gated, and leaves `/world` to the proxy | done |
| M1-9 | Docs — findings above, `NEXT-STEPS`, `CHANGELOG` | This file's §5 reflected in the spec's open questions | done — OQ-17, OQ-18, OQ-19 |

---

## 8. Open questions this milestone raises

All three are carried in spec v0.4 §10 so they survive this plan being archived.

- **OQ-17** — Does Paper generate the end arrival platform for a
  plugin-supplied destination world? §5.4. Blocking for milestone 1's
  acceptance, not for its code.
- **OQ-18** — Does an ARCHIVED world count against FR-1's per-player cap? §5.5.
  Implemented as "no"; confirm.
- **OQ-19** — `storage.local-scratch-path` is necessarily the server's world
  container. §5.1 and §5.6.

Not raised as a spec question, but noted for milestone 8: FR-26 caps loaded
worlds per node and MN-15 enforces it at placement time. Until a placement
service exists the backend enforces it locally at create and load, and that
local check is worth keeping afterwards as a backstop rather than removing.
