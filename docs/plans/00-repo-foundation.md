# Implementation Plan 00 — Repository Foundation

Status: draft for review
Covers: everything before spec milestone 1 ("Suggested milestones", §11)
Spec baseline: `docs/spec/v0.4.md` (D1, D2, D5 and D6 are folded in as of v0.4)

---

## 0. What this plan is, and what it deliberately is not

This is **milestone 0**: the repository, build, schema, threading model, test
harness and CI that every later milestone is written against. It contains no
gameplay behaviour. No `/world` command, no world load, no lease acquisition
is implemented here — those are milestones 1 onward.

The foundation is judged against exactly two stated goals:

1. **Runs 24/7 without problems.** Concretely: no unbounded work on the main
   thread, no silent failure modes, every failure observable before a player
   notices it, and every destructive path idempotent and restartable.
2. **Easily updated to new Minecraft versions.** Concretely: the amount of code
   that has to change when Paper ships a new Minecraft version is small,
   findable, and covered by a test that fails *before* the upgrade reaches
   production.

Anything in this plan that does not serve one of those two goals is marked
optional and can be dropped.

A deliberate constraint: the foundation must stay thin enough that milestone 1
can start within days, not weeks. Tasks that can be deferred without rework are
marked **[defer-ok]**.

---

## 1. Decisions taken

All six were open before this plan and are now settled. Each gets an ADR in
`docs/adr/` as part of task F0. D1, D2, D5 and D6 were amendments to the spec
and are folded into `docs/spec/v0.4.md`.

| # | Decision | Rationale |
| --- | --- | --- |
| D1 | **Minecraft version is gated in the database.** `player_world` and every manifest carry the chunk `DataVersion`; lease acquisition refuses a world newer than the acquiring node; placement filters on it. | The spec's "interchangeable nodes" assumption is false during an upgrade. Chunk `DataVersion` only moves forward — a world last saved by a 1.22 node cannot be opened by a 1.21 node. Without a gate, one mis-sequenced node restart during a rolling upgrade bricks a world. See §5.3. |
| D2 | **Control plane is a Postgres command table plus `LISTEN`/`NOTIFY`.** The durable row is the contract; `NOTIFY` is only a latency optimisation with polling as fallback. | §3.2 of the spec specifies plugin messaging, but a Velocity plugin message needs a connected player as its carrier. `/world admin unload <id>` against a node the caller is not on, and ejecting a banned player from a world elsewhere on the network, have no channel at all under plugin messaging. Postgres is already the single linearization point (MN-3a), so this adds no new infrastructure and no second source of truth. See §7. |
| D3 | **Java 21, Gradle with the Kotlin DSL.** | Spec-native; no `kotlin-stdlib` to shade and relocate into two plugin jars; every Paper and Velocity upgrade note applies verbatim rather than needing translation. Kotlin's real wins here are null safety and async ergonomics — the first is recovered with JSpecify + NullAway failing the build (§4), and the second is largely moot because concurrency on a node is tiny (≤5 worlds, a few dozen players). The hard problems are ordering and fencing, which coroutines do not help with. |
| D4 | **Monorepo: `:core` + `:backend` + `:proxy` in one Gradle build.** | The DB schema and the control-plane protocol are shared by both plugins and must never version-skew. One CI run proves the whole system. The Pelican extension (PHP, v1.1, optional) is kept out for now so PHP tooling does not enter CI for a component that may never be built. |
| D5 | **A node self-fences at lease expiry, not after 30 minutes.** A database outage ejects players at `lease_expires − nodes.fence-safety-margin-seconds`; `storage.max-sync-failure-minutes` applies only to object storage. | Resolves the contradiction between spec §9 and MN-10a. See §9.2. |
| D6 | **Snapshot copies are taken by quiesce → snapshot → verify**: auto-save off, forced save, quiet-period wait, reflink copy, post-copy re-stat, structural validation fused into the hash pass. | Closes the torn-region window MN-5a leaves open, using the established Minecraft `save-off` / `save-all flush` / copy / `save-on` idiom rather than a novel mechanism. See §9.1. |

---

## 2. Repository layout

```
DynamicPlayerWorlds/
├── settings.gradle.kts
├── build.gradle.kts                 # applies convention plugins only
├── gradle.properties                # java toolchain, paper/velocity api versions
├── gradle/
│   └── libs.versions.toml           # single version catalog for all modules
├── build-logic/                     # included build; convention plugins
│   └── src/main/kotlin/
│       ├── gzmn.java-conventions.gradle.kts
│       ├── gzmn.quality-conventions.gradle.kts
│       └── gzmn.plugin-conventions.gradle.kts     # shadow + relocation + jar naming
├── core/                            # :core — NO Bukkit, NO Velocity on the classpath
│   └── src/main/java/nl/gzmn/playerworlds/core/
│       ├── model/                   # records: WorldId, Generation, ManifestKey, Role, ...
│       ├── db/                      # repositories, Flyway migrations, DbClock
│       ├── storage/                 # content-addressed store, manifest, snapshot engine
│       ├── config/                  # typed config + startup validation
│       ├── control/                 # node_command protocol, NOTIFY listener
│       └── obs/                     # logging keys, metric names
├── backend/                         # :backend — Paper plugin (gzmn-worlds)
│   └── src/main/java/nl/gzmn/playerworlds/backend/
│       └── platform/                # the ONLY package allowed to know MC specifics
├── proxy/                           # :proxy — Velocity plugin (gzmn-worlds-proxy)
├── testing/                         # :testing — shared fixtures, Testcontainers factories
├── e2e/                             # docker compose harness + acceptance tests
├── docs/
│   ├── spec/                        # v0.4.md, the living specification
│   ├── adr/                         # architecture decision records
│   ├── plans/                       # this file
│   └── runbooks/                    # minecraft-upgrade, fenced-node, restore, minio-dr
├── .github/workflows/
├── CONTRIBUTING.md
├── CLAUDE.md
├── CHANGELOG.md
├── LICENSE                          # AGPL-3.0 (already present)
└── README.md
```

