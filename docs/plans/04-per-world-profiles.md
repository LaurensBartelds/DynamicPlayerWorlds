# Implementation Plan 04 — Per-World Profiles

Status: implemented; needs a node and a crash test
Covers: spec milestone 4 (§11.4) — FR-14 to FR-17a
Predecessors: plans 01, 02, 03

---

## 0. What this milestone is

A player's inventory, XP, health and effects scoped per `(uuid, world_id)` —
and, more importantly, **the commit model those are written through**.

Spec §11.4 is unusually prescriptive about the order: profiles must be "built
against the snapshot-commit model in FR-15 from the start", because a design
built against an independent autosave timer "will validate a design that
milestone 6 then replaces". So the commit engine lands here, before there is
anything but profiles to put in it.

## 1. Why there is no autosave timer

FR-15a spells out the failure it prevents, and it is worth restating because it
is the only reason the API is shaped this way. With a 5-minute profile autosave
against a 10-minute world sync: a player empties a chest at T+9, the profile
autosave records the items at T+10, the node dies at T+11, and recovery rolls
the world back to T+0 — restoring the chest's contents while the player still
holds them. Run in reverse, it destroys items instead. Every crash produces some
of each.

Committing both through one transaction removes the window rather than narrowing
it. So `ProfileRepository` has no "save one profile" entry point that opens its
own transaction: the write takes a `Connection`, and the caller owns it.

## 2. Single-flight commits

Plan 00 §9 called this a foundation-level shape "because retrofitting it later
means rewriting every caller". `CommitQueue` gives each world a queue where a
commit in flight absorbs further triggers and schedules exactly **one**
follow-up.

Absorbing rather than queueing is the important half: ten players leaving at
once need one commit that captures all ten, not ten commits. And because a
commit captures live state when it runs, one follow-up is enough to guarantee
nothing that happened during the in-flight commit is missed.

A caller waiting on `request` waits for a commit that *started after its call* —
never the one already running, which may have captured state before the change
that prompted the request.

## 3. What milestone 6 adds

The other half of the same transaction: the region-file snapshot, the upload,
and the manifest pointer. Two seams are placed for it and marked:

- `ProfileRepository.commit` is the transaction milestone 6 joins.
- `latestSnapshot` stands in for FR-15b's "the snapshot named by
  `player_world.manifest_key`". Until a manifest exists there is nothing to name
  a snapshot with, and the newest is the only one there is.

`generation` is written as 0 until milestone 7 makes leases real. It is a real
column and a real part of the key; what changes is where the number comes from.

## 4. Known gaps

- **The entry window.** A player is inside the world for the tick or two a
  database read takes, before their profile arrives. FR-11's holding area closes
  it and lands with the transfer path in milestone 5. Until then the inventory is
  cleared on crossing, so the window holds an *empty* inventory rather than the
  wrong one — an empty one can lose nothing into the world.
- **FR-16's "send the player to lobby"** needs the transfer path. A profile that
  cannot be deserialised is refused loudly and nothing is overwritten, but the
  player stays put.
- **FR-16a's admin repair** (`/world admin profile`, rollback) is not built.
  `listSnapshots` is the query it needs and is tested; the command belongs with
  the rest of the admin tree.
- **FR-15c's pruning** exists as `pruneToLatest` but nothing calls it yet: it is
  the FR-40 maintenance job's work.

## 5. The crash test §11.4 asks for

Spec §11.4 is explicit that it must be written against the commit model, and
equally explicit that **on a single node it cannot observe FR-15a's duplication
at all, because both stores fail together**. So the test that matters here is:
play, commit, kill the node, restart, and confirm the world and the profile come
back from the same instant. Re-run as a two-store test in milestone 6.
