# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The Minecraft version an artifact is built against is build metadata, carried in
the jar filename (`+mc<version>`), not part of the project version.

## [Unreleased]

### Added

- Specification v0.4, with Minecraft version gating (section 12.9), the control
  plane (section 13), self-fencing at lease expiry (MN-10b) and the quiesce,
  snapshot, verify procedure (MN-5a, MN-5c).
- Foundation plan `docs/plans/00-repo-foundation.md` covering tasks F0–F12.
- Repository foundation: build, quality gates, module layout, decision records.
- `verifyShadedJar`, a `check` task that fails the build if any class in a plugin
  jar sits outside `nl/gzmn/playerworlds/`. The relocation list is a list, and
  lists go stale; this makes an unrelocated transitive a build failure here
  rather than a classloader conflict on an operator's server.
- Database foundation (F3): the `V1__baseline.sql` migration for specification
  section 4, plus `player_world_report` (FR-39 requires a table staff can read and
  section 4 never defined one) and `network_setting` (network policy in the
  database rather than in each component's config file).
- `core.db`: `Database` on HikariCP with autocommit off, `DbClock` as the only
  sanctioned source of "now" for lease decisions (MN-10b), `AdvisoryLock` for the
  FR-40 single-process election, `Schema` with a version guard that refuses to
  start against a schema newer than the build supports, and `Repository` +
  `RowMapper` as the seam later milestones write their statements into.
- Config foundation (F4): typed `NodeConfig` / `ProxyConfig` /
  `StorageClientSettings`, `NetworkPolicy` (network-wide caps and expiries with
  specification defaults), `NetworkSettings` with a process-local cache and
  invalidation hook, and `ConfigValidator` for the plan §8.2 startup checks.
  ADR 0007 records the split and the three key reconciliations
  (`worlds.storage-path` → `storage.local-scratch-path`; one S3 client with an
  archive-bucket override; one retention count for manifests and profiles).
- Minecraft-version seam (F5): `backend.platform` interfaces and defaults for
  world folder layout (MN-2a), item serialisation (FR-14), world runtime (border,
  save, gamerules, dragon battle) and portal routing (FR-3a), selected by chunk
  data version through `Platform`. ADR 0008 records that item NBT uses Paper's
  `serializeAsBytes`/`deserializeBytes` while `format_version` tags only our
  profile envelope. Build data version pin is 4903 (Paper 26.2-112).
- Threading foundation (F6): `MainThread` guards, `PluginExecutors` topology
  (main / db / io / sched), bounded-operation helper, and ordered executor
  shutdown. `Database` refuses the main thread so a JDBC call on the tick path
  fails tests rather than stalling players (NFR-2).

### Changed

- Target Paper 26.2 (`26.2.build.112-stable`) and Velocity 4.0.0, up from Paper
  1.21.4 and Velocity 3.4.0-SNAPSHOT. No source changes were needed.
- Java toolchain 21 → 25, because `paper-api` 26.x is published with a Java 25
  target and Gradle refuses to resolve it against an older toolchain. See the
  note added to ADR 0003.

### Fixed

- Both plugin jars shipped unrelocated Netty, Apache HttpClient 5, Jackson,
  reactive-streams and HdrHistogram, and a second copy of `org.slf4j` that
  collides with the one Paper and Velocity provide. All transitives are now
  relocated or excluded, and `verifyShadedJar` keeps it that way.
- The S3 client no longer drags in the Netty and Apache HTTP transports; every
  transfer is synchronous work on the bounded `io` executor (NFR-7), so the JDK's
  own transport covers it. Plugin jars are 22 MB rather than 29 MB.
- `com.mojang` is now included in the papermc repository's content filter;
  `paper-api` depends on `com.mojang:brigadier`, which Maven Central does not
  carry, so `:backend` could not resolve at all.
- `plugin.yml` declared `api-version: '1.21'` regardless of what the jar was
  built against. It is now expanded from `paperApi`, the same value that names the
  jar, so the API level Paper applies cannot drift from the one we compiled to.
