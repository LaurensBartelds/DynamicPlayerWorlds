# Next steps

Short working list. Full detail and acceptance criteria live in
[`00-repo-foundation.md`](00-repo-foundation.md) and
[`01-world-lifecycle.md`](01-world-lifecycle.md); the specification is
[`../spec/v0.4.md`](../spec/v0.4.md).

F0–F12 are done. `./gradlew build` is green on all modules against Paper 26.2 and
Velocity 4.0.0, each quality gate has been verified by deliberately breaking it,
and both plugin jars have been loaded on real servers.

## Milestone 1 — world lifecycle: done, verified on a real node

Confirmed on the GZMN test instance (Paper 26.2-112, PostgreSQL 17.11): the
plugin enables, migrates V0 → V1, passes the capability probe, and
`/pworld create` generates a world whose **three dimensions all materialise** —
the overworld eagerly and the nether and end on first transit (FR-2, FR-3a,
FR-4). Portal linking resolving to the world's own dimensions is the part spec
section 11 said to do first because it was most likely to surprise; it did not.

Two things the first boot found, both now fixed and both invisible to any test
that does not involve a real server:

- The relocated JDBC driver never registered with `DriverManager`, so a node
  refused to enable with "No suitable driver" for a driver sitting in its own
  jar. Same trap existed a second time in the control-plane listener.
- `/pworld` is `default: op` and a fresh server has an empty `ops.json`. Paper
  hides commands the caller cannot use, so a permission denial is
  indistinguishable from an unregistered command. Enable now logs the
  registration and the permission it needs.

Still open from milestone 1, and worth doing while a world is in front of you:

- [ ] **OQ-17** — the end *generates*, but confirm a player arriving there lands
      on the obsidian platform rather than falling into the void.
- [ ] The `createWorld` stall number for `worlds.create-stall-budget-ms`. Every
      generation now logs it at INFO, so the next create prints it.
- [ ] Idle unload after ten minutes, and the FR-25a retry.
- [ ] Dragon fight state across an unload and reload (FR-3b).

## Milestone 1 — how it was built

Plan [`01-world-lifecycle.md`](01-world-lifecycle.md). `./gradlew check build` is
green: 168 tests across `:core`, `:backend` and `:testing`, none failing and
none skipped.

Taken as decisions before writing code, both recorded in plan 01 §1:

- **D7** — milestone 1 is database-backed, not the spec's "hardcoded owner, no
  database" staging. F3 already delivered `player_world` and its harness, so the
  in-memory version would have been a throwaway implementation of a tested table.
- **D8** — the backend command root is `/pworld`, not `/world`. The proxy claims
  `/world` in milestone 5 (OQ-15), and a registration here would have to be torn
  back to two subcommands.

### What must happen on a real node before this milestone is believed

These are the acceptance criteria, and none can be checked off a server. Spec §11
says to measure the stall *here*, which is the same point.

- [ ] **`/pworld create` end to end**, and the `createWorld` stall it reports.
      That number sets `worlds.create-stall-budget-ms` and is release-gating
      (FR-4). It is measured continuously into `create_stall_ms`, so a scrape
      after a few creates is the answer.
- [ ] **Portal linking both ways** (FR-3a) — overworld to nether and back with
      8:1 scaling, and the end in both directions. The routing maths is
      unit-tested; what is untested is the event surface.
- [ ] **The end arrival platform (OQ-17).** Vanilla generates it as part of its
      own end-portal handling. If it does not do so for a plugin-supplied
      destination world, a player entering the end falls into the void. **Check
      this one first** — it is the only item here that is unsafe rather than
      merely unmeasured.
- [ ] **Dragon fight state across unload and reload** (FR-3b). MockBukkit has no
      `DragonBattle`, so this has never been exercised.
- [ ] **Idle unload** after `worlds.idle-unload-minutes` (FR-25), and the FR-25a
      retry against a world held open by a force-loaded chunk.

### Found while building, and fixed

- **A `database.pool-size` below 4 deadlocked startup.** The migration advisory
  lock holds one connection while Flyway takes two; a pool of three or fewer
  waited for a connection that could not arrive, then failed the enable with a
  timeout message that named the wrong cause. `DatabaseSettings.MIN_POOL_SIZE`
  now refuses it up front. The default of 8 had hidden it.
- **The capability probe ran on the main thread.** Plan 00 §10.4's probe does a
  database round trip, a free-space stat and a reflink trial copy. `MainThread`
  caught it on the first real caller and turned an invisible startup stall into a
  refused enable; it now runs on the io pool under a budget.
- **A MockBukkit test can vanish into a skip.** `ServerMock.getWorldContainer()`
  throws `UnimplementedOperationException`, which MockBukkit reports as *skipped*
  rather than failed — the plugin smoke test silently stopped testing anything.
  `worldContainer()` is now a `protected` hook like `detectIdentity()`. The
  general caution is the point: anything load-bearing needs a real node or a
  `:core` test.
