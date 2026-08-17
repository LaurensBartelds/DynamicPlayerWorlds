# 0006. Snapshot copies quiesce, snapshot and verify

Status: Accepted (2026-08-17)
Spec: MN-5a, MN-5c

## Context

Uploads must never read a file the server may still be writing. Anvil region
files are mutated in place — a chunk save rewrites the sector header and may
relocate sectors — so reading one during a save yields a torn region: a header
pointing at sectors holding different chunk data. It also breaks the manifest
contract, because the bytes hashed and the bytes uploaded can differ.

Spec v0.3 addressed this with `World#save()` followed by `cp --reflink=auto`.
That is not sufficient. Paper's chunk IO is asynchronous and the server keeps
ticking between the save returning and the copy running, so a region file can be
rewritten *during its own copy*; a reflink copy is not atomic against a
concurrent in-place write. The window is narrower than reading the live folder,
but it is the same failure — and it produces corruption that nothing downstream
can detect, because a torn region hashes and uploads perfectly happily.

## Decision

**Quiesce, snapshot, verify** — the established `save-off` / `save-all flush` /
copy / `save-on` idiom, hardened. Full procedure in MN-5a; the parts that carry
the decision:

1. Disable auto-save on all three dimensions, then save.
2. Wait for quiescence by polling size and mtime across the dirty set until
   nothing changes for a quiet period, bounded by a timeout.
3. Reflink-copy into a per-sync snapshot directory, falling back to a plain copy.
4. Re-stat the sources; anything that moved during its copy is copied again,
   bounded by a retry count. A file that will not settle aborts the sync.
5. Restore auto-save.
6. Validate every `.mca` file structurally while hashing it (MN-5c), and abort
   rather than upload a file that fails.

Restoring auto-save is guarded by a `finally` block **and** an independent
watchdog. An auto-save flag left off means the world never saves again and the
next crash loses everything — strictly worse than the fault being prevented — so
recovery must not depend on the happy path.

## Rationale

The quiet-period wait is an API-only substitute for flushing the chunk IO queue,
which has no stable API. Reaching into internals for it would violate the
no-NMS rule and put a version-fragile dependency at the heart of the durability
path, which is the last place it belongs.

Structural validation is close to free because content addressing already reads
every byte to hash it, so the check rides along on that pass. It is the only
thing standing between the design and silent corruption: a torn region that
reaches object storage cannot be detected later by any other means.

Aborting a sync is cheap — the next one picks the work up, and the bound on data
loss is unchanged. Uploading a torn region is not cheap.

## Consequences

Sync becomes slightly more complex and slightly slower, with a bounded pause in
auto-save around each snapshot. Neither is player-visible.

`cp --reflink=auto` is only close to free on XFS and btrfs; on ext4 it silently
degrades to a full copy of the dirty set. A node probes and logs which behaviour
it actually gets at startup, and the free-space check budgets for the snapshot
directory. Discovering this from a disk-usage graph six weeks in is the
expensive way to learn it.

## Alternatives rejected

**Filesystem snapshots** (btrfs subvolume, LVM, ZFS) are strictly stronger —
genuinely atomic across all files — and would be the choice on bare metal.
Pelican runs nodes in containers, where those operations need host privileges.
Worth revisiting as an opt-in fast path if a node is ever deployed outside a
container.

**Copy-then-verify without quiescing** detects tearing but gives no convergence
bound on a busy world: the retry can lose the race repeatedly, and the sync that
keeps failing is the one carrying the most changes.

**Reading the live folder directly** is what MN-5a already rejects, correctly.
