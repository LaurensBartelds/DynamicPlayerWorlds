# Review of `gzmn-player-worlds-spec.md` v0.2

Reviewed against the draft dated v0.2, all 676 lines. Findings are grouped by
severity. Every item cites the requirement it applies to so it can be
triaged individually.

Overall: this is a strong spec. It already identifies the hard constraint
(section 12.1), picks the right storage split, and is honest about accepted
consequences (FR-25d, MN-6, MN-23). The gaps below are concentrated in three
places — the durability boundary between PostgreSQL and object storage, the
fencing story, and the parts of the Minecraft world folder the sync design
does not mention.

---

## 1. Blocking issues

These lose or duplicate player data as specified. They should be resolved
before milestone 6.

### B-1. Profiles and world data have independent durability, which is a dupe vector

FR-15 writes profiles to PostgreSQL every 5 minutes (and on quit). MN-6 syncs
world data to object storage every 10 minutes. Nothing ties the two clocks
together, and they land in different storage systems with different failure
domains.

Concrete duplication:

1. `T+0` — world syncs. A chest contains 64 diamonds.
2. `T+9` — the player empties the chest into their inventory.
3. `T+10` — profile autosave writes the inventory, with the diamonds, to
   PostgreSQL.
4. `T+11` — the node crashes before the next world sync.
5. Recovery — the world rolls back to the `T+0` manifest, so the chest is full
   again. The profile does not roll back, so the player still has the diamonds.

The items now exist twice. The same mechanism runs in reverse (player deposits
into a chest, profile saves, crash, chest rolls back empty) and silently
destroys items instead. Every crash produces some of both.

FR-16's atomicity guarantee does not help — each write is individually atomic,
but the pair is not.

Milestone 4 calls for "a deliberate crash test to verify no dupe or loss", but
milestone 4 predates object storage. The test as scoped cannot catch this,
because on a single node with no sync interval both stores fail together.

Recommended resolution — pick one, and state it:

- **Tie profiles to the world snapshot.** Write profiles as part of the sync,
  keyed by `(uuid, world_id, generation)`, and on lease acquisition load the
  profile row matching the generation of the manifest actually restored. This
  makes the rollback consistent in both directions. It costs a profile history
  table and a retention policy, and it means a crash rolls a player's inventory
  back too — which is correct, and is what players already expect from a
  server rollback.
- **Or make world sync the only durability point** by flushing profiles at
  every sync and only at every sync, accepting that `profiles.autosave-minutes`
  is meaningless as an independent knob and removing it.
- **Or store profiles inside the world folder** (as an extra data file synced
  with everything else) and give up the "profiles are node-independent" claim
  in MN-24 — this is the simplest to reason about and the most expensive to
  query for the browse list.

The first option is the one that preserves the rest of the design. Whichever
is chosen, MN-24's claim that per-world state is "already node-independent…
with no extra work" needs to be softened: it is node-independent, but it is not
automatically *crash-consistent* with the world it belongs to.

### B-2. MN-10's fencing check is a check, not a fence

