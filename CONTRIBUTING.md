# Contributing

This project runs unattended, 24/7, and holds the only copy of things players
care about. Most of the rules below exist because breaking them loses somebody's
inventory quietly, weeks after the mistake.

Read [`docs/spec/v0.4.md`](docs/spec/v0.4.md) before writing code. Requirements
have IDs — `FR-15`, `MN-3a`, `CP-2` — and those IDs belong in code comments,
test names and commit messages, so that a reader can always get from a line of
code back to the reason it exists.

## The rules that are not negotiable

These are all enforced by the build. If one blocks you and you believe it is
wrong, open a discussion — do not work around it.

**1. No server internals. Ever.**
No `net.minecraft.*`, no `org.bukkit.craftbukkit.*`, no reflection into either,
no Mixins. Internals are the single largest tax on a Minecraft upgrade, and the
cheapest moment to refuse them is before any exist. If something looks
impossible through the API, raise it as an open question rather than reaching
for a reflective hack.

**2. `:core` never depends on Paper or Velocity.**
Not a helper, not one import. The storage engine, lease logic, manifest format,
profile envelope and control plane all live there precisely so they can be
tested without a Minecraft server. Anything genuinely version-sensitive goes
behind an interface in `backend/platform`, which is the only package permitted
to know Minecraft specifics.

**3. No blocking work on the main thread.**
No JDBC, no object storage, no filesystem walks, no HTTP (NFR-2, NFR-7). A JDBC
call from the main thread throws (`MainThread` / `Database`). Use the executors
in `core.concurrent.PluginExecutors` rather than creating your own.

**4. Never cache a `World` reference across an unload.**
Resolve by name or UUID through `Bukkit.getWorld` at use time, every time
(FR-25b). A stale `World` reference is a memory leak and a correctness bug at
once.

**5. Time comes from the database.**
Every safety property in the lease design is a timestamp comparison, and node
clocks drift. Use `DbClock`; `System.currentTimeMillis()` and `Instant.now()`
are banned in lease-adjacent packages. Where a deadline must be evaluated
locally — MN-10b's self-fence — derive it from a `lease_expires` value the
database issued, never from a locally computed one.

**6. Migrations are forward-only and immutable once merged.**
Never edit a merged migration. Add another.

**7. Every write path is idempotent and safe to retry.**
This applies to object storage (NFR-8) and to control-plane handlers (CP-5),
both of which retry on their own.

**8. A destructive path verifies before it destroys.**
Checksums before deleting a source, structural validation before uploading a
region file (MN-5c), typed confirmation before anything a player cannot undo.

## Before you push

```sh
./gradlew spotlessApply   # format
./gradlew check           # static analysis and tests
```

`check` runs Spotless, Error Prone with NullAway, forbidden-apis, the ArchUnit
rules that enforce rules 1, 2, 3 and 5, and the licensee licence gate. It is the
same set the `build` workflow runs, so a green `check` locally means a green CI.

## Commits and branches

- Work on a branch; `main` takes merges only.
- Required status checks on `main` (configure under the GitHub branch-protection
  UI — these cannot live in the repo):
  - `build / check`
  - `dependency-review / review` (pull requests)
  - Prefer **linear history** and **dismiss stale reviews** on force-push.
  - Do not require `paper-latest` or `e2e` on every PR: they are nightly early-
    warning jobs, not merge gates. Treat a red nightly as a bug to fix before
    the next Minecraft bump, not as a blocked merge.
- Write commit subjects in the imperative mood, under 72 characters, with no
  trailing period.
- Reference the requirement ID in the body where one applies. `Implements
  MN-10b` is worth more to the next reader than a restatement of the diff.
- Explain *why* in the body. The diff already says what.

## Tests

- Anything in `:core` gets a unit test; it needs no server, so there is no
  excuse.
- Anything touching the database or object storage gets a Testcontainers test
  against a real PostgreSQL or MinIO. Mocks do not reproduce the conditional
  `UPDATE` semantics the whole design rests on.
- A bug fix starts with a failing test.
- Name tests after behaviour and the requirement:
  `leaseAcquisitionRefusesWorldNewerThanNode_MN26`.

## Adding a dependency

Add the version to `gradle/libs.versions.toml` and nowhere else. Prefer no
dependency: everything added to `:backend` or `:proxy` gets shaded into a jar
that shares a classloader with somebody else's plugin, and every one of them is
a licence obligation under AGPL-3.0 and a thing to upgrade forever.

Then say where its classes go. `verifyShadedJar` fails the build if any class in
a plugin jar sits outside `nl/gzmn/playerworlds/`, so a new dependency needs
either a `relocate(...)` line in `gzmn.plugin-conventions.gradle.kts` or an
`exclude(...)` if the platform already provides it — Paper and Velocity both
ship SLF4J, Netty and Gson, and a second copy of any of those is a classloader
conflict that surfaces weeks later on an operator's server rather than here.
Watch for transitives in particular: they are what the gate exists to catch,
because nobody writes them in a build file and so nobody thinks to relocate
them.

Also check the licence. `licensee` (and the PR `dependency-review` workflow)
fail the build on a transitive whose licence is outside the allow-list in
`gzmn.quality-conventions.gradle.kts`. The repository is AGPL-3.0-or-later;
Apache-2.0 / MIT / BSD / GPL-3.0 family are fine, SSPL / BUSL / proprietary are
not. Paper and Velocity stay `compileOnly` and are not shaded.

## CI overview

| Workflow | When | What |
| --- | --- | --- |
| `build` | every push / PR | `./gradlew check build` |
| `dependency-review` | PRs | vulnerable / disallowed-licence deps |
| `paper-latest` | nightly | compile + boot against newest Paper |
| `e2e` | nightly | compose harness (F11; stub until then) |
| `release` | `v*` tags | jars, CycloneDX SBOM, checksums |

Renovate opens dependency PRs. Paper, Velocity and MockBukkit are grouped as
`minecraft-update` so an MC bump is never mixed into a routine library PR.