**The load-bearing rule of this layout:** `:core` must never gain a dependency
on `paper-api` or `velocity-api`. That single constraint is what makes the
storage engine, the lease logic, the manifest format, the profile
serialisation envelope and the control plane testable without booting a
Minecraft server — and it is what keeps the MC-version-sensitive surface small
enough to audit by eye. It is enforced by an ArchUnit test *and* by simply not
declaring the dependency, so a violation cannot compile.

The spec now lives at `docs/spec/v0.4.md`, with the version in the filename so
v0.5 lands alongside it. Superseded versions stay in git history rather than as
duplicated 70 KB files; v0.3 is commit `157e3ff`.

---

## 3. Build

- **Toolchain:** Gradle toolchain pinned to Java 21 via one property in
  `gradle.properties`. When Paper requires a newer JDK, that is a one-line
  change, not a search across modules.
- **Version catalog:** `gradle/libs.versions.toml` is the only place a version
  is written. `paperApi` and `velocityApi` are separate entries so an MC bump
  touches one line.
- **Convention plugins** in `build-logic/` rather than a giant `allprojects {}`
  block, so `:core` (a plain library) and the two plugin modules can diverge
  cleanly.
- **Shading:** both plugin jars shade and *relocate* their third-party
  dependencies (HikariCP, the S3 client, Flyway, Micrometer). Relocation is
  mandatory, not optional — an unrelocated Hikari on a server that also runs
  another plugin bundling a different Hikari is a classloader conflict that
  shows up as a startup failure weeks later. `:core` is shaded into both.
- **Reproducible builds:** `preserveFileTimestamps = false`,
  `reproducibleFileOrder = true` on all jar tasks, so a rebuild of a tag
  produces a byte-identical artifact and "is the running jar the one we
  released" is answerable.
- **Jar naming:** `gzmn-worlds-<version>+mc<paperApi>.jar`. The Minecraft
  version an artifact was built against belongs in its filename; an operator
  should never have to open a jar to find out.

Initial dependency set (all pinned in the catalog):

| Concern | Choice | Note |
| --- | --- | --- |
| DB pool | HikariCP | |
| DB driver | PostgreSQL JDBC | |
| Migrations | Flyway | §6 |
| Object storage | AWS SDK v2 S3 client | MinIO-compatible; path-style access |
| Compression | zstd-jni | `archive.compression` default `zstd-3` needs a native lib; bundle all target platforms or fall back to gzip |
| Logging | SLF4J + Logback JSON encoder | §9 |
| Metrics | Micrometer + Prometheus registry | §9 |
| Nullability | JSpecify annotations | §4 |
| Tests | JUnit 5, AssertJ, Testcontainers, ArchUnit | §10 |

Data access is **plain JDBC behind a thin repository layer**, not an ORM. Every
statement in this system that matters is a hand-shaped conditional `UPDATE`
whose exact predicate is the correctness argument (MN-3a, MN-8, D1). Those must
be readable as SQL in the source, not assembled by a framework. jOOQ remains an
option later for the read-side queries; it is not worth a codegen step against a
live database at this stage.

---

## 4. Code-quality gates

All of these fail the build, not warn:

- **Spotless** with Palantir Java Format — formatting is never a review topic.
- **Error Prone** with **NullAway**, and **JSpecify** `@NullMarked` at the
  package level across `:core`. This is the compensation for choosing Java
  over Kotlin (D3): the Bukkit API returns null from places that matter —
  `Bukkit.getWorld()` most of all, which FR-25b requires be re-resolved at
  every use — and NullAway turns "I forgot the null check" into a compile
  error. Configure Bukkit/Velocity as unannotated third-party packages so
  their platform types are checked at the boundary.
- **ArchUnit** rules, run as ordinary tests:
  - `:core` must not reference `org.bukkit.*`, `io.papermc.*`, or
    `com.velocitypowered.*`.
  - Only `backend.platform.*` may reference version-sensitive Minecraft
    surfaces (see §5.2).
  - No `java.sql.*` usage outside `core.db`.
  - No `System.currentTimeMillis()` / `Instant.now()` inside lease or
    lease-adjacent packages (see §8, DB time only).
- **forbidden-apis** to ban `net.minecraft.*` and `org.bukkit.craftbukkit.*`
  outright. NMS access is the single largest tax on Minecraft upgrades, and the
  cheapest moment to forbid it is before any exists.

---

## 5. Minecraft-version-proofing

This is the part of the foundation that most directly serves the second stated
goal, so it gets the most detail.

### 5.1 The rule: API only, no internals, ever

No NMS, no CraftBukkit casts, no reflection into server internals, no
Mixins/ASM. Enforced by forbidden-apis (§4). If something appears impossible
without NMS, it goes on an open-questions list and gets an explicit decision —
it does not get quietly implemented with a reflective hack, because that hack
becomes the reason an upgrade takes three weeks instead of an afternoon.

Nothing in spec v0.3 obviously requires NMS. The closest calls are dirty-region
tracking (MN-5b, addressed in §5.4 below) and dragon-fight state (FR-3b, which
the Bukkit API exposes via `DragonBattle`).