- **The backend was holding `java.sql.Connection`.** ArchUnit caught the
  orchestration composing transactions with `Connection`-taking lambdas.
  `PlayerWorldRepository` gained transaction-owning overloads; the rule now
  permits `SQLException` alone, since `:core`'s repositories declare it.

Not fixed, and deliberate: `assigned_node`, `lease_expires` and `generation` stay
at their defaults. MN-8's conditional `UPDATE` is the whole of the lease
guarantee, and a milestone-1 statement that set those columns without it would
read like a lease while providing none of one (plan 01 §5.3).

## First, in an environment that can reach repo.papermc.io — done

`:backend` and `:proxy` had never compiled, because the sandbox they were written
in blocks `repo.papermc.io`. They compile now.

- [x] `paperApi` and `velocityApi` set to Paper `26.2.build.112-stable` and
      Velocity `4.0.0`. Minecraft's versioning changed after 1.21.11 — releases
      are now year.season — so `paperApi` carries the Paper build number and the
      jar-name derivation reduces both coordinate shapes to the Minecraft version
      alone. Jars are `gzmn-worlds-0.1.0-SNAPSHOT+mc26.2.jar`.
- [x] **Java toolchain 21 → 25.** `paper-api` 26.x is published with a Java 25
      target and Gradle refuses to resolve it against a 21 toolchain, so this was
      not optional. ADR 0003 has a note; its "Java 21" title records the
      language decision, not the JDK number.
- [x] `./gradlew build` green. What the two plugin modules turned up, in order:
      `com.mojang:brigadier` (a `paper-api` transitive absent from Maven Central,
      so the papermc content filter had to allow `com.mojang`);
      `String#formatted` in `GzmnWorldsPlugin` (banned by forbidden-apis for
      using the default locale); and the packaging defects below. `ServerIdentity`
      and the Velocity `@Plugin` processor both compiled unchanged.
- [x] **Relocation was broken, and is the reason this list gained a gate.** Both
      jars shipped unrelocated Netty, Apache HttpClient 5, Jackson,
      reactive-streams and HdrHistogram, plus a second `org.slf4j` colliding with
      the one both platforms provide. The relocation list named only the direct
      dependencies, so every transitive escaped. Fixed, and `verifyShadedJar` now
      fails `check` if any class in a plugin jar sits outside
      `nl/gzmn/playerworlds/`. Jars went 29 MB → 22 MB.
- [x] `:backend:test` runs: `ArchitectureTest` 4/4, including the FR-25b rule
      that no field may hold a `World`. 13 tests green across `:core` and
      `:backend`.
- [x] **Both shaded jars load on a real Paper and Velocity and log `enabled`.**
      F1's acceptance criterion is met. Verified on the GZMN test instances
      (Pterodactyl on Unraid, Linux x86_64, Temurin 25.0.3): Paper
      `26.2-112-main`, implementing API version `26.2.build.112-stable` — exactly
      the pin — and Velocity `4.1.0-SNAPSHOT`. A player connected through the
      proxy to the backend and joined.
      - **This node's chunk `DataVersion` is 4903.** That is the number every
        decision in spec §12.9 is taken against (ADR 0001), and the first time it
        has been observed rather than assumed.
      - No duplicate-binding SLF4J warning and no Netty or Jackson complaint from
        either server, which is the relocation fix holding up in the one place
        that can actually prove it.
      - `plugin.yml` now expands `api-version` from the `paperApi` pin, so the
        descriptor cannot claim an older API than the jar was built for.

### Things found while doing it

- [ ] **zstd natives are 6.4 MB of the 22 MB jar**, covering eighteen
      platform/arch pairs including `aix/ppc64`, `linux/mips64`, `linux/riscv64`
      and `linux/loongarch64`. The F1 boot confirms nodes run **Linux x86_64**
      under Pterodactyl, so trimming to `linux/amd64` plus `linux/aarch64` is now
      a decision that can actually be taken, saving about 5 MB per jar. The one
      thing it would break is a developer running a Paper node on Windows or
      macOS, since the failure is at first compression rather than at startup.
      Decide alongside OQ-10.
- [ ] **The proxy runs Velocity `4.1.0-SNAPSHOT` while `velocityApi` pins
      `4.0.0`.** Deliberately left as is: compiling against a stable release and
      running a newer server is the safe direction, and pinning a SNAPSHOT would
      make the build non-reproducible, which §3 of the plan explicitly buys with
      the reproducible-jar work. Revisit only if 4.1.0 ships API the proxy needs.
- [x] **Relocating `net.logstash.logback` means a logback configuration must name
      the encoder by its relocated class**, not
      `net.logstash.logback.encoder.LogstashEncoder`. Documented under
      `config/logback/` (F8).

