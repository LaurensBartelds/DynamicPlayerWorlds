# 0005. A node self-fences at lease expiry, not after a fixed timeout

Status: Accepted (2026-08-17)
Spec: MN-10b, section 9

## Context

Spec v0.3 contained a contradiction. Section 9 said a node whose database is
unreachable "keeps already-loaded worlds playable" and forces an unload only
after `storage.max-sync-failure-minutes`, default 30. MN-10a said a node
self-fences when its heartbeat fails to extend the lease — and the heartbeat
needs the database.

With a 180-second lease, MN-10a fires roughly twenty-seven minutes before
section 9's bound. The 30-minute figure was unreachable for the database case,
so operators would have seen behaviour the document did not describe.

Resolving it required deciding what a node should actually do when it cannot
reach the database, which v0.3 never separated from the case where it has
demonstrably lost the lease.

## Decision

Two cases, treated differently.

**Lease observed lost.** The database is reachable and the heartbeat's
conditional update affected zero rows, so another node holds the lease or the
generation moved. Run MN-10's shutdown path immediately. This is MN-10a as
written.

**Database unreachable.** Ownership is unknown. The node keeps playing and runs
MN-10's shutdown path at the `lease_expires` value returned by its last
*successful* heartbeat, minus `nodes.fence-safety-margin-seconds`.

`storage.max-sync-failure-minutes` now governs only the object-storage failure,
where the lease keeps renewing normally and the local copy stays authoritative —
which is what makes 30 minutes reasonable there and impossible here.

## Rationale

Fencing immediately on an unreachable database buys nothing for integrity. The
node cannot commit without the database (MN-3a), and its uploads are harmless by
construction, since data objects and manifests are write-once (MN-2, MN-3). The
actual risk is divergence: another node takes the expired lease and loads the
world while this one keeps ticking a copy whose progress can never be committed
and whose players are building something that will vanish.

That risk is bounded by giving up before anyone else can take over, which is the
standard rule for a lease client — hold strictly less than the grantor grants.
Subtracting the margin client-side from a deadline the database issued means the
two clocks never have to agree, which matters because node clock drift is
exactly the sort of thing that is fine until it is not.

## Consequences

A database outage ejects players roughly 150 seconds in — five missed heartbeats
at the MN-9 timings — losing at most one sync interval of progress. That is the
same bound as a node crash, which is the bound the design already accepts and
documents.

Quarantining the world's scratch directory afterwards (MN-10) is cheaper than it
sounds: the local object cache is content-addressed and immutable, so it
survives untouched and a later reload of that world is still largely warm.

The margin needs validating at startup, since a value below one heartbeat
interval fences on a single missed beat and a value above the lease fences
immediately and permanently.