### 5.2 The seam: `backend/platform`

One package is allowed to know Minecraft-version specifics. Everything else
talks to its interfaces. The seam covers exactly the surfaces that have
historically moved between versions:

| Interface | Why it is version-sensitive |
| --- | --- |
| `WorldLayout` | Which paths inside a world folder exist and must be synced (MN-2a). `poi/` appeared in 1.14, `entities/` split out in 1.17; the Bukkit `DIM-1`/`DIM1` nesting is its own quirk. Hardcoding MN-2a's table is a silent-data-loss bug waiting for the next format change. |
| `ItemCodec` | Item stack serialisation for profiles (FR-14, FR-17). |
| `WorldRuntime` | Border, `spawnChunkRadius`, gamerules, dragon battle, save. |
| `PortalRouting` | FR-3a; portal event surfaces have changed shape before. |
| `ServerIdentity` | Minecraft version string and chunk `DataVersion` for D1. |

Each implementation is selected at runtime by data version, with a
`DefaultWorldLayout` used for anything at or above the version we currently
build against, plus a loud startup log line stating which layout was chosen.
A node that boots against an *unknown, newer* data version logs a warning and
proceeds with the default; a node that boots against an older-than-supported
one refuses to enable.

`WorldLayout` deserves emphasis because it inverts MN-2a: the spec writes the
synced path set as prose in a table, which means the code that implements it is
a hardcoded list nobody revisits. Making it a version-keyed provider with a test
per version turns "we forgot `entities/` again" into a failing test.

### 5.3 Version gating in the database (D1)

Schema additions (folded into the V1 baseline migration, §6):

```sql
ALTER TABLE player_world      ADD COLUMN data_version INT;   -- NULL until first commit
ALTER TABLE player_world      ADD COLUMN mc_version   TEXT;  -- display only
ALTER TABLE worlds_node       ADD COLUMN data_version INT NOT NULL;
ALTER TABLE worlds_node       ADD COLUMN mc_version   TEXT NOT NULL;
ALTER TABLE player_world_archive ADD COLUMN data_version INT NOT NULL;
```

Rules, all of them atomic and therefore race-free:

1. **Lease acquisition (MN-8) gains a predicate.** The conditional `UPDATE`
   that assigns the lease also requires
   `(data_version IS NULL OR data_version <= :my_data_version)`. Zero rows
   affected is already the "could not acquire" path, so this costs nothing
   structurally. The player sees "this world needs a newer server version" and
   the node logs it.
2. **Snapshot commit records the version.** The conditional `UPDATE` in MN-3a
   also sets `data_version = :my_data_version`, and the manifest records it. A
   world's version therefore advances exactly when it is durably written by a
   newer node — never speculatively.
3. **Placement (MN-14/MN-15) filters, then scores.** Nodes whose
   `data_version` is below the world's are excluded, not merely down-scored.
   This is a hard constraint alongside `nodes.max-worlds`.
4. **Archives are version-stamped.** Restore (FR-36) refuses onto a node older
   than the archive. Archives are never deleted (FR-37), so with a 90-day
   auto-archive window the repository *will* eventually hold archives several
   Minecraft versions old; the restore path must state clearly that restoring
   upgrades the world irreversibly.
5. **Downgrade is never attempted.** Minecraft has no supported chunk
   downgrade. The gate makes the failure a clean refusal instead of corruption.

**Operational consequence to document, not to fix:** the first snapshot after a
world is upgraded to a new Minecraft version rewrites a large fraction of its
region files, so that sync uploads far more than a normal incremental one — for
a multi-gigabyte world (NFR-3), potentially all of it. The MN-2b garbage
collector then reclaims the superseded objects once older manifests age out.
Plan upgrades for low-traffic windows and expect a storage spike. This belongs
in `docs/runbooks/minecraft-upgrade.md`.

### 5.4 Recommendation: replace MN-5b's chunk-save hook with an mtime+size stat walk

MN-5b specifies hooking chunk saves to record which region files were touched.
There is no stable Paper API for "a region file was written", so any
implementation means internals coupling — precisely the thing that makes
upgrades expensive.

The same result is available with no coupling at all: after `World#save()`,
stat-walk the world folder and compare `(size, mtime)` per file against the
last committed manifest. Only changed files are copied, hashed and uploaded.
The manifest already records size and mtime per file (MN-3), and MN-4 already
uses exactly this comparison in the other direction for the warm-start check —
so the mechanism is one the design has already committed to. A world at the
default border is on the order of a thousand files per dimension; a stat walk
over that is sub-millisecond and runs off the main thread.

This gives the same incremental behaviour MN-5b wants, removes an entire
category of version-fragile code, and is strictly easier to test.

Not folded into v0.4, because it was never put to you as a decision. It is
carried in the spec as OQ-13 so it stays visible until milestone 6 needs an
answer.

### 5.5 Item serialisation is a version-proofing decision, not a storage one

`player_world_profile.data` is the one place where Minecraft's own data format
is stored *outside* a world folder, so it will outlive several versions.

- **Use** Paper's `ItemStack#serializeAsBytes()` / `deserializeBytes()`. It
  produces version-tagged NBT that Mojang's DataFixerUpper migrates on read, so
  a profile written under 1.21 deserialises correctly on 1.22 without us
  writing a migration.
- **Do not use** `BukkitObjectOutputStream`, YAML `ConfigurationSerializable`,
  or any hand-rolled item encoder. These are Bukkit-version-coupled and
  historically break across updates — and FR-16 turns a deserialisation failure
  into a player locked out of their world, repaired only through the admin path
  in FR-16a.