## Remaining foundation tasks

- [x] **F3** Database. Done: `V1__baseline.sql` (spec §4 verbatim — the version
      columns were already folded into v0.4 — plus `player_world_report` for
      FR-39 and `network_setting` for plan §8.1), `Database` on Hikari with
      autocommit off, `DbClock`, `AdvisoryLock` for the FR-40 election,
      `Repository` + `RowMapper` as the seam, `Schema` with the version guard, and
      13 Testcontainers tests against PostgreSQL 18.3. `:core:test` is 22 green.
      - The guard refuses **before** migrating when the schema is newer than
        `Schema.MAX_SUPPORTED`, which is the rolling-deploy case it exists for.
        Adding a migration means bumping `MAX_SUPPORTED` in the same commit.
      - Migrations run under `AdvisoryLock.MAINTENANCE_KEY`, bounded to 60s and
        refusing rather than hanging, per plan §6.
      - `Repository` takes a `Connection` per call rather than fetching its own,
        so MN-3a can commit a manifest pointer and its profiles in one
        transaction.
      - Testcontainers is declared on `:core`'s test classpath directly rather
        than via `:testing`, because `:testing` depends on `:core` and the
        reverse would be a cycle. F9's fixtures serve `:backend`, `:proxy` and
        e2e; `:core` owns the database and tests it itself.
- [x] **F4** Config. Done: typed `NodeConfig` / `ProxyConfig` / `StorageClientSettings`,
      `NetworkPolicy` with specification defaults, `NetworkSettings` (cache +
      `invalidate` for the control-plane `INVALIDATE_CACHE` command), and
      `ConfigValidator` for every §8.2 check. ADR 0007 records the three key
      reconciliations and answers OQ-16.
      - `worlds.storage-path` → `storage.local-scratch-path` (node-local).
      - `archive.s3.*` credentials → one `storage.s3.*` client with optional
        `archive-bucket` override.
      - `profiles.retain-snapshots` and `storage.manifest-retention-count` → one
        key, `storage.manifest-retention-count`; a leftover
        `profiles.retain-snapshots` row is refused at policy load.
      - Invalid config throws `ConfigException` so enable refuses rather than
        running with a default that silently violates a safety property.
- [x] **F5** Minecraft version seam. Done: `backend.platform` holds `WorldLayout`,
      `ItemCodec`, `WorldRuntime`, `PortalRouting` and `ServerIdentity`, selected
      by chunk data version through `Platform` at enable. ADR 0008 separates item
      NBT from the profile envelope's `format_version`.
      - `DefaultWorldLayout` encodes the Bukkit `DIM-1`/`DIM1` layout and the
        MN-2a required set (`region/`, `entities/`, `poi/`, `data/`, `level.dat`).
      - `PaperItemCodec` calls `serializeAsBytes` / `deserializeBytes` by name so
        a Paper rename is a compile failure; unit tests pin the signatures. A
        behavioural item byte round-trip still needs a running Paper node (the
        methods bottom out in the server bridge).
      - `PaperWorldRuntime.disableAlwaysLoadedSpawnChunks` is a documented no-op:
        Minecraft 1.21.9+ dropped always-loaded spawn chunks, so FR-25c is the
        platform default on this API line.
      - Unknown newer data version: warn and use the default layout. Older than
        `Platform.MIN_SUPPORTED_DATA_VERSION` (4903): refuse enable.
