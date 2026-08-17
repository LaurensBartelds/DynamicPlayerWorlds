# 0001. Minecraft versions are gated in the database

Status: Accepted (2026-08-16)
Spec: section 12.9, MN-26 to MN-30

## Context

Section 12 treats the `worlds` pool as interchangeable Paper nodes: any node may
acquire any world's lease and load it. That holds only while every node runs the
same Minecraft version.

Chunk `DataVersion` moves in one direction. A world opened by a newer node is
upgraded in place as its chunks are touched, and no supported path returns it to
the older format. So after a node is upgraded and touches a world, an older node
can no longer open it — but nothing in the design stopped an older node from
acquiring its lease and trying. The outcome is a world that fails to load, or
loads damaged, from a single mis-sequenced node restart.

This matters more than it would elsewhere because the system is required to run
24/7 and to be upgraded to new Minecraft versions easily. Those two goals
together mean rolling upgrades, which means a mixed-version pool, which is
exactly the window in which the bug bites.

## Decision

Version is recorded in the database and checked wherever a node and a world are
matched.

- `player_world.data_version` records the chunk `DataVersion` of the last
  committed snapshot, advanced only by the commit in MN-3a — never
  speculatively, so it always describes durable state.
- Lease acquisition (MN-8) carries the predicate
  `(data_version IS NULL OR data_version <= $my_data_version)`. Zero rows
  affected was already the "could not acquire" path, so this is structurally
  free.
- Placement (MN-15) excludes nodes below the world's version as a hard
  constraint, before any scoring term.
- `player_world_archive.data_version` stamps archives; restore refuses onto an
  older node.
- `mc_version` is stored alongside for humans and never compared. Version
  strings do not order reliably; `DataVersion` does.

## Consequences

Rolling upgrades become safe by construction and need no maintenance window.
Upgrade one node, and the worlds it touches stay away from the older ones. That
is the whole point.

The costs are real and are accepted:

- The first snapshot commit after a world is upgraded rewrites a large fraction
  of its region files, so that sync uploads far more than a normal incremental
  one — potentially the entire world. MN-2b reclaims the superseded objects once
  older manifests age out. Upgrades want a low-traffic window and storage
  headroom.
- Rolling back after worlds have been upgraded strands those worlds until a node
  of the newer version returns. The rollback is safe but not transparent.
- Every place a node and a world meet now has a version check to keep correct.
  The predicate lives in the same statement as the lease, which keeps it atomic
  and hard to forget, but it is one more invariant.

## Alternatives rejected

**Upgrade the whole network in a maintenance window.** Simplest code, and it
conflicts directly with the 24/7 requirement. It also has no partial rollback:
if the new version misbehaves, everything is already on it.

**Assume all nodes always run the same build.** Cheapest now. The failure mode
is silent world damage from an ordinary operational mistake, discovered later,
with no way to recover the lost chunks.
