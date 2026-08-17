# Next steps

Short working list. Full detail and acceptance criteria live in
[`00-repo-foundation.md`](00-repo-foundation.md); the specification is
[`../spec/v0.4.md`](../spec/v0.4.md).

F0, F1 and F2 are done. `./gradlew :core:build :testing:build` is green, and
each quality gate has been verified by deliberately breaking it.

## First, in an environment that can reach repo.papermc.io

The sandbox this was built in blocks `repo.papermc.io` (403 on CONNECT), and
Paper and Velocity are not on Maven Central. So `:backend` and `:proxy` are
written but have never compiled. Before anything else:

- [ ] Set `paperApi` and `velocityApi` in `gradle/libs.versions.toml` to the
      versions you actually run. `paperApi` also supplies the `+mc<version>`
      suffix on the plugin jars, so it is the one line that decides what an
      artifact claims to target.
- [ ] `./gradlew build` and fix whatever the two plugin modules turn up. Likely
      candidates: the `Bukkit.getMinecraftVersion()` and `Bukkit.getUnsafe()`
      calls in `ServerIdentity`, and the Velocity `@Plugin` annotation
      processor.
- [ ] Confirm both shaded jars load on a real Paper and Velocity and log
      `enabled`. That is F1's actual acceptance criterion and the only part
      still outstanding.
- [ ] Run `:backend:test` — `ArchitectureTest` there has never executed,
      including the FR-25b rule that no field may hold a `World`.

## Remaining foundation tasks

- [ ] **F3** Database: Flyway, the V1 baseline migration (spec §4 plus
      `data_version`, `node_command`, `player_world_report`, `network_setting`),
      repository skeleton, `DbClock`, Hikari, Testcontainers schema test.
- [ ] **F4** Config: typed node config, `network_setting` accessor with cache
      invalidation, and the startup validations in plan §8.2. Resolve the
      duplicate config keys flagged in spec §7 while doing it.
- [ ] **F5** Minecraft version seam: the `platform` interfaces (`WorldLayout`,
      `ItemCodec`, `WorldRuntime`, `PortalRouting`), and an `ItemStack`
      round-trip test pinning `serializeAsBytes`/`deserializeBytes`.
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
      until the report is marked handled? Needed by F3's baseline migration.
- [ ] **OQ-15** Confirm the proxy owns the `/world` root and forwards `leave`
      and `report` to the backend. Needed by milestone 5.
- [ ] **OQ-16** Move network-wide policy out of `config.yml` into a database
      table read by both components? Needed by F4.
- [ ] **OQ-10, OQ-12** Deployment facts: how many nodes at launch, and does
      MinIO share a host with them?
