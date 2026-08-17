# 0007. Configuration is split, and three duplicate keys are one each

* Status: Accepted
* Date: 2026-08-17
* Plan: F4 / section 8.1

## Context

Specification section 7 put nearly all configuration in the backend
`config.yml`, including caps and expiries that the **proxy** is what enforces
(`worlds.max-per-player` at `/world create`, `invites.expiry-minutes` at
`/world invite`, `transfers.expiry-seconds` at handoff). As written, two
components hold private copies of shared policy and can disagree, and in a pool
of interchangeable nodes the nodes can disagree with each other too. That is
OQ-16.

The same sections also name three things twice:

1. `worlds.storage-path` (§7) and `storage.local-scratch-path` (§12.8) — one
   directory.
2. `archive.s3.*` (§7) and `storage.s3.*` (§12.8) — two credential sets for
   what is one S3-compatible service.
3. `profiles.retain-snapshots` (§7) and `storage.manifest-retention-count`
   (§12.8) — described as "aligned", defaulting both to 3. If manifests are
   pruned faster than profiles, a load finds a `manifest_key` whose profiles are
   already gone and issues every player a fresh profile under FR-15b: silent,
   total inventory loss for that world.

## Decision

**Network policy lives in the `network_setting` table.** Caps, expiries,
retention counts, defaults and the in-world command allow-list are one row per
key, read by every component through `NetworkSettings` / `NetworkPolicy`.
Changing a cap does not require a restart; the control-plane
`INVALIDATE_CACHE` command drops each process's cache after a write.

**Node-local facts stay in files**, parsed into `NodeConfig` (backend) and
`ProxyConfig` (proxy): `node.id`, `node.address`, `node.heartbeat-seconds`,
paths, pool size, credentials, lobby server name. They have to be readable
before the database is.

**The three duplicates resolve to one name each:**

| Was | Is now |
| --- | --- |
| `worlds.storage-path` | `storage.local-scratch-path` on `NodeConfig` (node-local) |
| `archive.s3.*` credentials | `storage.s3.*` on `StorageClientSettings`, with optional `archive-bucket` override |
| `profiles.retain-snapshots` **and** `storage.manifest-retention-count` | **only** `storage.manifest-retention-count`, applied to both manifests and profiles |

A leftover `profiles.retain-snapshots` row in `network_setting` is refused at
policy load rather than honoured, so it cannot silently reintroduce the split.

Startup runs `ConfigValidator` against the combination of node config and
network policy (plan section 8.2). Invalid configuration disables the plugin;
it does not run with a "close enough" default.

## Consequences

* OQ-16 is answered: network-wide policy is a database table, not a file.
* The proxy's config file no longer carries `transfers.expiry-seconds`; it reads
  that from `NetworkPolicy` like everything else.
* Operators rotate one set of object-storage credentials, not two. Archives and
  live objects may still use different buckets.
* Profile retention cannot drift from manifest retention because there is only
  one knob.
* Defaults for network policy live in code (`NetworkPolicy.defaults()`), not as
  seeded migration rows, so a fresh database is useful before any admin write
  and a default change is a code change rather than an uneditable migration.
* Every node and the proxy must reach PostgreSQL to know the caps. That was
  already true for membership and placement; configuration is not a new
  dependency.