- FR-17's `format_version` column tags **our envelope** (which fields, in what
  order, around the item blobs), not the item NBT itself. Keep the two layers
  distinct in code and in the ADR; conflating them is how the migration story
  gets lost.
- Verify at foundation time (F5) that the exact Paper method names are current
  for the targeted API, and pin the check with a round-trip test.

### 5.6 CI is the early-warning system

A nightly workflow builds and boots against Paper's *latest* build, separately
from the pinned version the release build uses. When Mojang ships a version
that breaks us, the failure arrives as a red nightly job weeks before an
operator tries the upgrade. Concretely this is what "easily updated" means in
practice — not that the code is clever, but that breakage is discovered by a
machine rather than by players. See §11.

---

## 6. Database foundation

- **Flyway**, migrations in `core/src/main/resources/db/migration`, naming
  `V<seq>__<snake_case>.sql`. Migrations are forward-only and immutable once
  merged.
- **Who runs migrations** is a decision the spec does not make, and it matters
  for 24/7: if every node migrates on startup, a rolling restart during a
  deploy has N nodes racing. Flyway takes a lock so it is safe, but a node
  starting against a *newer* schema than its code expects is not. Foundation
  rule: migrations run under the same Postgres advisory lock used by FR-40's
  maintenance election, and every node validates on startup that the schema
  version is within the range its code supports, refusing to enable otherwise.
  This makes mixed-version node pools explicit rather than accidental — the
  same posture as D1 takes for Minecraft versions.
- **V1 baseline** = spec §4 verbatim, plus:
  - the version columns from §5.3;
  - `node_command` (§7);
  - `player_world_report` and its chat-log capture — **FR-39 requires "a table
    network staff can read" and §4 never defines one**. Proposed:
    `player_world_report(id, world_id, reporter_uuid, target_uuid, reason,
    created_at, handled_at, handled_by)` plus `chat_log JSONB` on the report
    row rather than a separate always-on chat table, so ordinary chat is never
    persisted and only the window around a report is retained. Retention needs
    a config key and a maintenance-job sweep — see open question Q4.
  - `network_setting(key, value JSONB, updated_at, updated_by)` — see §8.1.
- **Every timestamp comparison uses database time.** `now()` in SQL, never a
  node's `System.currentTimeMillis()`. Node clocks drift, and every safety
  property in §12.3 of the spec is a timestamp comparison. A `DbClock` type in
  `core.db` is the only sanctioned source of "now" for lease logic, and
  ArchUnit forbids the alternatives in those packages (§4).
- **Testcontainers-backed schema test** from day one: the migration set applies
  to an empty database, and applies again idempotently.

---

## 7. Control plane (D2)

```sql
CREATE TABLE node_command (
  id           BIGSERIAL PRIMARY KEY,
  target_node  TEXT        NOT NULL,
  world_id     UUID        REFERENCES player_world(id) ON DELETE CASCADE,
  generation   BIGINT,                       -- discard if the world has moved on
  command      TEXT        NOT NULL,
  payload      JSONB       NOT NULL DEFAULT '{}'::jsonb,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at   TIMESTAMPTZ NOT NULL,
  claimed_at   TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  attempts     INT         NOT NULL DEFAULT 0,
  result       TEXT
);
CREATE INDEX node_command_pending_idx
  ON node_command (target_node, id) WHERE completed_at IS NULL;
```

- Producer inserts the row and calls `pg_notify('gzmn_node_' || target_node, id)`
  **in the same transaction**, so the notification is only delivered if the row
  committed. That ordering is the entire reason this is safe.
- Consumer holds one dedicated JDBC connection on `LISTEN`, plus a poll every
  `control.poll-seconds` (default 5) as a fallback, because a dropped
  connection loses notifications silently. The poll is the contract; `NOTIFY`
  is the optimisation.
- Claim is `UPDATE ... SET claimed_at = now(), attempts = attempts + 1
  WHERE id = ? AND claimed_at IS NULL RETURNING *`. One claimer wins.
- Commands carrying `generation` are discarded if the world's current
  generation differs — the same staleness rejection FR-11 applies to
  `pending_transfer`, for the same reason.
