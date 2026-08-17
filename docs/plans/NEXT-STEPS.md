# Next steps

Short working list. Full detail and acceptance criteria live in
[`00-repo-foundation.md`](00-repo-foundation.md); the specification is
[`../spec/v0.4.md`](../spec/v0.4.md).

F0, F1, F2, F3, F4 and F5 are done. `./gradlew build` is green on all four modules
against Paper 26.2 and Velocity 4.0.0, each quality gate has been verified by
deliberately breaking it, and both plugin jars have been loaded on real servers.

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
- [ ] **Relocating `net.logstash.logback` means a logback configuration must name
      the encoder by its relocated class**, not
      `net.logstash.logback.encoder.LogstashEncoder`. F8 owns that.

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
- [ ] **F6** Threading: executors, main-thread guards, a test proving a JDBC
      call from the main thread fails, ordered shutdown.
- [ ] **F7** Control plane: `node_command`, the `LISTEN` listener with its
      polling fallback, claim/complete/retry (spec §13).
- [ ] **F8** Observability: JSON logging with MDC, Micrometer registry, the
      startup capability probe — including the reflink verdict, which decides
      whether MN-5a's snapshot copy is cheap or a full copy.
- [ ] **F9** Test harness: `:testing` fixtures, Testcontainers factories for
      PostgreSQL and MinIO, one smoke test per layer.
- [ ] **F10** CI: build, nightly `paper-latest`, e2e, release; Renovate with
      Paper and Velocity in their own `minecraft-update` group; SBOM and licence
      check. The nightly job is what makes a Minecraft upgrade cheap — it goes
      red weeks before an operator hits the problem.
- [ ] **F11** *(defer-ok)* e2e docker compose harness.
- [ ] **F12** *(defer-ok, worth pulling forward)* Durability primitives: the
      reflink copier with fallback detection and the region-file structural
      validator (MN-5c). Pure `:core` code, no server needed, and the
      highest-consequence correctness code in the system.

Then spec milestone 1 begins: create a world, materialise its nether and end on
first transit, portal linking both ways, and measure the `createWorld` stall
that sets `worlds.create-stall-budget-ms`.

## Open questions, none blocking

Carried in the spec as OQ-13 to OQ-16 so they are not lost.

- [ ] **OQ-13** Replace MN-5b's chunk-save hook with a size and mtime stat walk
      against the last manifest? Same result, no coupling to server internals.
      Needed by F12 / milestone 6.
- [ ] **OQ-14** Retention period for FR-39's captured chat log — 30 days, 90, or
      until the report is marked handled? No longer blocks the schema: V1 carries
      `created_at` and `handled_at`, which is what any of the three answers sweeps
      on, and the period itself belongs in `network_setting` so it can change
      without a migration. Now needed by the F40 maintenance sweep instead.
- [ ] **OQ-15** Confirm the proxy owns the `/world` root and forwards `leave`
      and `report` to the backend. Needed by milestone 5.
- [x] **OQ-16** Answered by F4 / ADR 0007: network-wide policy lives in
      `network_setting`, read by both components through `NetworkPolicy`.
      Node-local facts stay in files.
- [ ] **OQ-10, OQ-12** Deployment facts: how many nodes at launch, and does
      MinIO share a host with them?
