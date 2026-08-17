# Working in this repository

Private per-player Minecraft worlds for the GZMN network. Three deployables:
a Paper plugin (`:backend`), a Velocity plugin (`:proxy`), and shared code
(`:core`) that is shaded into both.

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first — it holds the rules, and they
are enforced by the build rather than by review. This file adds only what an
agent session needs on top.

## Orientation

| Path | What is there |
| --- | --- |
| `docs/spec/v0.4.md` | The specification. Requirements have IDs (`FR-15`, `MN-3a`, `CP-2`) used everywhere. |
| `docs/plans/00-repo-foundation.md` | The current plan, with tasks F0–F12 and their acceptance criteria. |
| `docs/adr/` | Why the expensive-to-reverse decisions were taken. |
| `core/` | Data model, database, object storage, config, control plane. No Paper or Velocity. |
| `backend/platform/` | The only package allowed to know Minecraft specifics. |

## Before changing anything

Find the requirement ID that governs it. If the spec does not cover what you
are about to write, that is a finding worth reporting, not a gap to fill
silently — the spec has been through several review passes and a genuine gap in
it usually means the change is larger than it looks.

## Commands

```sh
./gradlew build           # everything
./gradlew check           # static analysis, licence gate and tests
./gradlew spotlessApply   # fix formatting
./gradlew :core:test      # fastest useful loop; :core needs no server
# CI paper-latest override (does not rewrite libs.versions.toml).
# Quote the property on PowerShell so the dots are not split into task names.
./gradlew check build "-PpaperApi=26.2.build.112-stable"
```

`:core` and `:testing` build from Maven Central. `:backend` and `:proxy` need
`repo.papermc.io`, which some sandboxes block — if resolution fails there,
say so rather than working around it.

## What tends to go wrong here

- **Reaching for NMS.** Banned by forbidden-apis. If the API genuinely cannot
  do it, report that; do not add reflection.
- **Doing IO on the main thread.** Banned, and the reason FR-11 is shaped the
  way it is. Every database and storage call is asynchronous.
- **Assuming a `World` reference stays valid.** It does not (FR-25b).
- **Using local time for a lease decision.** Use `DbClock` (MN-10b).
- **Editing a merged migration.** Add a new one instead.
- **Treating `player_world_member.role = 'OWNER'` as authoritative.** It is a
  denormalised convenience; `player_world.owner_uuid` wins (FR-31a).

## Reporting

State plainly what was verified and what was not. A build that could not run
because a repository was unreachable is a useful result; a claim that it passed
is not. Tests that fail get reported with their output.