- **Handlers must be idempotent** (NFR-8's principle extended to control), since
  a claimed-but-uncompleted command is retried after `control.claim-timeout`.
- v1 command set: `EJECT_PLAYER`, `KICK_MEMBER`, `APPLY_SETTINGS`,
  `UNLOAD_WORLD`, `MIGRATE_WORLD`, `DRAIN_NODE`, `INVALIDATE_CACHE`.
- A `gzmn_proxy` channel carries the reverse direction (node → proxy: "world
  loaded, send the player"), same mechanics.
- Expired and completed rows are swept by the FR-40 maintenance job.

The foundation delivers the table, the protocol types, the listener with its
polling fallback, and its tests. No command *handlers* — those arrive with the
features that need them.

---

## 8. Configuration foundation

### 8.1 Split node-local config from network policy

Spec §7 places nearly all configuration in the backend's `config.yml`, but a
large share of it is enforced by the **proxy**: `worlds.max-per-player` is
checked at `/world create` (proxy), `invites.expiry-minutes` at `/world invite`
(proxy), `transfers.expiry-seconds` at handoff (proxy),
`worlds.public.browse-page-size` at `/world browse` (proxy). Meanwhile §7's
proxy `config.toml` lists only database credentials, a lobby name and a
transfer expiry. As written, two components hold their own copies of shared
policy and can silently disagree — and in a pool of interchangeable nodes,
several nodes can disagree with each other too.

Foundation rule:

- **Network policy lives in `network_setting`** (a database table): caps,
  expiries, retention counts, defaults, the allow-list of in-world commands.
  One value, all components, changeable without a restart, and auditable.
- **Node-local config stays in files**: `node.id`, `node.address`, paths,
  connection pool size, credentials.

Also to reconcile while defining the config schema — all of these are real
conflicts in v0.3, not readings of it:

- `worlds.storage-path` (§7) and `storage.local-scratch-path` (§12.8) describe
  the same directory under two names. Keep one.
- `archive.s3.*` (§7) and `storage.s3.*` (§12.8) describe two independent S3
  configurations. Archives and live objects can legitimately use different
  buckets, but they should share one client configuration with a bucket
  override, not two credential sets.
- `profiles.retain-snapshots` (default 3) and
  `storage.manifest-retention-count` (default 3) are described as "aligned".
  They must be *equal*, and the failure mode if they are not is severe: prune
  manifests faster than profiles and a load finds a `manifest_key` whose
  profiles are gone, so every player is issued a fresh profile per FR-15b —
  silent, total inventory loss for that world. Make it one key.

### 8.2 Validate at startup, refuse to run if invalid

Typed config objects, parsed and validated once at enable. Invalid config
disables the plugin loudly rather than running with a default that silently
violates a safety property. Checks that ship with the foundation:

- `nodes.dead-after-seconds < nodes.lease-seconds` (MN-18 — v0.2 had these
  inverted and it opened a takeover window against a live node).
- `node.heartbeat-seconds * 3 <= nodes.lease-seconds` (MN-9's tolerance
  argument; 30/180 gives six missed heartbeats).
- `node.heartbeat-seconds <= nodes.fence-safety-margin-seconds < nodes.lease-seconds`
  (§9.2 — a margin below one heartbeat interval fences on a single missed beat;
  a margin above the lease fences immediately and permanently).
- `storage.snapshot-quiesce-timeout-ms` leaves room inside
  `storage.commit-timeout-seconds` (§9.1 runs inside the commit budget).
- `profiles.retain-snapshots == storage.manifest-retention-count` (§8.1).
- `storage.commit-timeout-seconds` < the kick path's tolerance (§9 of spec).
- Scratch, cache and quarantine paths exist, are writable, and are on the same
  filesystem where reflink copies are expected (§10.3).
- Free space above the NFR-3 threshold.

---

## 9. Threading and concurrency foundation

The spec's hardest constraints are threading constraints (NFR-2, NFR-7,
FR-11, MN-5a). The foundation makes them structural rather than a matter of
reviewer vigilance.

- **Executor topology**, created once and owned by `:core`:
  - `main` — Bukkit scheduler; only API calls that require it.
  - `db` — small fixed platform-thread pool sized to the Hikari pool. (Not
    virtual threads: on Java 21 `synchronized` still pins carrier threads and
    pgjdbc synchronizes. At this scale a bounded pool is simpler and adequate.)
  - `io` — bounded to `storage.parallel-transfers` (NFR-7).
  - `sched` — single-threaded; lease heartbeat and commit orchestration.
- **Guards, not conventions.** `MainThread.assertOn()` / `assertOff()` are
  called at every boundary; in tests and dev builds a JDBC wrapper throws if
  invoked on the main thread. NFR-2 becomes a failing test rather than a
  code-review note.
- **Snapshot commits are single-flight per world.** FR-15 triggers a commit on
  *every* player leaving the world; on a busy public world that is a save,
  copy, hash, upload and transaction per quit. The commit engine must be a
  serialised per-world queue where a commit already in flight absorbs
  subsequent triggers and a single follow-up commit is scheduled after it. This
  is a foundation-level shape because retrofitting it later means rewriting
  every caller.
- **Every long operation is cancellable and bounded**, because a 24/7 process
  cannot afford an unbounded wait: cold load (`storage.cold-load-budget-seconds`),
  commit (`storage.commit-timeout-seconds`), holding area
  (`transfers.holding-timeout-seconds`).
- **Shutdown is a first-class path** (FR-28): an ordered, timeout-bounded
  shutdown sequence, tested, so a planned restart never loses a world.

### 9.1 Snapshot copy: quiesce → snapshot → verify (D6)

MN-5a's procedure — save on the main thread, then `cp --reflink=auto` the dirty
files — leaves the window it exists to close. Paper's chunk IO is asynchronous
and the server keeps ticking between the save returning and the copy running, so
a region file can be rewritten *during* its own copy. Reflink copies are not
atomic against a concurrent in-place write.

The chosen procedure is the standard one for backing up a live datastore that
has no native snapshot: **quiesce the writer, take the snapshot, verify it.**
For Minecraft specifically this is the long-established `save-off` /
`save-all flush` / copy / `save-on` idiom that every server backup script uses;
we are hardening it, not inventing anything.

1. **Main thread** — `setAutoSave(false)` on all three dimensions. Wrapped in
   `try`/`finally` *and* watched by an independent watchdog, because an
   auto-save flag left off means the world never saves again and the next crash
   loses everything. That is a strictly worse bug than the one this prevents, so
   restoring the flag must not depend on the happy path.
2. **Main thread** — `World#save()` per dimension.
3. **Off thread — quiet-period wait.** Poll `(size, mtime)` across the dirty set
   until nothing has changed for `storage.snapshot-quiet-ms` (default 250),
   bounded by `storage.snapshot-quiesce-timeout-ms` (default 5000). This is the
   API-only substitute for flushing Paper's chunk IO queue, which has no stable
   API and which we will not reach into (§5.1). On timeout, continue — step 5
   catches whatever moved.
4. **Copy** the dirty set into a per-sync snapshot directory on the same
   filesystem, using reflink (`FICLONE` / `copy_file_range`) with a detected
   fallback to a plain copy. Never hard links — MN-5a is right that a shared
   inode makes the copy pointless.
5. **Re-stat the sources.** Any file whose `(size, mtime)` moved while it was
   being copied is re-copied, bounded by `storage.snapshot-copy-retries`
   (default 3). A file that will not settle aborts this sync; the next one picks
   it up. Aborting a sync is cheap, uploading a torn region is not.
6. **Restore auto-save.**
7. **Validate structurally while hashing.** Every `.mca` file gets its 8 KiB
   header checked as it is read: offsets and sector counts inside file bounds,
   no overlapping sector allocations, each chunk's declared length consistent
   with its sector count. Content addressing means we already read every byte to
   hash it, so validation shares that pass and costs approximately nothing. A
   file that fails aborts the snapshot rather than being uploaded.
8. **Upload, write manifest, commit** (MN-3a). The snapshot directory is deleted
   on success, and orphan snapshot directories found at startup are deleted
   outright — they are derived data, not crash debris, so unlike MN-13's scratch
   directories they are not quarantined.

Why not the alternatives:

- **Filesystem snapshots** (btrfs subvolume, LVM, ZFS) are strictly stronger —
  genuinely atomic across all files — and are what this would use if the nodes
  ran on bare metal. Pelican runs nodes in containers, where those operations
  need host privileges. Worth keeping as an opt-in fast path if a node is ever
  deployed outside a container; not the baseline.
- **Copy-then-verify without quiescing** detects tearing but gives no
  convergence bound on a busy world: the retry can lose the race repeatedly.
- **Reading the live folder directly** is what MN-5a already rejects, correctly.

Two consequences worth stating plainly. First, MN-5a's "close to free" claim for
`cp --reflink=auto` holds only on XFS and btrfs; on ext4 it silently degrades to
a full copy of the dirty set, which is why §10.4 probes and logs the real verdict
at startup and why the free-space check must budget for the snapshot directory.
Second, the module split falls out cleanly: steps 1–2 and 6 are
`backend/platform` (`WorldRuntime`), and steps 3–5, 7 and 8 are `:core` — so the
riskiest correctness code in the system is unit-testable without booting a
server.

New config: `storage.snapshot-quiet-ms`, `storage.snapshot-quiesce-timeout-ms`,
`storage.snapshot-copy-retries`, `storage.verify-region-structure` (default
true; a kill switch, not a tuning knob).

### 9.2 Lease self-expiry and the database-outage bound (D5)

Spec §9 and MN-10a cannot both hold: §9 keeps a node playing for
`storage.max-sync-failure-minutes` (30) when the database is unreachable, while
MN-10a self-fences when the heartbeat cannot extend the lease — and the
heartbeat needs the database. With a 180-second lease, MN-10a fires roughly
twenty-seven minutes first. Resolved in favour of lease expiry.

The node distinguishes two states the spec conflates:

- **Lease observed lost** — the database is reachable and the conditional
  update affected zero rows, so another node holds it or the generation moved
  on. Run MN-10's shutdown path immediately. This is MN-10a exactly as written.
- **Database unreachable** — ownership is *unknown*. Fencing instantly buys
  nothing for integrity: the node cannot commit without the database (MN-3a) and
  its uploads are harmless by construction (MN-2, MN-3). The real risk is
  divergence, where another node takes the expired lease and loads the world
  while this one keeps ticking a copy whose progress can never be committed.

So the rule is the standard lease-client one: **a lease holder must give up
strictly before the grantor would consider the lease expired.** The node tracks
`leaseValidUntil`, the `lease_expires` value returned by its last *successful*
heartbeat in database time, and runs the MN-10 shutdown path at
`leaseValidUntil − nodes.fence-safety-margin-seconds` (default 30). Because the
margin is subtracted client-side against a grant issued in database time, the
node always releases before any other node can take over, without needing the
two clocks to agree.

With the 180/30 timings that ejects players about 150 seconds — five missed
heartbeats — into a database outage, losing at most one sync interval of
progress. New joins are already refused from the first failure (spec §9).
`storage.max-sync-failure-minutes` now applies **only** to the
object-storage-unreachable path (§12.7), where the lease keeps renewing normally
and the local copy stays authoritative — which is what makes 30 minutes a
reasonable figure there and an impossible one here.

The world's scratch directory is quarantined on this path per MN-10, since it
has diverged from the last committed manifest and must never be reused as a warm
cache. Note that this is cheaper than it sounds: the local *object* cache is
content-addressed and immutable, so it survives untouched and a later reload of
that world is still mostly warm.

New config: `nodes.fence-safety-margin-seconds` (default 30).

---

## 10. Observability foundation (the 24/7 requirement)

### 10.1 Logging

SLF4J + Logback with a JSON encoder. MDC keys standardised in `core.obs` and
used everywhere: `node_id`, `world_id`, `generation`, `player_uuid`, `op`,
`trace_id`. NFR-6's event list (create, join, invite, kick, unload, delete,
lease acquire/release, sync start/finish, every fencing abort) is a typed
enum, so an event cannot be logged with a misspelled name and vanish from a
dashboard.