MN-10 requires the node to re-check the lease "before any upload, incremental
sync, or manifest write". A check and a write that are not a single atomic
operation do not fence anything: a node that is stalled (GC pause, disk stall,
SIGSTOP — the spec's own test case in milestone 7) can pass the check, lose the
lease while suspended, resume, and complete the write against object storage.
The window is exactly the stall the fencing exists to defend against.

MN-10 also states this check "is the only thing preventing a stalled or
partitioned node from overwriting a world another node has already taken over",
which is an accurate description of the problem and an argument for not relying
on it.

Recommended resolution — make stale writes physically impossible rather than
merely unlikely, by making object storage immutable:

- **Content-address the data objects.** Store each file's *bytes* at
  `worlds/<world_id>/data/<sha256>` instead of at a logical path. Objects are
  then write-once and a stale writer cannot clobber anything; its uploads are
  inert orphans.
- **Scope the manifest by generation.** Write it to
  `worlds/<world_id>/manifest/<generation>.json`, mapping logical path →
  sha256. Since `generation` is incremented by the atomic lease acquisition in
  MN-8, a node holding generation N physically cannot write the manifest for
  N+1. This needs only write-once semantics, not conditional-PUT support, so it
  works on any S3-compatible backend.
- **Let PostgreSQL be the only linearization point.** `player_world.generation`
  already is one, by MN-8. A loading node reads the generation it acquired and
  fetches exactly that manifest. No mutable pointer object is needed, and the
  "manifest and objects disagree" failure mode in 12.7 disappears by
  construction.

This also simplifies three other requirements: MN-11's staged-upload rule
becomes unnecessary (nothing is ever overwritten), MN-4's warm-cache dedup
becomes a local lookup by hash, and MN-13's quarantine rule applies to strictly
less data. The cost is a garbage collection pass — delete any `data/<sha256>`
not referenced by a retained manifest — which should be added as a background
job and to section 12.8 (`storage.manifest-retention-count`).

Keep the MN-10 check regardless. It is still the right way for a node to
discover it has been fenced so it can drop players and quarantine (which it
must still do); it just cannot be the mechanism that protects the data.

### B-3. Incremental sync uploads region files that the server is actively writing

MN-5 and MN-6 both say: save the world, then upload every file whose checksum
differs. The server keeps ticking during the upload. Anvil region files are
mutated in place — a chunk save rewrites the file's sector header and may
relocate sectors — so a file read concurrently with a chunk save yields a torn
region: header pointing at sectors that hold different chunk data. This is the
quiet corruption the spec correctly worries about in 12.1, arriving through a
different door.

It also breaks MN-3's manifest contract: if the file is hashed and then
uploaded, the bytes recorded and the bytes stored can differ, so the checksum
verification in MN-4 will fail the load — or worse, pass, if the hash is
computed from the same torn read.

Recommended resolution:

- Take a snapshot immediately after `World#save()` and hash and upload *from
  the snapshot*, never from the live file. Use `cp --reflink=auto` on XFS or
  btrfs so the copy is cheap.
- **Do not use hard links for this.** A hard link shares the inode, so an
  in-place region write is visible through the link — it is not a snapshot. This
  is worth stating explicitly in the spec, because it is the obvious first
  implementation and it silently does nothing.
- Track dirty regions rather than diffing the whole world. Hook chunk saves and
  record which region files were touched since the last sync; snapshot and hash
  only those. Without this, each sync rehashes multiple gigabytes per world
  (see C-2), which will not fit in a 10-minute interval alongside normal play.
- If none of that is acceptable, the honest alternative is that incremental
  sync only happens at unload, and MN-6's bound becomes "since the world was
  loaded" rather than one sync interval. That is a much worse guarantee, and it
  should be an explicit choice rather than an emergent one.

### B-4. The sync set is underspecified and, as written, loses all entities

MN-2's example object layout mentions only
`worlds/<world_id>/<dimension>/region/r.0.0.mca`. A modern Paper world folder
is more than `region/`:

| Path | Since | Contents lost if not synced |
| --- | --- | --- |
| `region/` | — | Blocks, tile entities |
| `entities/` | 1.17 | **All mobs, item frames, armour stands, dropped items, boats, minecarts** |
| `poi/` | 1.14 | Villager workstation bindings; villages break subtly |
| `data/` | — | Raids, maps, structure references |
| `level.dat` | — | Spawn point, time, weather, gamerules, **dragon fight state** |

Omitting `entities/` is not a degradation, it is the world coming back empty of
everything that moves. Omitting `level.dat` breaks FR-3b directly: per-world
ender dragon state (`hasBeenKilled`, gateways, respawn) lives in the end
dimension's `level.dat`, and FR-3b requires it to survive an unload/reload
cycle.

Also worth writing down, because it will bite during implementation: Bukkit does
not lay multi-world folders out the way vanilla does. For a world folder
`foo`, the nether's regions are at `foo_nether/DIM-1/region/` and the end's at
`foo_the_end/DIM1/region/`, each with its own `level.dat` at the folder root.
The sync path builder needs those `DIM-1`/`DIM1` segments.

Recommendation: replace MN-2's single example with the explicit list of synced
paths, and state the rule for anything not listed (ignored, or synced by
wildcard).

---

## 2. Contradictions within the spec

### C-1. `pending_transfer` is missing the column the prose requires

Section 4's prose says the table "carries the target `node_id` so a player who
lands on the wrong node … is bounced to lobby rather than triggering a second
load of a world that is leased elsewhere." The DDL directly above has only
`uuid`, `world_id`, `created_at`. Without `node_id` the described check cannot
be performed, and the failure it guards against — a second node loading a
leased world — is the corruption case from 12.1.

Add `node_id TEXT NOT NULL`. Consider adding the `generation` the proxy
resolved against too, so the receiving node can reject a handoff that was
routed against a lease that has since been taken over.

### C-2. NFR-1's 5-second load budget is contradicted by MN-25 and is not achievable cold

NFR-1: "Loading an existing world must complete within 5 seconds." MN-25: cold
load "must stay within the NFR-1 budget … If it cannot, show a progress message
rather than leaving the player on a blank connecting screen." The second
sentence concedes the first is not a requirement.

It is also not achievable at the stated world size. A `border_radius` of 5000
is a 10 000 × 10 000 block overworld — roughly 380 region files if fully
explored, and region files run to tens of megabytes each. A well-played world
is multiple gigabytes per dimension. That does not download in five seconds
over anything, and NFR-3's "bounded by the borders" gives no number to plan
against.

Recommendation: split the requirement. Warm load (working copy present, hashes
match) ≤ 5 s; cold load has a separate, larger budget and a mandatory progress
UI. Then state a per-world disk and transfer budget in NFR-3 as an actual
figure, since `worlds.max-loaded`, `storage.local-cache-max-gb` and
`storage.sync-minutes` all need to be sized from it.

### C-3. Lease and liveness timings are inconsistent

`nodes.lease-seconds` is 60 (MN-9, 12.8) and `nodes.dead-after-seconds` is 90
(12.8, MN-18). So there is a 30-second window in which a node's lease has
expired and is eligible for takeover by MN-8, while MN-18 still considers the
node alive. During that window a node that is merely slow can have its world
taken while it is still ticking it and accepting players.

Separately, a 60-second lease with a 20-second heartbeat tolerates two missed
heartbeats. A node running 15 Bukkit worlds (FR-26's cap) can plausibly stall
longer than that on a bad GC or a disk hiccup, and the penalty for exceeding it
is the takeover path.

Recommendation: make `dead-after-seconds` strictly less than `lease-seconds` so
a node is excluded from placement before its lease can be stolen, and raise the
lease (180 s / 30 s heartbeat is a more forgiving pairing at this scale). Also
state that a node must self-fence on *losing* the lease, not only before
writes: MN-10 covers the write path, but a fenced node must also stop ticking
the world and stop accepting joins into it.

### C-4. `state` and the lease are two sources of truth for "is this world loaded"

`player_world.state` includes `LOADED`, while `assigned_node` + `lease_expires`
+ `generation` already answer the same question authoritatively. After a node
crash, `state` stays `LOADED` forever while the lease expires — so placement,
`/world browse` (FR-9g requires showing loaded state) and the admin list will
disagree with reality.

Recommendation: drop `LOADED` from `state` and derive loadedness from a live
lease. Keep `state` for the lifecycle the lease cannot express, and add the
transitional values that are currently missing: `ARCHIVING` and `RESTORING`. As
written, a crash during FR-35 or FR-36 leaves a world in an unrecoverable
in-between with live folders half-removed and no state to describe it.

### C-5. The disk-backed profile fallback contradicts the disposable-disk model

Section 9: on database loss, "queue profile writes in memory with a
disk-backed fallback." MN-1 says local disk is a disposable working copy;
MN-13 says any local directory not covered by a held lease is quarantined on
startup, "not deleted and not uploaded", because it is crash debris.

So the fallback queue is written to storage the rest of the design promises to
discard, and there is no specified replay path. If the node is replaced (the
scenario NFR-9 tests), the queued profiles are gone with it.

Recommendation: state where the fallback lives, who replays it, and what the
operator does with a node that dies holding one. If the answer is "nothing,
they are lost", say that, and bound it by refusing new joins early rather than
accumulating a queue.

### C-6. Owner commands are backend-only, so an owner cannot manage an unloaded world

The section 6 table places `/world invite`, `/world kick`, `/world members`,
`/world delete`, `/world public`, `/world promote`, `/world ban`,
`/world unban`, `/world set` and `/world transfer` on the *backend*. An owner
sitting in the lobby — the normal case when their world is unloaded, which by
FR-25 is most of the time — cannot run any of them. `/world ban` in particular
is a moderation action that is most needed when the owner is not in the world.

FR-6 has a related problem: it requires invites to notify a target "online
anywhere on the network", which a backend node cannot do on its own.

Recommendation: register the management commands on the proxy (they are all
database operations), and have the proxy notify the node over plugin messaging
only for the parts that need a live world — ejecting a banned player, kicking
an online member. Note this changes the "Where" column for most of the table.

### C-7. `worlds.max-loaded` and `nodes.max-worlds` both cap loaded worlds

FR-26 defines a "global loaded-world cap" (default 5) in the backend config,
while 12.8 adds `nodes.max-worlds` per node. Which one FR-9g's browse list and
FR-26's refusal message check against is not stated, and in a multi-node pool a
backend config value is a strange place for a global cap.

Recommendation: make `nodes.max-worlds` the per-node limit enforced at
placement (MN-15 already implies this), and either move the global cap to the
proxy or drop it. FR-26's arithmetic ("5 loaded worlds means 15 Bukkit `World`
instances") is per node and should say so.

### C-8. NFR-3a proposes unloading dimensions separately, which FR-25 forbids

NFR-3a suggests "unloading a world's nether and end separately if profiling
shows idle dimensions are expensive". FR-25 requires all three to "unload
together, with save", and section 5.5 defines the visibility group as all three
dimensions as a single unit.

Not fatal — NFR-3a is phrased as a consideration — but if it is ever acted on,
FR-25, FR-25a's unload ordering and the group definition all need revisiting.
Better to state now whether partial unload is permitted.

---

## 3. Missing from the data model

FR-level requirements with no table to store them:

- **Per-world settings (FR-9e, `/world set`).** PVP, container access,
  redstone/doors for visitors, mob griefing. Nothing in section 4 holds these.
  Add a `player_world_settings` table or a `settings JSONB` column on
  `player_world`.
- **Pending ownership transfers (FR-32).** `player_world_ownership_log` records
  *completed* transfers. The 7-day pending offer, which `transfers.pending-expiry-days`
  configures, has nowhere to live.
- **Reconnect target (FR-13).** "Record the world for reconnect, so a rejoin
  from lobby offers a resume prompt." Derivable from the most recent
  `player_world_profile.updated_at`, but that is a guess, not a design.

Schema notes:

- **`player_world.folder` uniqueness is insufficient.** FR-2 derives sibling
  folders `<folder>_nether` and `<folder>_the_end`. A world named so that its
  folder is `foo_nether` collides with the nether of a world whose folder is
  `foo`, and the `UNIQUE` constraint will not catch it. Derive `folder` from
  the world UUID rather than from the player-supplied name, which also sidesteps
  path traversal and case-insensitive-filesystem collisions from `name`.
- **Ownership is stored twice.** `player_world.owner_uuid` and the `OWNER` role
  in `player_world_member` can disagree. FR-31 must update both in one
  transaction; the spec should say which is authoritative (recommend
  `owner_uuid`, with the member row's role as a denormalised convenience).
- **No index for the hot lookup.** `player_world_member`'s primary key is
  `(world_id, uuid)`, so "which worlds is this player a member of" — run by the
  proxy on every join, and by FR-24c's tab completion filter on effectively
  every keystroke — has no usable index. Add `CREATE INDEX ON
  player_world_member (uuid);` and the same on `player_world_invite (uuid)`.
- **`JSONB` is the wrong type for profile data (FR-14).** Item stacks carry
  arbitrary NBT including raw binary, so the payload has to be base64'd into the
  JSON anyway. More sharply, PostgreSQL rejects the null byte (U+0000) inside `jsonb`
  string values with `unsupported Unicode escape sequence`, and serialised item
  NBT will contain null bytes. Use `BYTEA` with an
  explicit `format_version INT NOT NULL` column, which also gives FR-17's
  version tag a real home instead of burying it inside the blob.
- **Enum-like `TEXT` columns have no constraints.** `state`, `visibility`,
  `role`, `reason` are documented in comments only. Add `CHECK` constraints —
  cheap, and they turn a whole class of bug into an insert failure.
- **`player_world_archive`'s primary key allows one archive per world.** With
  `restore_count` implying rows survive restores, a second archive of the same
  world must upsert and destroy the previous record. If archive history matters,
  key on `(world_id, archived_at)`.

---

## 4. Minecraft- and Paper-specific correctness

- **FR-4 is close to vacuous, and world creation will stall the node.**
  "Must not block the main thread for more than one tick beyond what
  `createWorld` itself requires" exempts the expensive part. `createWorld` is a
  blocking main-thread call, FR-2 requires three of them at once, and FR-4 adds
  spawn pre-generation. On a node hosting other people's live worlds, that is a
  multi-second freeze for every player on the node, every time someone runs
  `/world create`. Options: create the nether and end lazily on first portal
  transit (which FR-3a's portal handling already intercepts), or do creation on
  a node drained of other worlds, or accept it and say so with a number.
- **FR-19 misses the other server-wide broadcasts.** It covers join, quit and
  death. Advancement announcements (`announceAdvancements`) broadcast to every
  player on the server, not per world, and so leak presence and names between
  two worlds on one node exactly like a join message. Check also: sleep
  messages, raid bars, and `/me`. Recommend restating FR-19 as "all server-wide
  broadcasts are suppressed by default and re-emitted per group", with an
  enumerated list, rather than naming three.
- **FR-22's command list is EssentialsX-specific.** Vanilla and Paper ship
  presence-revealing surfaces too — `/list`, `/tell`, and target selectors
  (`@a`, `@p`) for anyone holding the permission. A deny-list of known plugin
  commands will drift; an allow-list of commands permitted inside a player world
  will not.
- **FR-16 has no recovery path.** Refusing to load an undeserialisable profile
  and sending the player to lobby is the right call, but as written it locks
  that player out of that world permanently with no admin remedy. Add an admin
  command to inspect and reset a profile, and keep the previous profile version
  so a reset is a rollback rather than a wipe. This pairs naturally with the
  generation-keyed profile history in B-1.
- **FR-11 reads the database in `PlayerJoinEvent`,** which NFR-2 forbids. The
  holding area is mentioned in section 3.1 but its role is never specified.
  State it: the player lands in the holding area, the lookup runs async, and the
  teleport happens on completion — with a timeout that sends them to lobby, per
  section 9.
- **MN-4's warm-start check is expensive at the worst moment.** Verifying
  "files already present locally with a matching checksum" means hashing
  gigabytes during the join the 5-second budget applies to. Trust size+mtime for
  the warm cache and write a clean-shutdown marker on unload; fall back to full
  hashing only when the marker is absent.
- **`World#save()` is a synchronous main-thread call.** MN-5 and MN-6 both open
  with "save the world" while NFR-7 requires storage work off the main thread.
  The save itself cannot be — only the upload can. Worth stating so the tick
  cost of a sync on a large world is budgeted rather than discovered.
- **FR-25c disables spawn chunks (`spawnChunkRadius` 0)** — correct, and worth
  noting it must be applied at load, since the default is per-world and will be
  restored from `level.dat` when the world is downloaded from object storage.

---

## 5. Operational gaps

- **No leader election for scheduled work.** FR-34 (archive after 90 days),
  invite expiry, `pending_transfer` cleanup and FR-32's 7-day transfer expiry
  are all periodic jobs. With an interchangeable node pool, every node will run
  them simultaneously. Designate a leader (a PostgreSQL advisory lock is
  sufficient and needs no new infrastructure), or move the jobs to the proxy.
- **Archival does not take a lease.** FR-35 unloads a world, packs it and
  deletes the live folders. Nothing stops a node acquiring the lease and
  loading the world mid-archive. Archiving must acquire the lease like any
  other writer, and should bump the generation when it finishes.
- **`/world create` has no cross-node flow.** The section 6 table places it on
  the proxy; FR-1 through FR-5 describe it as a purely backend operation. Which
  node creates the world, and when the lease is acquired relative to
  `createWorld`, is unspecified. It must be: placement → lease acquire →
  create → initial upload → manifest.
- **Quarantine has no size bound.** MN-13 quarantines rather than deletes,
  correctly, but `storage.local-cache-max-gb` governs only the LRU cache. A node
  that crash-loops will fill its disk with quarantined copies and then fail the
  free-space check in NFR-3. Add a retention policy and a config key.
- **The public-world threat model is not written down.** OQ-7 raises it. The
  concrete exposure: a stranger who joins a public world is running code on a
  node that is simultaneously hosting other people's private worlds, where the
  only thing separating them is this plugin's own isolation logic. Worth an
  explicit placement rule — prefer not to co-locate public worlds with private
  ones — which the placement service in MN-15 can express as a scoring term at
  little cost, and which limits the blast radius of any FR-18 to FR-24 bug.

---

## 6. Suggested answers to the open questions

- **OQ-6 (MOTD count).** Count them. It is a single integer with no identities
  attached, excluding them makes the network look emptier than it is, and any
  discrepancy between the MOTD count and `/world browse` is itself a signal.
- **OQ-7 (permission to go public).** Gate it behind a permission node,
  defaulting to off. It is one config line now and unwindable later; the reverse
  is not true, and it bounds the exposure in section 5's threat note until the
  isolation logic has been tested against real strangers.
- **OQ-8 (moderation path).** Yes, and the schema is nearly there — FR-9d's
  per-world ban covers the owner's side. What is missing is a path *to network
  staff*, since by design nobody outside the world can see what happens inside
  it. A `/world report` that captures world id, reporter, target and timestamp
  into a table staff can read is the minimum.
- **OQ-9 (`max-per-player` with transfers).** Raise the default to 2 and make
  the cap count *owned* worlds only. As written the cap's subject is ambiguous
  — if membership counted, joining one public world would block a player from
  creating their own, which is clearly not intended. FR-30 also becomes far less
  annoying at 2.
- **OQ-11 (10-minute sync interval).** 10 minutes is too generous given B-1:
  the rollback window is also the duplication window, so tightening it reduces
  both. With dirty-region tracking (B-3) a 3–5 minute interval should be
  affordable. Measure it in milestone 6 before committing to a default.
- **OQ-12 (MinIO co-located).** If it shares a host with the nodes, the split
  buys operational convenience but no durability, and 12.7 already flags it as
  a single point of failure for the whole feature. Either put it elsewhere or
  drop the pretence that local disk is disposable — NFR-9's test will pass
  either way, which is exactly why it should not be the only evidence.

---

## 7. Milestone adjustments

The existing ordering is good, and the rationale for putting 6 and 7 before the
second node is right. Three changes:

1. **Move the durability decision in B-1 before milestone 4.** The crash test
   in milestone 4 should be written against the final profile/world consistency
   model, or it will validate a model that is about to change.
2. **Fold B-4's sync path list into milestone 6's manifest work,** and add an
   explicit acceptance test: kill a mob-heavy world, sync, wipe local scratch,
   reload, verify the mobs are still there. NFR-9's test as written ("verify it
   is intact") will pass with an empty `entities/` directory if the check is
   only that the world loads.
3. **Add the SIGSTOP test to milestone 7 as a data test, not a liveness test.**
   The interesting assertion is not that the stalled node is fenced — it is that
   after the takeover node writes and the stalled node resumes and writes, the
   world still matches what the takeover node wrote. Under B-2's immutable
   layout that is true by construction, which is the point of the change.

---

## 8. Smaller notes

- FR-3 fixes the border at creation and section 2 lists resizing as out of
  scope, but FR-9f allows a world to go public later — a 5000-radius world is
  small for a public server. Worth noting that this is the reason resizing will
  come back as a request.
- FR-25a's unload order (end, nether, overworld) with an abort on a `false`
  return is good. Add that `worlds.unload-retry-minutes` retries the *whole*
  world, not the remaining dimensions, since a partial unload leaves the
  visibility group split.
- FR-27 says `/world delete` "archives the folder to a configured path" while
  FR-35 and MN-2 send archives to object storage under a key. Align the wording.
- Section 9's "player kicked mid-save: profile write completes before the
  session is released" needs a stated timeout — a hung database makes this an
  unbounded hold on the kick path.
- `archive.compression: zstd-9` is expensive for the compression ratio on
  region files, which are already zlib-compressed internally. `zstd-3` will be
  several times faster for a few percent more size. Measure in milestone 11.
- The command table's Permission column mixes permission nodes
  (`gzmn.worlds.create`) with role names ("owner role"). Give the roles real
  permission nodes too, so admin overrides work uniformly with FR-33 and
  FR-24b's `gzmn.worlds.admin` exemption.
