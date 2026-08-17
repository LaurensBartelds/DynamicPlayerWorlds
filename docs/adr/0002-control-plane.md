# 0002. Control plane is a Postgres command table plus LISTEN/NOTIFY

Status: Accepted (2026-08-16)
Spec: section 13, CP-1 to CP-7

## Context

Section 3.2 puts every management command on the proxy, because a world is
unloaded most of the time and its owner is usually somewhere else on the
network. Some of those commands still need a loaded world to react: ejecting a
banned player, kicking an online member, applying a changed setting, unloading
or migrating a world.

Spec v0.3 said the proxy "notifies the holding node over plugin messaging". A
Velocity plugin message travels inside a player's connection, so it needs a
player connected to the target backend to carry it. Several commands have no
such carrier — `/world admin unload <id>` against a node the caller is not on,
`/world ban` ejecting someone from a world on a different node, `/world set`
applying to a world the owner is not standing in. Under plugin messaging those
succeed in the database and silently never reach the node, which is the worst
available outcome: the operator sees success and the world does not change.

## Decision

Directed commands travel through a `node_command` table in PostgreSQL.

The durable row is the contract. `pg_notify`, issued in the same transaction as
the insert so it cannot be delivered for a row that did not commit, is only a
latency optimisation; nodes also poll for unclaimed rows, because a dropped
`LISTEN` connection loses notifications silently. Claiming is a conditional
`UPDATE`, so exactly one claimer wins, and unclaimed-after-timeout rows are
retried — which makes idempotent handlers mandatory.

Commands carry the lease `generation` where one applies and are discarded if the
world has moved on, the same staleness rejection FR-11 already applies to
`pending_transfer`.

## Consequences

No new infrastructure, no second source of truth, and no new failure domain:
PostgreSQL is already the single linearization point the whole design rests on
(MN-3a), so a control plane that is down is a database that is down, which the
system already has to handle.

Commands survive a restart on either end, which plugin messaging could not offer
at all, and the table is directly inspectable when something does not happen —
a meaningful operational property at three in the morning.

The costs:

- Latency is polling-bounded in the worst case rather than immediate. For
  administrative actions this is irrelevant; it would not be acceptable for
  anything on a player's critical path, and nothing on that path uses it.
- Every handler must be idempotent, because retries are guaranteed rather than
  hypothetical.
- It adds load to PostgreSQL, though at the volume of administrative commands
  this is not measurable.

## Alternatives rejected

**Plugin messaging**, as v0.3 specified: several commands have no carrier and
fail silently. Not a tuning problem; a structural one.

**Direct HTTP or WebSocket RPC between proxy and nodes**, using `node.address`
from the heartbeat row. Lower latency and a natural fit for request/response.
Rejected because it adds authentication, TLS, retry and liveness handling, and
because it creates a second channel that can disagree with the database about
who holds a world. One linearization point is worth more than a few hundred
milliseconds.