### 10.2 Metrics

Micrometer with a Prometheus endpoint per node. Minimum set:

`worlds_loaded`, `lease_acquire_total{result}`, `lease_lost_total{reason}`,
`fence_events_total`, `commit_duration_seconds`,
`commit_failed_total{reason=fenced|db|storage}`, `sync_bytes_total`,
`sync_files_total`, `world_load_seconds{kind=warm|cold}`,
`create_stall_ms` (FR-4's release-gating number, measured continuously rather
than once), `holding_timeouts_total`, `quarantine_bytes`, `scratch_free_bytes`,
`db_pool_wait_seconds`.

### 10.3 Alerts to define before go-live

Fencing aborts > 0; sync failing for longer than half
`storage.max-sync-failure-minutes`; any lease loss on a node that believed it
was healthy; database unreachable; scratch free space below the NFR-3
threshold; quarantine growth (MN-13a's crash-loop-fills-disk scenario);
`create_stall_ms` above budget.

### 10.4 A startup capability probe

On enable, the node probes and logs, once, loudly:

- filesystem type of the scratch path and **whether reflink copies actually
  work there** — `cp --reflink=auto` silently falls back to a full copy, so on
  ext4 (a very common default) MN-5a's "close to free" snapshot becomes a full
  copy of the dirty set on every sync, with the disk and IO cost that implies.
  Discovering that from a graph six weeks in is the expensive way;
- free space, and the headroom implied by NFR-3;
- Minecraft version and chunk `DataVersion` (D1);
- schema version and whether it is within the supported range;
- database and object-storage reachability, with a real round trip.

Any probe failure that would violate a safety property refuses the enable.

---

## 11. Testing foundation

| Layer | Mechanism | Runs in CI |
| --- | --- | --- |
| Unit | JUnit 5 + AssertJ on `:core` — no server needed, which is the payoff of the module split | every push |
| Architecture | ArchUnit rules from §4 | every push |
| Database | Testcontainers Postgres; migrations, lease predicates, MN-3a commit semantics | every push |
| Object storage | Testcontainers MinIO; content addressing, manifests, idempotent retry (NFR-8) | every push |
| Plugin surface | MockBukkit for the thin backend layer — kept thin deliberately, since MockBukkit tracks Minecraft versions and can lag a new release, which would otherwise block an upgrade | every push |
| End-to-end | docker compose: Postgres + MinIO + Velocity + 2 Paper nodes, driven over RCON and a test-only plugin | nightly + pre-release |
| Fault injection | toxiproxy for DB/S3 partitions; `docker pause` for the SIGSTOP fencing test (milestone 7) | nightly |

Foundation deliverable is the **harness**, not the milestone tests: `:testing`
with `TestDatabase`, `TestObjectStore` and `WorldFixture` factories, plus one
green smoke test per layer. The milestone-6 and -7 acceptance tests (scratch
wipe / NFR-9; SIGSTOP fencing) are written later but must find their harness
already waiting, because both are far harder to bolt on afterwards.

One fixture decision worth taking now: `WorldFixture` should generate synthetic
files for storage-layer tests (fast, no server) and use a small committed real
Anvil world only in the e2e harness (a few MB, kept out of the main build).
Committing a multi-gigabyte realistic world is not viable; NFR-3's real-size
measurement happens against a live world in milestone 6.

---

## 12. CI/CD

- `build.yml` — Spotless check, Error Prone/NullAway, build, unit + ArchUnit +
  Testcontainers tests. Every push and PR.
- `paper-latest.yml` — **nightly**, builds and boots against Paper's latest
  build rather than the pinned one. This is the mechanism from §5.6 and is the
  single highest-value workflow in the repo for the "easily updated" goal.
- `e2e.yml` — nightly compose harness.
- `release.yml` — tag-triggered, reproducible build, jars attached with the
  `+mc<version>` suffix, CycloneDX SBOM, checksums.
- `dependency-review` + a **license check**: the repository is AGPL-3.0, which
  is fine alongside Velocity's GPL-3.0 and Paper's licensing, but a
  permissively-incompatible transitive dependency should fail CI rather than be
  discovered later.
- **Renovate**, with Paper and Velocity in their own group labelled
  `minecraft-update` so an MC bump is never bundled into a routine dependency
  PR.
- Branch protection: PRs only, CI green required, linear history.

---

## 13. Documentation and governance

- `docs/adr/` — the four decisions in §1 written up first, then one per
  irreversible choice. Cheap, and the reason "why is it like this" stays
  answerable.
- `docs/runbooks/` — **`minecraft-upgrade.md` first**, because it is the
  procedure the second goal is actually about: pin the new Paper version, run
  the nightly matrix, upgrade one node, confirm the D1 gate keeps older nodes
  off upgraded worlds, expect the large first sync (§5.3), roll the rest.
  Then `fenced-node.md`, `restore-from-archive.md`, and `minio-dr.md` — the
  spec itself names MinIO as a single point of failure for the whole feature
  (§12.7) and requires its restore path be tested before go-live.
- `CONTRIBUTING.md` — the non-negotiable rules in one page: no NMS, no blocking
  IO on the main thread, no `World` references cached across an unload
  (FR-25b), DB time only, migrations forward-only.
- `CLAUDE.md` — the same rules in the form agent sessions read.
- `CHANGELOG.md` — Keep a Changelog format; semver where the MC version is
  build metadata, not part of the version.

---

## 14. Work breakdown

Ordered. Each task states what "done" means. Rough sizes assume one developer.

| ID | Task | Done when | Size |
| --- | --- | --- | --- |
| F0 | Repo hygiene: `.gitignore`, `.editorconfig`, `.gitattributes`, README rewrite, CONTRIBUTING, CLAUDE.md, six ADRs from §1 (spec move already done) | A newcomer can read the repo and know the rules | S |
| F1 | Gradle skeleton: settings, version catalog, `build-logic` conventions, five empty modules, shadow + relocation + reproducible jars | `./gradlew build` produces two correctly-named, correctly-relocated plugin jars that load on a real Paper/Velocity and log "enabled" | M |
| F2 | Quality gates: Spotless, Error Prone + NullAway + JSpecify, forbidden-apis, ArchUnit rules from §4 | A deliberate NMS import and a `:core` → Bukkit import each fail the build | S |
| F3 | Database foundation: Flyway, V1 baseline (spec §4 + §5.3 + §6 additions), repository skeleton, `DbClock`, Hikari, Testcontainers schema test | Migrations apply to an empty DB in CI; schema-version guard refuses an out-of-range node | M |
| F4 | Config foundation: typed node config, `network_setting` accessor with cache + invalidation, all §8.2 startup validations | Every conflicting/duplicate key from §8.1 resolved and recorded; an invalid config refuses the enable | M |
| F5 | MC-version seam: `platform` interfaces, default implementations, `ServerIdentity` reporting data version, `ItemCodec` round-trip test (§5.5) | The seam exists and the ArchUnit rule confines MC knowledge to it | M |
| F6 | Threading foundation: executors, main-thread guards, JDBC-on-main-thread test failure, bounded-operation helpers, ordered shutdown | A test proving a JDBC call from the main thread fails the build | S |
| F7 | Control plane: `node_command`, protocol types, `LISTEN` listener with polling fallback, claim/complete/retry, tests | Two processes against one Testcontainers DB exchange a command, survive a killed listener connection, and never double-execute | M |
| F8 | Observability: JSON logging + MDC, typed event enum, Micrometer registry + endpoint, startup capability probe (§10.4) | Probe output visible in a booted node's log, including the reflink verdict | M |
| F9 | Test harness: `:testing` fixtures, Testcontainers factories, MinIO fixture, one smoke test per layer | Green in CI, under five minutes | M |
| F10 | CI/CD: all five workflows, Renovate, branch protection, SBOM, license check | Nightly Paper-latest job green and demonstrably able to go red | M |
| F11 | **[defer-ok]** e2e compose harness (2 nodes, Velocity, MinIO, Postgres) | One player joins a lobby in CI | L |
| F12 | **[defer-ok]** Durability primitives: reflink copier with fallback detection, region-file structural validator, hash-and-validate single pass (§9.1 steps 4–7) | Property tests over synthetic `.mca` files: every single-byte corruption of a header is rejected; a file mutated mid-copy is detected and retried | M |

F0–F2 are prerequisites for everything. F3, F5 and F6 can proceed in parallel
afterwards. F11 can wait until milestone 5, when a second node first exists.

F12 is marked defer-ok because it belongs to milestone 6, but it is worth
pulling forward: it is pure `:core` code with no server dependency, it is the
highest-consequence correctness code in the system, and it is far easier to test
in isolation than inside a running sync.

---

## 15. Open questions and spec issues found

Resolved since the first draft, now recorded as decisions rather than
questions: the database-outage bound (D5, §9.2), the snapshot copy procedure
(D6, §9.1), and the package root `nl.gzmn.playerworlds`. The first two are in
spec v0.4 as MN-10b and MN-5a/MN-5c.

The four below are also carried in spec v0.4 as OQ-13 to OQ-16, so they are not
lost if this plan is archived.

Of what remains, none is blocking for F0–F12; Q1 and Q2 become blocking at
milestones 5 and 9 respectively.

**Q1 — the `/world` command root is claimed by both components.**
Spec §6 puts `/world leave` and `/world report` on the backend and everything
else on the proxy. A Velocity plugin registering `/world` intercepts the whole
namespace, so the two backend subcommands become unreachable unless the proxy
handler explicitly forwards unrecognised — or specifically those — subcommands
to the backend. Not hard, but it must be designed in, not discovered. Confirm
the proxy owns the root and forwards a known list.

**Q2 — FR-39's report table is missing from §4, and its chat log needs a
retention policy.** Proposal in §6. The chat-log capture in particular stores
player conversation, so it needs an explicit retention period and a maintenance
sweep. What retention do you want — 30 days, 90, until the report is handled?

**Q3 — proxy tab completion (FR-24c) cannot hit the database per keystroke.**
The membership index in §4 makes the query cheap, but a query per tab-completion
per player is still the wrong shape. Foundation answer: a membership cache on
the proxy invalidated over the D2 control channel. Flagging it because it means
the control plane carries cache invalidation from the start, which F7 already
provides for.

**Q4 — OQ-10 and OQ-12 remain open in the spec and affect foundation choices.**
How many `worlds` nodes at launch, and does MinIO run on the same host as the
nodes? If MinIO shares a host with the nodes, a single host failure takes both
the working copy and the source of truth, which removes most of the value of
the split — and it changes how much the e2e harness (F11) needs to model. Not
blocking for F0–F12.

---

## 16. Explicitly not in this plan

World creation, loading, unloading, borders, portals, membership, invites,
visibility isolation, profiles, leases, placement, sync, archival — all of it is
milestone 1 and later. The foundation provides the seams these land in, and
nothing more. If a foundation task starts implementing behaviour, it has grown
past its purpose and should be cut back.
