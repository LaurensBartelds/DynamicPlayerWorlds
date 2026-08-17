# 0004. Monorepo for backend, proxy and shared core

Status: Accepted (2026-08-16)

## Context

The system ships two plugins that must agree exactly on a database schema and a
control-plane protocol. The proxy writes `pending_transfer` rows and
`node_command` rows that a node reads; both read and write `player_world`. A
version skew between them is not a degraded experience, it is a world that
fails to route or a command that never arrives.

## Decision

One Gradle build containing `:core`, `:backend`, `:proxy` and `:testing`.
`:core` holds the model, database access, storage engine, configuration and
control plane, and is shaded into both plugin jars.

The Pelican panel extension (PHP, optional, v1.1) stays out. Adding PHP tooling
to CI for a component that may never be built is a cost with no current return;
it can join later or live in its own repository.

## Consequences

The schema and the wire protocol cannot version-skew, because there is one copy
of each and one CI run proves both consumers against it. A change to the
protocol lands atomically with both sides of it.

`:core` having no Paper or Velocity dependency is the load-bearing part of the
arrangement. It means the storage engine, lease logic, manifest format and
profile envelope are unit-testable without booting a Minecraft server, which is
where the majority of the correctness risk lives. It is enforced by an ArchUnit
test and by simply not declaring the dependency.

The costs:

- The two plugins release together whether or not both changed. At this size
  that is simpler than coordinating two release streams, but it is a real
  constraint.
- The build is larger than either plugin alone, and a contributor who only cares
  about the proxy still clones everything.
- Building `:backend` or `:proxy` needs `repo.papermc.io`; `:core` and
  `:testing` build from Maven Central alone, which turns out to matter in
  restricted environments.

## Alternatives rejected

**Separate repositories per component.** Independent release cadence, at the
price of publishing the schema and protocol as a versioned artifact from day one
and then living with the skew that inevitably follows. The coupling is real, so
it is better represented than hidden.
