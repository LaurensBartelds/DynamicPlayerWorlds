# Architecture decision records

One record per decision that is expensive to reverse. Each states the context,
the decision, and what it costs — the last part matters most, because a record
that only lists advantages is advocacy rather than a decision.

Records are immutable once merged. A decision that is later reversed gets a new
record that supersedes the old one, and the old one gains a `Superseded by`
line. The history is the point.

| # | Decision | Status |
| --- | --- | --- |
| [0001](0001-minecraft-version-gating.md) | Minecraft versions are gated in the database | Accepted |
| [0002](0002-control-plane.md) | Control plane is a Postgres command table plus LISTEN/NOTIFY | Accepted |
| [0003](0003-java-and-gradle.md) | Java 21 with Gradle, not Kotlin | Accepted |
| [0004](0004-monorepo.md) | Monorepo for backend, proxy and shared core | Accepted |
| [0005](0005-self-fence-at-lease-expiry.md) | A node self-fences at lease expiry, not after a fixed timeout | Accepted |
| [0006](0006-snapshot-quiesce-verify.md) | Snapshot copies quiesce, snapshot and verify | Accepted |