- [x] **F6** Threading foundation. Done: `core.concurrent` holds `MainThread`,
      `PluginExecutors` (main / db / io / sched), `BoundedOperations` and
      `MainScheduler`. `Database` calls `MainThread.assertOff()` on every entry
      point so JDBC on the tick thread fails the build (NFR-2). Ordered shutdown
      drains sched → db → io under a budget (FR-28's executor half). The Paper
      entry point marks the main thread at enable and opens the pools with
      specification defaults until config load is wired.
- [x] **F7** Control plane. Done: `core.control` protocol types (`CommandKind`,
      `NodeCommand`, `CommandHandler`, `CommandResult`, `ControlChannels`) and
      `ControlPlane` (poll + LISTEN, claim/complete, generation discard, unknown
      kind completes with error). `NodeCommandRepository` and
      `PgNotificationListener` stay in `core.db` so JDBC remains confined.
      - Insert and `pg_notify` share one transaction (CP-2 / ADR 0002).
      - Claim is a conditional `UPDATE` with claim-timeout reclaim (CP-5); two
        concurrent claimers never both run the handler.
      - Poll is the contract; killing the LISTEN connection still delivers via
        poll (CP-3). NOTIFY only shortens the wait.
      - No feature handlers yet — those arrive with the milestones that need them.
- [x] **F8** Observability. Done: `core.obs` holds MDC keys/`MdcContext`,
      `EventLogger` over the typed `LogEvent` set (NFR-6), `WorldsMetrics` with
      the §10.2 meter names on a Prometheus registry, `PrometheusEndpoint` on
      loopback:9464 by default, and `CapabilityProbe` (filesystem type, reflink
      verdict via `cp --reflink=always`, free space, optional DB/schema and
      storage health). Paper enable runs the probe and opens the scrape socket.
      Relocated Logstash encoder class names live in `config/logback/`.
- [x] **F9** Test harness. Done: `:testing` holds `TestDatabase` (pinned
      Postgres 18.3), `TestObjectStore` (pinned MinIO + path-style S3 client) and
      `WorldFixture` (synthetic MN-2a Anvil layout). One smoke per CI layer:
      unit (`WorldFixture`), database, object storage in `:testing`; architecture
      remains in the owning modules; MockBukkit plugin-surface smoke in
      `:backend`. `:core` keeps `TestPostgres` to avoid a dependency cycle.
- [x] **F10** CI/CD. Done: five workflows under `.github/workflows/` (`build`,
      `paper-latest`, `e2e`, `release`, `dependency-review`), Renovate with a
      `minecraft-update` group for Paper/Velocity/MockBukkit, licensee licence
      gate on `check`, CycloneDX SBOM on release, and `-PpaperApi=` override so
      the nightly compiles and boots against Paper's newest API/server without
      rewriting the catalog. Branch-protection settings are documented in
      `CONTRIBUTING.md` (GitHub UI; not codable here). The e2e workflow body
      landed with F11.
- [x] **F11** e2e docker compose harness. Done: `e2e/compose.yml` boots Postgres
      18.3, MinIO, Velocity and two Paper nodes; `:e2e-harness` answers `/e2e`
      over RCON and logs join markers; a minecraft-protocol bot joins the lobby
      through the proxy (handshake advertises protocol 776 with 26.1 packet
      schemas). Nightly workflow `e2e.yml` runs `e2e/scripts/run.sh`.
      Acceptance: one player joins a lobby in CI.
- [x] **F12** Durability primitives. Done: `core.storage` holds the reflink
      copier with plain fallback (`ReflinkFileCloner` / `PlainFileCloner`),
      `SnapshotCopier` (post-copy re-stat and bounded retry, MN-5a steps 4–5),
      `RegionStructure` (MN-5c Anvil header/sector/length checks), and
      `ContentHasher` (SHA-256 fused with optional region validation on one
      read, plan §9.1 step 7). Property tests flip every location-table byte on
      a synthetic `.mca` and assert rejection; a mid-copy mutation is detected
      and retried until settle or `UnstableFileException`.

Foundation complete. Spec milestone 1 followed; see the top of this file and
plan [`01-world-lifecycle.md`](01-world-lifecycle.md).

## Open questions, none blocking

Carried in the spec as OQ-13 to OQ-16 so they are not lost.

- [ ] **OQ-13** Replace MN-5b's chunk-save hook with a size and mtime stat walk
      against the last manifest? Same result, no coupling to server internals.
      Needed by milestone 6 (`FileFingerprint` from F12 is the comparison unit).
- [ ] **OQ-14** Retention period for FR-39's captured chat log — 30 days, 90, or
      until the report is marked handled? No longer blocks the schema: V1 carries
      `created_at` and `handled_at`, which is what any of the three answers sweeps
      on, and the period itself belongs in `network_setting` so it can change
      without a migration. Now needed by the F40 maintenance sweep instead.
- [ ] **OQ-15** Confirm the proxy owns the `/world` root and forwards `leave`
      and `report` to the backend. Needed by milestone 5. Milestone 1 kept the
      backend off that root entirely by using `/pworld` (plan 01, D8), so the
      answer is still free.
- [ ] **OQ-17** Does Paper generate the end arrival platform when the
      destination world comes from a plugin rather than from its own portal
      search? Blocking for milestone 1's acceptance; see the checklist above.
- [ ] **OQ-18** Does an ARCHIVED world count against FR-1's per-player cap?
      Implemented as "no", because `/world delete` *is* the archival flow and
      would otherwise never free a slot. Confirm.
- [ ] **OQ-19** `storage.local-scratch-path` is necessarily the server's world
      container — Bukkit cannot create a world anywhere else. Matters beyond
      naming, because MN-13's quarantine and MN-5a's snapshot directory are
      specified relative to it and must share its filesystem.
- [x] **OQ-16** Answered by F4 / ADR 0007: network-wide policy lives in
      `network_setting`, read by both components through `NetworkPolicy`.
      Node-local facts stay in files.
- [ ] **OQ-10, OQ-12** Deployment facts: how many nodes at launch, and does
      MinIO share a host with them?
