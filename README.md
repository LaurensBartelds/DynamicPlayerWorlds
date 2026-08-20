# DynamicPlayerWorlds

Private, per-player survival worlds for the GZMN Minecraft network. A player
creates a world, invites specific people to it, and plays there in isolation
from the rest of the network — players outside a world cannot see, contact or
detect the players inside it.

Live worlds are plain Anvil folders on a node's local disk, treated as a
disposable working copy. S3-compatible object storage is the source of truth at
rest, and PostgreSQL holds metadata, leases and player profiles. Worlds move
between interchangeable `worlds` nodes through object storage; a loaded world
lives on exactly one node at a time, enforced by a database lease.

**Status: pre-alpha.** The specification is complete and the repository
foundation is being built. No gameplay behaviour is implemented yet.

## Components

| Module | Artifact | Runs on | Java |
| --- | --- | --- | --- |
| `:lobby` | `gzmn-worlds-lobby` | Lobby servers (Paper 1.21+) | 21 |
| `:backend` | `gzmn-worlds` | Every `worlds` node (Paper) | 25 |
| `:proxy` | `gzmn-worlds-proxy` | The Velocity proxy | 25 |
| `:core` | shaded into modules | — | 21 |

`:core` holds everything that is not platform-specific: the data model,
database access, the object-storage engine, configuration, menu wire codecs
and the control plane. It has no dependency on Paper or Velocity, which is what
makes it testable without booting a Minecraft server.

`:lobby` is a lightweight, zero-database plugin that acts as a pure inventory
renderer for the network menu GUI (`/world`). It holds no database credentials,
runs no SQL queries, and communicates strictly with Velocity over the `gzmn:menu`
plugin messaging channel. Because it targets Java 21 and Paper 1.21+, it can be
deployed on existing lobby servers without requiring Java 25.

## Documentation

| Document | What it is |
| --- | --- |
| [`docs/spec/v0.4.md`](docs/spec/v0.4.md) | The specification. Requirements are referenced by ID (`FR-15`, `MN-3a`, `CP-2`) throughout the code and in commit messages. |
| [`docs/plans/00-repo-foundation.md`](docs/plans/00-repo-foundation.md) | The foundation plan this repository is currently executing. |
| [`docs/adr/`](docs/adr/) | Decisions that are expensive to reverse, and why they were taken. |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | The rules that are not negotiable. Read before writing code. |

## Building

Requires JDK 25 (Paper 26.x targets 25; see ADR 0003).

```sh
./gradlew build          # compile, check and package everything
./gradlew check          # formatting, static analysis, licence gate and tests
./gradlew spotlessApply  # fix formatting
```

CI mirrors `./gradlew check build` on every push. Nightly jobs also compile and
boot against Paper's newest build (`paper-latest`) and run the compose e2e
harness (`e2e/`, lobby join through Velocity). See `.github/workflows/` and
`e2e/README.md`.

Plugin jars are written to:
- `lobby/build/libs/`: `gzmn-worlds-lobby-<version>+mc<paper-api-version>.jar` (Java 21, ~380 KB)
- `backend/build/libs/`: `gzmn-worlds-<version>+mc<paper-api-version>.jar` (Java 25)
- `proxy/build/libs/`: `gzmn-worlds-proxy-<version>+mc<velocity-api-version>.jar` (Java 25)

The Minecraft version an artifact was built against is in its filename
deliberately: an operator should never have to open a jar to find out.

Paper and Velocity APIs come from `https://repo.papermc.io/repository/maven-public/`,
which must be reachable to build `:backend` or `:proxy`. `:core` and `:testing`
build from Maven Central alone.

## Requirements

- Java 21
- Paper (latest stable) on the `worlds` nodes
- Velocity on the proxy
- PostgreSQL
- S3-compatible object storage (MinIO)

## Licence

AGPL-3.0-or-later. See [LICENSE](LICENSE).
