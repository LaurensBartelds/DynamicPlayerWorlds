# Implementation Plan 05 — Audit Remediation

Status: in progress — Phase A complete (R0–R5). Next is Phase B starting at R6.
The e2e suite runs with object storage enabled and is 9/9 green across
consecutive runs.
Covers: the defects found by the intent and behaviour audit of milestones 1–8,
and the requirements those defects turned out to have left unimplemented
Spec baseline: `docs/spec/v0.4.md`
Predecessors: `00-repo-foundation.md` (F0–F12), `01-world-lifecycle.md`,
`02-membership-and-invites.md`, `03-visibility-isolation.md`,
`04-per-world-profiles.md`, and the milestone-8 record in `NEXT-STEPS.md`

---

## 0. What this plan is

Not a feature milestone. Every task below closes a gap between a requirement
already in `docs/spec/v0.4.md` and what the code does today. Nothing here needs
a new requirement, with the four exceptions in §4, and those four are spec
*clarifications* the audit forced into the open rather than new scope.

The audit found four shapes of defect, and the shape matters more than the
individual bugs because each one recurs:

1. **Wired-up but not wired-in.** A class implements a requirement completely,
   has a passing test suite, and is never constructed. `CommandGuardListener` is
   the extreme case: FR-21 and FR-22 do not run on a live node at all.
   `LocalObjectCache.evictLru`, `QuarantineManager.prune` and
   `ProfileRepository.pruneToLatest` are the same shape with less blast radius.
2. **Invalidate with no fill.** Three caches are populated at exactly one
   lifecycle point and evicted from several others, and two of them return a
   *wrong answer* on a miss rather than a miss. `INVALIDATE_CACHE` therefore
   silently demotes a loaded world's owner to VISITOR.
3. **Verify-then-destroy, inverted.** CONTRIBUTING rule 8 says a destructive
   path verifies before it destroys. Archival compares a length and then deletes
   the only remaining copy of a world.
4. **Fire and report success.** The control plane writes a `result` column that
   nothing reads, so `STALE_GENERATION`, `UNKNOWN_COMMAND` and
   `no handler for X` — the three outcomes CP-6 designed specifically to be
   visible — are invisible.

The ordering in §2 follows from that: fixes that stop data loss first, fixes
that stop *silent* data loss immediately after, and the structural work that
stops each shape recurring folded into the phase that first needs it rather than
deferred to a cleanup pass that never happens.

### R0 — The world layout is wrong for the targeted Paper build

**Requirement:** MN-1, MN-2, MN-2a, MN-3, NFR-9, FR-35. **Confidence: HIGH,
reproduced on a live node.** Found while verifying R2/R3, not by review.

`DefaultWorldLayout` places a Bukkit world's files at
`<worldContainer>/<folder>`, with the nether and end as sibling folders
(`<folder>_nether/DIM-1`, `<folder>_the_end/DIM1`) and a `level.dat` at each
root. MN-2a documents that layout explicitly.

Paper 26.2 — the version this build pins and the one the e2e stack runs — does
not use it. A world created as `pw_<hex>` is stored at:

```
/server/world/dimensions/minecraft/pw_<hex>/
  region/  r.-1.-1.mca  r.-1.0.mca  r.0.-1.mca  r.0.0.mca
  entities/
  data/
  paper-world.yml          <- and no level.dat
```

while every consumer of the layout looks in `/server/pw_<hex>`, which does not
exist. Observed consequences, all live:

- **Object storage holds no world data.** `DirtyScanner` walks a path that is
  not there, so the dirty set is always empty and `SnapshotEngine` writes a
  manifest with `"entries": {}`. The committed manifest for a real, played world
  was 204 bytes with zero entries, and the bucket held nothing else — no
  `data/` objects at all. The commit still advances `manifest_key`,
  `data_version` and `storage_bytes = 0`, so it looks entirely successful.
- **NFR-9 is inverted.** "Wipe a node's scratch directory and reload" would
  restore an empty world over a good one. The property the design calls
  testable currently fails in the most destructive possible way.
- **Archival cannot run.** `ARCHIVE_WORLD` completes
  `ERROR:No world dimensions found to archive`, which is what scenario 09 hits.
- **MN-13's quarantine never fires**, because it looks for `pw_*` directories in
  the scratch root.
- `worldRootFiles()` requires `level.dat`, which this layout does not put in the
  dimension folder, so `dimensionsOnDisk` would find nothing even with the base
  path corrected.

Why nothing caught it: the harness ran with `storage.s3.enabled: false`, so no
scenario ever exercised a snapshot; and the storage unit tests build their
fixtures with `WorldFixture.materialize(scratchRoot)`, which *creates* the
legacy layout by hand. The tests assert the layout the code assumes rather than
the one the server produces — the same shape as §0's defect 1.

**This is what `WorldLayout` and ADR 0001 exist for.** The seam is right; it is
pinned to the wrong layout. The fix is a new `WorldLayout` for the Paper 26.x
storage scheme, selected by data version, plus a `dimensionsOnDisk` root-file
check that matches it. MN-2a needs rewriting to describe both layouts and to say
which data version each applies from.

Sequenced ahead of Phase A: R2's checksum gate and R3's handoff check are both
correct and both currently unreachable, because archival cannot get past step 3.

**Landed** (commit `fe9142c`, by the repository owner with Junie): the layout,
commit, archive and quarantine paths were aligned on Paper 26's nested scheme,
and `RegionStructure` was relaxed to accept the unpadded trailing sector a live
`.mca` carries.

Three further defects sat behind it, each of which only became reachable once
the previous one was fixed. All three are now fixed and the archival scenario
passes end to end.

#### R0a — a file that vanished between the scan and the copy aborted every sync

`SnapshotCopier.copyOne` threw `StorageException("source missing…")` when a path
named by the dirty scan no longer existed. Paper writes and removes transient
files under `data/` around every save — `chunk_tickets.dat` is the one that
showed up — so in practice **no snapshot ever completed**: the manifest stayed
empty and the commit failed with the world already saved.

MN-5a's "abort this sync" rule is about a file that will not *settle*, which is
a torn-read risk. A file that no longer exists carries no such risk; it is
simply not part of the state the snapshot describes. `copyOne` now returns
`null` for a vanished source and `copyAll` omits it. After the fix the live
prefix went from 1 object (the empty manifest) to 8–15 real objects.

#### R0b — zstd cannot be used from a relocated plugin jar

`archive.compression` defaulted to `zstd-3`, and zstd-jni is a native library
whose JNI entry points are bound to `com.github.luben.zstd`. The plugin jar
relocates every dependency, so the first archive threw
`UnsatisfiedLinkError` out of a static initialiser. **Archival never worked with
the documented default**, on any server.

The default is now `gzip` — in the JDK, no native component — and an explicit
zstd request fails with a message naming the relocation rather than a JNI stack
trace. The specification itself doubts the codec's value here ("region files are
already zlib-compressed internally… several times the CPU for a few percent of
size"), so this is close to free. **Spec §7 and §12.8 need the default changed;
see §4.** Removing the dependency altogether is the tidier end state and is not
done here.

#### R0c — an `Error` in a control-plane handler killed the LISTEN thread

`ControlPlane.completeWithHandler` caught `Exception`. R0b's
`UnsatisfiedLinkError` is an `Error`, so it escaped: the command was left
claimed and never completed — retried forever after
`control.claim-timeout-seconds` — and the throw propagated out of the dispatch
loop and **killed the `gzmn-backend-listen` thread**, silently reducing the node
to CP-3's poll fallback for the rest of its life.

Both the dispatch and the LISTEN loop now catch `Throwable`. CP-6 wants a
failing command to "degrade visibly instead of stalling the queue", and that has
to hold for an `Error` too.

---

### Task index

| Phase | Tasks | Theme |
| --- | --- | --- |
| A | R1–R5 | Data loss and access control. No open decisions. **R1, R4 done.** |
| B | R6–R10 | The commit path: what reaches durable storage, and when. |
| C | R11–R15 | Lease and lifecycle hygiene. |
| D | R16–R20 | FR-40: the maintenance job the system has been running without. |
| E | R21–R23 | Storage-model correctness. |
| F | R24–R28 | Reporting, messaging, and de-duplication. |

---

## 1. Decisions taken before writing code

### D13 — Caches become load-through; `invalidate` stops being a public verb

`MembershipCache` and `WorldSettingsCache` are read on the tick thread and so
cannot query (NFR-2). That is why they exist. What was never decided is what a
miss *means*, and today it means two different wrong things: `effectiveRole`
answers VISITOR and `WorldSettingsCache.get` answers `WorldSettings.defaults()`.
Both are defensible as the safe direction for a transient miss on a world nobody
has loaded. Neither is defensible for a world that is loaded and being played
in, which is exactly the state `INVALIDATE_CACHE` leaves them in.

So both caches gain a loader — `WorldCacheLoader` — with a `refresh(WorldId)`
that re-reads the authoritative rows and `put`s the result. `invalidate`
survives only on the unload path, where absence is correct because the world is
gone.

As implemented, the refresh runs **inline on the control-plane handler thread**
rather than being dispatched to the db executor. That thread is already off the
tick thread, so NFR-2 is satisfied, and CP-5 makes a completed command mean the
effect has happened: a refresh handed to another executor would let the
`node_command` row be marked complete while the node was still answering from
the old membership.

The alternative — leaving invalidate-only and making every producer follow it
with a fill — was rejected because there are six producers today (`kick`, `ban`,
`promote`, `public`, `set`, and the eject handler) and the seventh will forget.

### D14 — Permission is a property of the action, not of the command syntax

Today `gzmn.worlds.public`, `gzmn.worlds.create` and `gzmn.worlds.join` are
enforced in Brigadier `.requires(...)` clauses, so the GUI path
(`MenuChannelListener` → `WorldActions`) bypasses all three. FR-9h and OQ-7 are
emphatic that the public toggle is ungranted by default *because* of what a
public world does to a node's blast radius, and a second entry point that
ignores it is not a gap in the GUI — it is the gate not existing.

Checks move into `WorldActions`. `.requires(...)` stays, demoted to what it is
good at: hiding a subcommand from tab completion for someone who cannot use it.

This also settles a live inconsistency nobody has had to name yet:
`hasPermissionOrDefault` treats Velocity's `UNDEFINED` as **allowed** while
`source.hasPermission` treats it as **denied**, and both are used in the same
command tree. One semantic, chosen deliberately, applied in one place.

### D15 — A control-plane handler never runs on the thread its own continuations need

`ControlPlane.start(pools.sched(), listen)` schedules the CP-3 poll onto the
single-threaded `sched` pool, and `MigrateWorldHandler` blocks on a future that
`WorldHandoff.countdown` completes *via that same pool*. While the LISTEN
connection is healthy the notification path dispatches first and this never
fires. When it is not — the exact case CP-3 says the poll exists for — the
handler deadlocks `sched` until its budget expires, taking the lease heartbeat,
the fencing watchdog, the periodic sync and the maintenance sweep with it.

`ControlPlane` gains a handler executor, defaulting to a small dedicated pool.
The poll and LISTEN loops keep their threads for claiming; handlers get their
own. This is a `:core` change with no Minecraft in it and is unit-testable.

### D16 — A manifest is the complete file set, not a cumulative overlay

`SnapshotEngine` builds `newEntries` from `baselineEntries` and only ever `put`s
into it, so an entry can never leave a manifest. A file deleted from a world
folder is resurrected by the next cold load, and MN-2b can never collect its
object because a retained manifest still references it.

No format change is needed and MN-3 does not move: the manifest already *claims*
to describe the world's state, and `DirtyScanner` already walks the whole tree,
so it can return the observed path set alongside the dirty subset at no extra
cost. `newEntries` is then built from the observed set — baseline entry where
unchanged, new entry where dirty — and deletions fall out.

`WorldDownloader.materialize` gains the mirror half: a file under the world's
folders that the manifest does not list is removed, so a materialised world
matches its manifest rather than being a union with whatever was there before.

### D17 — A restore carries its profiles forward

FR-36 does not say what happens to `player_world_profile` rows across an
archive and restore round trip, and the implementation answers "nothing" — which
under FR-15b's snapshot keying means every member's inventory becomes
unreachable the moment a world is restored. FR-31's reasoning for a transfer —
the world's id never changes, so "profiles, bans and members survive intact" —
is the model the rest of the system is built on, and archival changes the id no
more than a transfer does.

`completeRestore` therefore re-keys the newest surviving profile snapshot onto
the restore's `(generation, sequence)`, inside the transaction that moves
`manifest_key`. Same one-transaction rule as MN-3a, same reason. §4 records the
sentence FR-36 needs.

### D18 — MN-4's completion marker is what reconciles MN-13 with MN-5

MN-13 says quarantine every scratch directory not covered by a lease this node
holds. MN-5 says local files are retained as a warm cache. MN-15a then scores a
warm copy at ten thousand points — dominating every other placement term — from
`player_world.last_node`. Today `sweepStartup` is called with an empty lease set,
so a planned restart quarantines the node's entire working set, while
`last_node` still names it, so placement keeps routing joins to the node whose
warm copy it just destroyed; and MN-13a's bound on quarantine size is not
implemented, so the disk fills.

MN-4 already specifies the missing piece and it is unimplemented: *"a clean
unload writes a completion marker, and a world whose marker is absent is fully
rehashed before use."* Extend it by one field. On clean unload, after the final
commit, write a marker naming the `manifest_key` that was just committed. At
startup:

- marker present and naming the world's current `manifest_key` → **warm cache**,
  left alone;
- marker absent, or naming a different manifest → **crash debris**, quarantined
  per MN-13.

That is the distinction MN-13 was reaching for. "Not covered by a lease" is a
proxy for "may have diverged" that stops being accurate the moment a clean
shutdown releases its leases. §4 records the MN-13 sentence this needs.

---

## 2. Why this order

**Phase A** is everything losing data or letting somebody through a gate right
now, and none of it depends on an open decision. It should land first even if
the rest of this plan is renegotiated.

**Phase B** is the commit path. Second, because Phase C's lease work and Phase
D's pruning both assume commits are trustworthy, and because R6 and R7 are the
difference between "one sync interval of loss on a fault" — which FR-15 accepts
and documents — and unbounded silent loss, which it does not.

**Phase C** is lease and lifecycle. After B, because R12's lease release on a
failed load is only safe once R7 and R8 have stopped a failed commit from
fencing and quarantining the node.

**Phase D** is FR-40. Fourth: the most work and the least urgency per task —
nothing in it is losing data today, it is filling disks — and R16 depends on
D18's marker, which arrives with R21.

**Phases E and F** can interleave with D. Neither blocks anything else.

---

## 3. Tasks

Each task names the requirement it closes, the failing test that starts it
(CONTRIBUTING: *a bug fix starts with a failing test*), and its acceptance
condition. File lists are indicative, not exhaustive.

### Phase A — data loss and access control

#### R1 — Register `CommandGuardListener` — **DONE**

**Requirement:** FR-21, FR-22.
**Files:** `backend/GzmnWorldsPlugin.java`, `backend/PluginSmokeTest.java`.

The class is complete and tested and is never constructed. Build it beside
`RoleEnforcementListener`, sharing the `VisibilityGroups` instance already
created for `VisibilityListener`, and register it.

Then close the shape rather than the instance. `PluginSmokeTest` already enables
the plugin under MockBukkit, so it can scan `nl.gzmn.playerworlds.backend` for
every class implementing `org.bukkit.event.Listener` and assert each appears in
`HandlerList.getRegisteredListeners`. An unregistered listener is invisible to
every other kind of test by construction, which is exactly why this one survived
three milestones.

**Failing test first:** `everyListenerInTheBackendIsRegisteredAtEnable`.
**Acceptance:** the new assertion fails on `main` and passes after registration;
`/list` inside a player world is refused for a non-admin.

**Landed.** `GzmnWorldsPlugin` now builds one `VisibilityGroups` and shares it
between `VisibilityListener` and `CommandGuardListener`. The guard was verified
by breaking it, not by grep: with the registration disabled,
`everyListenerInTheBackendIsRegisteredAtEnable` fails naming
`nl.gzmn.playerworlds.backend.world.CommandGuardListener`, and e2e scenario 08
times out waiting for the FR-22 refusal of `/list`. With it, both pass and a bot
inside its own world is told *"That command is not available inside a player
world."*

#### R2 — Verify the archive's checksum before deleting anything — **DONE**

**Requirement:** FR-35, CONTRIBUTING rule 8.
**Files:** `backend/storage/WorldArchiver.java`, `backend/storage/ArchiveStorage.java`.

Step 6 checks `exists` and compares `getArchiveSize` to the local packed size;
steps 7 and 8 then delete all three live dimension folders, the per-world data
prefix and the manifest prefix. The sha256 in `packResult.checksum()` is computed
over the local temp file and never compared against what object storage holds.

Add a real verification and gate every deletion on it. Two acceptable
implementations, in preference order:

1. **Ask the store.** Set `ChecksumAlgorithm.SHA256` on the `PutObject` and
   compare the returned `checksumSHA256`. No second transfer, and both S3 and
   MinIO provide it.
2. **Read it back.** Download to a temp file and run
   `ArchivePacker.verifyChecksum`, which the restore path already uses correctly.

On failure: delete the uploaded object, leave the world untouched, return
`ArchiveResult.error`. The world stays READY and FR-40's sweep retries it, which
is exactly the crash contract FR-35 describes.

**Failing test first:** a Testcontainers MinIO test that corrupts the stored
object after upload while preserving its length, asserts archival fails, and
asserts the three dimension folders still exist.
**Acceptance:** no path reaches `deleteDirectoryRecursively(baseDim)` or
`objectStore.deletePrefix` without a checksum comparison having returned true.

**Landed.** `ArchiveStorage.verifyStoredArchive(key, expected)` re-reads the
stored artefact and hashes it — locally for the filesystem backend, via a
download for S3. The read-back costs one extra transfer of an archive that was
just uploaded; an S3-only fast path using `ChecksumAlgorithm.SHA256` on the
upload would avoid it, but `ObjectStore` cannot carry a checksum today and
widening `:core`'s interface for one caller was not worth it.

Verified by breaking it: with the gate removed,
`WorldArchiverTest#archiveRefusesWhenStoredArchiveIsCorrupt` fails. It drives a
`CorruptingObjectStore` that flips one byte of every upload while preserving the
length — the failure a size comparison cannot see — and asserts the live folders,
the world state and the archive row are all untouched.

#### R3 — Archival aborts when the handoff did not release the world — **DONE**

**Requirement:** FR-35, MN-5a.
**Files:** `backend/storage/WorldArchiver.java`, `backend/control/WorldHandoff.java`,
`backend/world/WorldLifecycleService.java`.

`archiveWorld` calls `handoff.release(...).get(...)` and discards the `Outcome`
entirely, so `Blocked` (a dimension refused to unload) and `CommitFailed` (the
world deliberately stays loaded and leased) both fall through to packing a live,
ticking world folder with `ArchivePacker.pack` — bypassing the whole of MN-5a —
and then deleting it from under three loaded Bukkit worlds.

Switch on the outcome. Only `Released` and `NotHeld` continue; anything else
returns `ArchiveResult.error` and leaves the world alone.

The same task fixes the lease race underneath it. `afterUnload` releases the
lease asynchronously on the db executor, so the archiver's `acquireLease` two
lines later usually loses to a lease that has not been dropped yet — and the
code then treats "the holder is me" as good enough and proceeds *unleased*,
while the queued release fires mid-pack. Either make the release synchronous
with the outcome, or have `Outcome.Released` carry the generation it released so
the archiver can acquire deterministically. The second keeps `afterUnload` off
the critical path and is preferred.

**Failing test first:** a handoff stub returning `CommitFailed`; assert no
packing occurs and the folders survive.
**Acceptance:** FR-35's "acquires the lease first" holds across the whole
pack–upload–verify–delete sequence, not just its first statement.

**Landed.** The archiver now switches on the `WorldHandoff.Outcome` and returns
an error for `Blocked` and `CommitFailed` instead of packing a live world. The
lease race is closed the way the plan preferred: `afterUnload` returns a
`CompletableFuture` and `WorldHandoff` waits for it before completing
`Outcome.Released`, so the outcome's name is true and the archiver's own
`acquireLease` is not racing a queued release.

Verified by breaking it: restored to log-and-continue,
`WorldArchiverTest#archiveRefusesToPackAWorldItCouldNotGiveUp` fails.

#### R4 — `INVALIDATE_CACHE` refreshes instead of evicting (D13) — **DONE**

**Requirement:** FR-9, FR-9e, FR-31a, CP-6.
**Files:** `backend/world/MembershipCache.java`,
`backend/world/WorldSettingsCache.java`,
`backend/control/InvalidateCacheHandler.java`,
`backend/control/EjectPlayerHandler.java`.

Give both caches a loader and a `refresh(WorldId)` that re-reads on the db
executor and `put`s. `InvalidateCacheHandler` and `EjectPlayerHandler` call
`refresh` for a world-scoped command and `refreshAll` for a global one; the
unload path keeps `invalidate`.

Keep FR-31a explicit through the change: the refill reads `player_world.owner_uuid`
and `player_world_member` together, and `owner_uuid` still wins.

**Failing test first:** load a world, cache membership, dispatch
`INVALIDATE_CACHE`, assert the owner still resolves to `Role.OWNER` and the
world's stored settings still resolve rather than `WorldSettings.defaults()`.
**Acceptance:** `/world promote`, `/world kick`, `/world ban`, `/world public`
and `/world set` against a **loaded** world leave the owner able to build and
the world's configured container rule intact.

**Landed.** The fill half is a new `WorldCacheLoader` in `backend.world`, which
both handlers call and which holds FR-31a's precedence in one place. The refresh
runs inline on the handler thread rather than being dispatched to the db
executor: CP-5 makes a completed command mean the effect has happened, and a
refresh dispatched elsewhere would let the row complete while the node still
answered from the old membership.

Two of `BackendControlHandlersTest`'s assertions had to be rewritten because
they asserted the defect — `assertEquals(0, membershipCache.size())` after an
invalidate was the bug, stated as a requirement. They now assert that a
promotion is visible and that the owner is still OWNER, and that eviction
happens only when the row is gone.

Verified end to end by e2e scenario 08 against a deliberately reverted build:
Alice breaks a block, runs `/world set pvp on`, and is then refused
server-side. With the fix, both breaks succeed.

#### R5 — Permission checks move into `WorldActions` (D14) — DONE

**Requirement:** FR-1, FR-9h, FR-10, FR-27, FR-37, OQ-7.
**Files:** `proxy/permission/WorldPermissions.java`,
`proxy/command/WorldActions.java`, `proxy/command/WorldCommand.java`,
`proxy/menu/MenuChannelListener.java`, `backend/gui/screen/ConfirmMenu.java`.

**Landed.** Decision confirmed by owner: `UNDEFINED` is **deny**, except
`gzmn.worlds.create` and `gzmn.worlds.join` which ship granted by default.
`gzmn.worlds.public`, `gzmn.worlds.admin` and `gzmn.worlds.delete.hard` stay
ungranted. One helper (`WorldPermissions.allows`) is the single semantic.

`WorldActions` now checks permission on `create`, `join`, `accept`, `browse`,
`setPublic` and `deleteHard` before any side effect. Brigadier `.requires(...)`
remains only as a completion hint (`WorldCommand.maySee`). The GUI path that
previously bypassed FR-9h entirely
(`MenuChannelListener` → `SetVisibility` → `setPublic`) now returns
`PERMISSION_DENIED`.

`ConfirmMenu` documents and tests the FR-27 / FR-37 equivalence: only
`SLOT_CONFIRM` runs `onConfirm`; filler and cancel do not. That is what
authorises `MenuIntent.ArchiveWorld` / `HardDeleteWorld` with `confirmed = true`.

**Failing test first (proven by temporary revert):**
`MenuChannelListenerTest#setPublicViaMenuRefusesCallerWithoutPublicPermission_FR9h_R5`
— without the `WorldActions.setPublic` gate the menu path returns `Ok` and the
world becomes PUBLIC; with the gate it returns `Failed(PERMISSION_DENIED)` and
visibility stays PRIVATE.
**Also:** `WorldPermissionsTest` (UNDEFINED / FALSE / TRUE matrix),
`WorldActionsTest#setPublicRefusesWithoutPublicPermission_FR9h_R5`,
`CoreScreensTest#confirmMenuIsTypedConfirmationSubstitute_FR27_FR37_R5`.

### Phase B — the commit path

#### R6 — Departing profiles survive a failed commit

**Requirement:** FR-15, FR-15a, FR-16.
**Files:** `backend/profile/WorldCommitService.java`.

`phase1MainThread` does `pendingDepartures.remove(worldId)` and folds the
payloads into the in-flight commit. If phase 2 or 3 throws — a torn region, an
`UnstableFileException`, an object-storage outage, a `commitSnapshot` that
returns false — those payloads are gone, and the next commit cannot re-capture
them because the player has left and `captureWorld` will not see them.

Take a copy in phase 1; remove only on success in `phase4Completion`; on failure
merge back (`merge`, not `put`, so a departure recorded during the failed commit
wins). This is the difference between FR-15's documented bound — one sync
interval, for world and profile *together* — and losing a profile while the
world rolls back, which is FR-15a's duplication window with the halves swapped.

**Failing test first:** `departingProfileSurvivesAFailedCommit_FR15`.
**Acceptance:** a commit that throws in phase 2 leaves `pendingDepartures`
non-empty, and the next commit writes the profile.

#### R7 — A fenced world stops committing, and stops re-uploading itself

**Requirement:** MN-10, MN-10a, MN-13.
**Files:** `backend/lease/SelfFencingHandler.java`,
`backend/profile/WorldCommitService.java`, `backend/profile/ProfileListener.java`.

`selfFence` unregisters the world and calls `commitService.forget(worldId)`,
then teleports every player out on the main thread. That teleport raises
`PlayerChangedWorldEvent`, `ProfileListener` answers with `commitDeparture`, and
`commitDeparture` recreates the queue entry `forget` just dropped. Because
`forget` also cleared `cachedManifests`, the new commit's baseline is null, so
`DirtyScanner` reports the entire world as dirty and `SnapshotEngine` re-copies,
re-hashes and re-uploads all of it — from a scratch directory that
`QuarantineManager.quarantineWorld` is concurrently moving on the io executor.
Phase 3 then falls back to `generation = 0`, `commitSnapshot` returns false, and
`selfFence(COMMIT_FENCED)` fires again.

Add a fenced-world set to `WorldCommitService`, consulted at the top of
`requestCommit` and `commitDeparture`, returning an already-failed future rather
than starting work. `forget` populates it; a successful load clears it.

**Failing test first:** `fencedWorldRefusesFurtherCommits_MN10a`, asserting no
`SnapshotEngine` interaction after `selfFence`.
**Acceptance:** a fence with players inside performs zero uploads and exactly
one quarantine move.

#### R8 — `generation` stops doubling as a "not found" sentinel

**Requirement:** MN-3, MN-3a.
**Files:** `backend/profile/WorldCommitService.java`.

`phase2IoThread` uses `generation = 0L` for both "created without a lease" and
"the registry does not know this world", and the second routes into a
`commitSnapshot` that returns false and a `selfFence(COMMIT_FENCED)` that
quarantines the scratch directory — a destructive response to a benign cause.

Make the absence explicit: resolve to an `OptionalLong` (or fail fast) and treat
"no generation" as *abort the commit*, not as generation zero. With R7 in place
the case should be unreachable; it should still not be silently wrong when it is
reached.

**Failing test first:** `commitWithoutARegisteredWorldAbortsRatherThanFencing`.
**Acceptance:** `COMMIT_FENCED` is raised only for a genuine MN-3a outcome.

#### R9 — Implement `APPLY_SETTINGS`

**Requirement:** FR-9e, CP-6, §6.
**Files:** new `backend/control/ApplySettingsHandler.java`,
`backend/GzmnWorldsPlugin.java`, `proxy/command/WorldActions.java`.

`CommandKind.APPLY_SETTINGS` is declared and has no handler anywhere, so if it
were sent, a node would complete it `no handler for APPLY_SETTINGS`. Meanwhile
`runtime.setPvp` and `runtime.setMobGriefing` are called only from
`applySettings` at load time, so `/world set pvp on` reports success and changes
nothing on a loaded world.

The handler re-reads `player_world.settings`, refreshes `WorldSettingsCache`
(R4), and re-applies PVP and the mob-griefing gamerule on the main thread across
all materialised dimensions. `WorldActions.setSetting` sends it instead of
`INVALIDATE_CACHE`.

**Failing test first:** `applySettingsChangesPvpOnALoadedWorld_FR9e`.
**Acceptance:** every setting FR-9e names takes effect on a loaded world without
an unload.

#### R10 — Decide what `latestSnapshot` is for

**Requirement:** FR-15b, §7's retention warning.
**Files:** `backend/profile/ProfileListener.java`, `backend/profile/WorldCommitService.java`.

`enter` falls back to `repository.latestSnapshot(worldId)` when `manifest_key`
is null or unparseable. FR-15b names `manifest_key` as *the* source, and the
fallback reads whichever profile snapshot is newest regardless of which world
state is on disk — FR-15a's skew, reintroduced.

The fallback is load-bearing for the no-object-storage mode, where profiles are
committed at generation 0 and `manifest_key` is null. Scope it there: fall back
only when `manifest_key IS NULL`, and treat "manifest key present, no profile
row for its snapshot" as FR-16's refusal (R11) rather than FR-5's fresh profile.
§7 names this exact failure — *"silent, total inventory loss for that world"* —
as the thing the retention validation exists to prevent, and the
storage-enablement transition produces it anyway.

Add the one-shot migration for that transition: when a world's first snapshot
commit lands and generation-0 profile rows exist for it, re-key them onto the
new snapshot in the commit transaction. Same mechanism as D17.

**Failing test first:** `enablingObjectStorageDoesNotOrphanGenerationZeroProfiles`.
**Acceptance:** no path issues a fresh profile to a player who has a stored
profile for that world under any snapshot.

### Phase C — lease and lifecycle hygiene

#### R11 — FR-16's refusal sends the player to lobby

**Requirement:** FR-16, FR-16a.
**Files:** `backend/profile/ProfileListener.java`.

`enter` clears the inventory first, then on an unreadable profile tells the
player *"Nothing has been overwritten"* and leaves them standing in the world
with an empty inventory. FR-16 requires the opposite: *"sending the player to
lobby with an error rather than granting an empty inventory."* The javadoc
defers it to milestone 5's transfer, which shipped — `TransferJoinListener.refuse`
does exactly this, four files away.

Reuse that path: enqueue `EJECT_PLAYER` to the proxy and correct the message.
Move the `applyFresh` clear to *after* a successful read, so the FR-16 case
never touches the player's state and the message stops being false.

**Failing test first:** `unreadableProfileEjectsRatherThanClearing_FR16`.
**Acceptance:** an undecodable profile produces an eject command and no
inventory mutation.

#### R12 — Release the lease on every failed load

**Requirement:** MN-8, MN-12, FR-11.
**Files:** `backend/world/WorldLifecycleService.java`, `proxy/command/WorldActions.java`.

`doJoin` acquires the lease on the proxy before routing, and `readForLoad`
acquires it on the node. Neither releases it on any terminal failure —
`NodeFull`, `TooNew`, `Failed("could not materialize world from storage: …")`,
or a dimension that would not load. The world stays leased for the full
`nodes.lease-seconds`, so `placementContext` answers `Held(thatNode)` for every
subsequent join, every retry is routed to the same node, and every one is
refused. A world becomes unjoinable for three minutes per failed attempt.

Make the acquisition scoped: whichever component acquired releases on any
non-`Loaded` outcome, using the generation it was granted. `releaseLease` is
already conditional on `(node, generation)`, so a release racing a takeover is
a no-op rather than a hazard.

**Failing test first:** `aFailedColdLoadReleasesTheLeaseItAcquired_MN12`.
**Acceptance:** after a load failure, `placementContext` reports no live lease
and the next join is placed normally.

#### R13 — Implement FR-11's holding timeout

**Requirement:** FR-11, §9, NFR-1.
**Files:** `backend/node/TransferJoinListener.java`, `core/obs/WorldsMetrics.java`.

`transfers.holding-timeout-seconds` is used in seven places, all as the TTL of a
`node_command` row. Nothing bounds how long a player sits in the holding area.
`WorldsMetrics.holdingTimeout()` exists as a counter and is incremented nowhere
outside its own test. `ConfigValidator` enforces `commitTimeout < holdingTimeout`
on the grounds that "the kick/join paths cannot wait longer than the holding
area allows" — protecting a budget that is not enforced.

Wrap the `lifecycle.load(...)` future in a deadline. On expiry: message, eject,
increment the counter. The refusal path already exists; only the timer is
missing.

**Failing test first:** `aJoinThatNeverCompletesEjectsAtTheHoldingTimeout_FR11`.
**Acceptance:** a stalled cold load ejects at the configured deadline and
`worlds_holding_timeouts_total` moves.

#### R14 — Shutdown releases leases after unloading, not before

**Requirement:** FR-28, MN-12, FR-25.
**Files:** `backend/GzmnWorldsPlugin.java`, `backend/world/IdleUnloadTask.java`.

`onDisable` commits, then releases every lease, then unloads. FR-25 orders it
*commit, unload, release*, and `WorldHandoff.unload` carries the reasoning in a
comment: *"Release comes last, so no other node can acquire the world before its
final snapshot is the current one."* Two paths doing the same thing in opposite
orders, with the reasoning on only one of them.

Drive shutdown through `WorldHandoff.release(worldId, 0, "server shutting down")`
so there is one implementation of the order. That also picks up `afterUnload`'s
`touchLastPlayed` and cache invalidation, which `unloadAllForShutdown` currently
skips.

One thing to confirm on a live node while testing this, because it decides
whether the change is sufficient: the audit's reading is that Paper disables
plugins *before* kicking players, which would mean `Bukkit.unloadWorld` returns
false during `onDisable` for any world still holding one, and the current
shutdown path only logs that. If so, routing through the handoff is not merely
tidier — it is the only version that works, because it ejects to the holding
world first. Verify rather than assume.

**Failing test first:** `shutdownReleasesAfterUnload_FR28`.
**Acceptance:** no window exists in which a lease is free while this node still
has the world loaded.

#### R15 — Give control-plane handlers their own executor (D15)

**Requirement:** CP-3, CP-5.
**Files:** `core/control/ControlPlane.java`, `backend/GzmnWorldsPlugin.java`,
`proxy/GzmnWorldsProxyPlugin.java`.

Add a handler executor to `ControlPlane` and dispatch `completeWithHandler` onto
it. Claiming stays where it is; only handler execution moves.

Then fix the two budgets that are wrong for a related reason.
`DrainNodeHandler.budget()` returns `countdown + commitTimeout + 5s` with no
ceiling, while its own comment argues the drain "fits inside one
`control.claim-timeout-seconds` instead of being reclaimed halfway through by a
second poller". `MigrateWorldHandler.budget()` clamps for exactly that reason.
Clamp both, and add the assertion to `ConfigValidator` so the relationship is
enforced rather than asserted in prose.

**Failing test first:** `aBlockingHandlerDispatchedByThePollDoesNotStallTheScheduler`
— dispatch `MIGRATE_WORLD` with a non-zero countdown through `pollOnce` on a
single-threaded scheduler and assert it completes.
**Acceptance:** with the LISTEN connection dropped (`disconnectListener`), a
migrate with a countdown still completes, and the heartbeat keeps beating
throughout.

### Phase D — FR-40, the maintenance job

FR-40 enumerates nine duties. `MaintenanceTask` implements four: reset stuck
ARCHIVING, reset stuck RESTORING, queue inactivity archivals, expire transfer
requests. The rest of this phase is the other five, plus the three pruners that
already exist and have no production caller.

#### R16 — Startup quarantine uses the completion marker (D18)

**Requirement:** MN-4, MN-5, MN-13, MN-15a.
**Files:** `core/storage/QuarantineManager.java`, `core/storage/WorldDownloader.java`,
`backend/world/WorldLifecycleService.java`, `backend/GzmnWorldsPlugin.java`.

Write the marker on clean unload (in `afterUnload`, after the commit has
landed), naming the committed `manifest_key`. At startup, `sweepStartup` reads
each world's marker and quarantines only directories without a valid one. Pass
the node's actually-held leases too — `PlayerWorldRepository.worldsLeasedTo`
already exists and is unused by this path.

Implement MN-4's other half while the marker is being added: a world whose
marker is absent is fully rehashed before use rather than trusted on size and
mtime.

**Failing test first:** `aCleanlyUnloadedWorldSurvivesRestartAsAWarmCache_MN5`,
and `aWorldWithNoMarkerIsQuarantined_MN13`.
**Acceptance:** a planned restart preserves warm copies; a `kill -9` restart
quarantines them.

#### R17 — Wire the three pruners

**Requirement:** FR-15c, MN-2b, MN-13a, MN-5.
**Files:** `backend/storage/MaintenanceTask.java`, plus the existing
`LocalObjectCache.evictLru`, `QuarantineManager.prune`,
`ProfileRepository.pruneToLatest`.

`storage.local-cache-max-gb`, `storage.quarantine-max-gb`,
`storage.quarantine-retain-days` and `storage.manifest-retention-count` are
read into `NetworkPolicy`, validated at startup, and consulted by nothing.

Two of the three are node-local and must **not** run under the FR-40 advisory
lock — every node prunes its own disk. Only profile and manifest pruning is
network-wide and belongs inside the lock. Splitting them is the point: today
there is one sweep and it is election-gated, which would leave the disks of
every node but one unpruned even after the pruners are called.

Order matters within the locked half: FR-15c and §7 both warn that pruning
manifests faster than profiles makes a load find a `manifest_key` whose profiles
are gone. Prune to the same retained set in one transaction, or prune profiles
last.

**Failing test first:** one per pruner, asserting the bound is honoured.
**Acceptance:** a node under sustained quarantine pressure stays below
`storage.quarantine-max-gb` rather than failing `PathChecks.requireFreeSpace` at
the next enable.

#### R18 — Sweep `node_command`

**Requirement:** CP-7, FR-40.
**Files:** `core/db/NodeCommandRepository.java`, `backend/storage/MaintenanceTask.java`.

CP-7: *"Expired and completed rows are swept by the maintenance job in FR-40."*
`nodeCommands` is injected into `MaintenanceTask` and used only to enqueue. The
table grows forever, and `findClaimableIds` scans an ever-larger index.

Add `deleteCompletedBefore(Duration retain)` and call it under the lock.

**Failing test first:** `completedCommandsAreSweptAfterTheirRetention_CP7`.

#### R19 — Expire invites and `pending_transfer` rows

**Requirement:** FR-40.
**Files:** `core/db/MembershipRepository.java`,
`core/db/PendingTransferRepository.java`, `backend/storage/MaintenanceTask.java`.

Both tables filter on `expires_at > now()` at read time, so behaviour is
correct, but neither is ever swept. `PendingTransferRepository.deleteExpired`
already exists and has no production caller.

**Acceptance:** both tables are bounded by their configured expiry.

#### R20 — Archival warnings, and object-storage garbage collection

**Requirement:** FR-34 (`archive.warn-days`), MN-2b.
**Files:** `backend/storage/MaintenanceTask.java`, `core/storage/ObjectStore.java`.

Two separate pieces that both live in the sweep:

- **FR-34's warnings** at 14 and 3 days. The owner is offline by definition —
  that is why the world is being archived — so this needs a durable channel. The
  simplest one consistent with §13 is a `node_command` on `gzmn_proxy` that the
  proxy delivers on next login, alongside the transfer-request reminder it
  already sends in `onPostLogin`. Record the warning on the row so it is not
  re-sent every sweep.
- **MN-2b's GC.** Only correct once D16 lands: today every manifest is
  cumulative, so nearly every object is referenced by a retained manifest and
  the pass would reclaim almost nothing. List `worlds/<id>/data/`, subtract the
  union of the retained manifests' hashes, delete the remainder. Bound it per
  sweep the way `BATCH_LIMIT` bounds the others.

**Acceptance:** an owner returning after an auto-archive has been warned twice;
a world that has churned its region files reclaims storage after its old
manifests age out.

### Phase E — storage-model correctness

#### R21 — Manifests express deletions; materialise mirrors the manifest (D16)

**Requirement:** MN-3, MN-4, MN-2b.
**Files:** `core/storage/DirtyScanner.java`, `core/storage/SnapshotEngine.java`,
`core/storage/WorldDownloader.java`.

`DirtyScanner` returns the observed path set alongside the dirty subset;
`SnapshotEngine` builds `newEntries` from the observed set rather than from the
baseline; `WorldDownloader.materialize` removes local files under the world's
folders that the manifest does not list.

While in `DirtyScanner`: it re-derives `base + "_nether"` and `base + "_the_end"`
as string literals, which is exactly the hardcoding `WorldFolders` exists to
prevent — *"The suffixes are never hardcoded here — they are recovered from the
layout itself"*. `QuarantineManager` does the same. `:core` cannot see
`WorldLayout` (CONTRIBUTING rule 2), so the caller passes the three folder names
in. Three call sites, one source of truth.

**Failing test first:** `aDeletedFileLeavesTheNextManifest_MN3`, and
`materialiseRemovesFilesTheManifestDoesNotList_MN4`.

#### R22 — A restore preserves profiles and its generation (D17)

**Requirement:** FR-36, FR-15b, MN-3.
**Files:** `backend/storage/WorldRestorer.java`, `core/db/PlayerWorldRepository.java`.

Two bugs in one place. The restore snapshot is written at a hardcoded
`generation = 0, sequence = 1`, so (a) `ProfileListener` parses `(0,1)` out of
the resulting `manifest_key`, finds no profile rows, and issues every member a
fresh profile — a silent, total inventory wipe on every restore; and (b) MN-3's
write-once manifest key is violated, because a second restore rewrites the same
`0-1.json` object with different content.

Carry the generation `transitionToRestoring` just granted into the snapshot, and
re-key the newest surviving profile snapshot onto the restore's
`(generation, sequence)` inside `completeRestore`'s transaction.

**Failing test first:** `restoringAWorldPreservesInventories_FR36`, and
`twoRestoresDoNotWriteTheSameManifestKey_MN3`.
**Acceptance:** the milestone-11 round trip returns a world *and* its players'
inventories.

#### R23 — `deleteHard` deletes the archives it promises to delete

**Requirement:** FR-37.
**Files:** `proxy/command/WorldActions.java`, `core/db/PlayerWorldRepository.java`,
`backend/storage/WorldArchiver.java`.

The confirmation promises to permanently destroy the world *"and all backup
archives"*. `worlds.deleteHard` deletes the `player_world` row; the cascade
removes the `player_world_archive` rows; the archive objects and any surviving
per-world prefix in object storage are orphaned permanently, and MN-2b can never
find them because the GC walks per world and the world is gone.

Delete the objects before the row, or route hard deletion through a node
(`ARCHIVE_WORLD`'s sibling) that owns the object store. The second is more
consistent with §13 — the proxy has no object-store client — and gives the
operation a result to report under R24.

**Failing test first:** `hardDeleteRemovesArchiveObjects_FR37`.

### Phase F — reporting, messaging, and de-duplication

#### R24 — The producer reads the control-plane result back

**Requirement:** CP-5, CP-6, §6.
**Files:** `proxy/command/WorldActions.java`, `core/control/ControlPlane.java`.

Every command except `MIGRATE_WORLD` enqueues a row and immediately reports
success. `node_command.result` is written by every handler and read by nothing,
so CP-6's three deliberately-visible failure outcomes are invisible: a
`/world delete` discarded as `STALE_GENERATION` leaves the world READY, the
owner's slot consumed, and the owner told it is archiving.

Add a bounded await helper — poll `findById` until `completed_at`, bounded by
the row's TTL, on the db executor — and use it for the commands whose outcome a
player is waiting on: archive, restore, unload, hard delete. `MigrateWorldHandler`
already demonstrates the pattern.

`enqueueToWorldOrAliveNodes` needs a rule of its own here: when a world is
unleased it broadcasts to every alive node, so "the result" is several results.
For an idempotent notification (`INVALIDATE_CACHE`, `KICK_MEMBER`) that is fine
and no await is needed. For anything a player waits on, the world must be placed
first and the command addressed to one node.

**Acceptance:** an archive discarded as stale reports the discard to the owner.

#### R25 — `/world delete` on a CREATING world stops reporting failure after succeeding

**Requirement:** FR-27, OQ-18.
**Files:** `proxy/command/WorldActions.java`.

`deleteIfCreating` commits, then `enqueueToWorldOrAliveNodes` inserts a
`node_command` whose `world_id` references the row just deleted. The foreign key
rejects it, the `SQLException` reaches the method's outer handler, and the owner
is told *"that did not work"* — after the world is gone and their cap slot is
freed. The success message and the log line after it are unreachable.

Enqueue inside the same transaction as the delete using the `Connection`-taking
overloads that already exist, and order the insert before the delete; or carry
the world id in the payload and leave the column null.

**Failing test first:** `deletingACreatingWorldReportsSuccess_FR27`.

#### R26 — `info`/`error`/`success` stop sending as a side effect

**Requirement:** NFR-5.
**Files:** `proxy/command/WorldActions.java`, `proxy/command/ActionResult.java`,
`proxy/menu/MenuChannelListener.java`.

The three helpers both send the message and return it, and the returned
`Component` goes into an `ActionResult` that the menu channel serialises and
sends again — so every GUI-driven action delivers its message twice. Several
return values are also discarded (`WorldActions:242`, `:277`, `:357`), so those
lines reach chat but never the GUI: an invisible split in what the two surfaces
say, and `deleteHard`'s "this cannot be undone" warning is one of them.

Make them pure builders. One place decides delivery: the command tree sends
`result.message()`, the menu channel serialises it.

In the same pass, replace `ActionResult.code()`'s `String` and
`MenuChannelListener.mapFailureCode`'s 25-case translation table with
`FailureCode` produced directly by `WorldActions`. Both ends are in this
repository; the string round trip buys nothing and silently degrades an unmapped
code to `GENERIC_ERROR`.

Also fix the raw `Component.text("cannot route to node", RED)` returned by four
call sites after `routableNodeOrExplain` has already sent the real explanation:
a chat user sees the explanation, a GUI user sees developer text.

#### R27 — Correct the messages that describe behaviour the code no longer has

**Requirement:** FR-8, FR-16, NFR-5.
**Files:** `proxy/command/WorldActions.java`, `backend/profile/ProfileListener.java`.

- `/world kick` says *"if they are inside the world right now they will be
  removed on their next join"* immediately after dispatching a `KICK_MEMBER`
  that ejects them now (FR-8: "removes them from the world immediately"). It
  describes the pre-control-plane behaviour and teaches operators the wrong
  model.
- FR-16's refusal says *"Nothing has been overwritten"* after `applyFresh` has
  cleared the inventory. R11 makes that true; this task makes the text match.

#### R28 — De-duplicate the two holding-area implementations, and settle `lastLocation`

**Requirement:** FR-11, FR-14.
**Files:** `backend/lease/SelfFencingHandler.java`, `backend/control/WorldHandoff.java`,
`backend/profile/ProfileService.java`, `backend/node/TransferJoinListener.java`.

`holdingWorld(WorldId)` is duplicated verbatim in `SelfFencingHandler` and
`WorldHandoff`, including the two-pass fallback and its comment. Both are FR-11's
holding area. One implementation.

`ProfileEnvelope.lastLocation` is captured, encoded and decoded, and read by
nothing: `capture`'s javadoc says it is stored *"so a rejoin returns them where
they were rather than to spawn"*, `restore`'s says it deliberately does not
teleport, and `TransferJoinListener.sendIn` always teleports to overworld spawn.
Two adjacent javadocs describe opposite intentions and one of them describes
behaviour that does not exist. Either implement it — materialise the stored
dimension, clamp inside the border, teleport — or delete the field and correct
both javadocs. The current state is the worst of the three options. FR-14 lists
last location as in scope, so implementing is the default reading.

---

## 4. Spec amendments this plan needs

CLAUDE.md: *"If the spec does not cover what you are about to write, that is a
finding worth reporting, not a gap to fill silently."* Four sentences are
missing, and each one is a decision the code has already taken implicitly.

1. **FR-36 — profiles across a restore (D17).** The spec never says. The code
   says "they are lost", silently. Proposed: *"A restore re-keys the world's most
   recent retained profile snapshot onto the snapshot it commits, in the same
   transaction, so a restored world returns its players' state with it (FR-15b)."*
2. **MN-13 — what "may have diverged" means (D18).** "Not covered by a lease"
   stops distinguishing crash debris from a warm cache the moment a clean
   shutdown releases its leases. Proposed: MN-13 keys on MN-4's completion
   marker, and MN-4's marker records the `manifest_key` it was written against.
3. **MN-2b / MN-3 — manifests must be able to shrink (D16).** MN-3 says a
   world's state is defined by its manifest; a manifest that cannot express a
   deletion cannot define state, and MN-2b's GC cannot collect what a
   permanently-referenced entry pins. One clarifying sentence in MN-3.
4. **§6 — `/world admin drain`.** Already reported in `NEXT-STEPS.md` and still
   open: MN-22 is an operational requirement with no row in §6's table. Either
   §6 gains the row or MN-22 names the mechanism.
5. **§7 and §12.8 — `archive.compression` (R0b).** The documented default
   `zstd-3` cannot work: zstd-jni is native and its JNI symbols are bound to a
   package the plugin jar must relocate, so the default threw
   `UnsatisfiedLinkError` on every archival. The code now defaults to `gzip`.
   The specification should either name `gzip` as the default and drop zstd, or
   state that zstd requires an unshaded deployment that this project does not
   produce.

Two further items are already recorded as open in `NEXT-STEPS.md` and are folded
into tasks above rather than restated: `commitSnapshot`'s fencing predicate
being looser than MN-3a (R8's neighbourhood), and `DrainNodeHandler`'s unclamped
budget (R15).

---

## 5. Blocked on a decision

These tasks have a defensible answer in the plan but should not be written until
the answer is confirmed, because each changes observable behaviour:

| Task | Question |
| --- | --- |
| R10 | Is the `latestSnapshot` fallback only for the no-object-storage mode, or is it deliberate elsewhere? |
| R16 | Confirm D18: is a cleanly unloaded world's scratch directory a warm cache to keep, or crash debris to quarantine? |
| R20 | FR-34's warnings need a delivery channel. Is a `gzmn_proxy` command delivered on next login acceptable, or is the Discord DM path in FR-34 the intended one? |
| R22 | Confirm D17 before the migration is written. |
| R28 | Implement `lastLocation` or delete it? |
| — | OQ-14's retention period for `player_world_report` chat logs — 30 days, 90, or until handled? R19's sweep is where it lands. |
| — | Is multi-proxy in scope? Both proxies would use `targetNode = "proxy"` on one shared channel, so an `EJECT_PLAYER` claimed by the proxy the player is *not* on is silently dropped (`ProxyEjectHandler` returns `ok()`). If yes, ejects need per-proxy addressing and this becomes a Phase C task. |

---

## 6. Guards, so each shape cannot come back

Milestone 8 added two ArchUnit rules by breaking them first, which is the
standard this repository has set. Four more guards, each aimed at a shape from
§0 rather than at the instance that was found:

1. **Every `Listener` is registered** (shape 1). The MockBukkit assertion in R1.
   Also worth extending to every `CommandHandler`: assert the registered
   `CommandKind` set covers CP-6's list, so `APPLY_SETTINGS` cannot go missing
   again.
2. **Bukkit mutation asserts the main thread** (R15's neighbourhood).
   `QuiesceWatchdog` calls `runtime.setAutoSave` from the `sched` pool, and
   `PaperWorldRuntime` asserts nothing, so it works by luck. Add
   `MainThread.assertOn()` to the `WorldRuntime` mutators; the watchdog then
   fails loudly and is fixed to hop to main, which is what MN-5a's "independent
   watchdog" meant — independent of the happy path, not of the tick thread.
3. **A config key that nothing reads fails the build** (shape 1). Four keys in
   `NetworkPolicy` are validated and never consulted. A test asserting every
   `KEY_*` constant has at least one non-test reader is crude but would have
   caught all four.
4. **No `pg_advisory_unlock` failure returns a connection to the pool.**
   `AdvisoryLock.close` logs and returns the connection, reasoning that *"ending
   the session releases the lock anyway"* — true for process death, false for a
   pooled connection, which does not end its session. A leaked
   `MAINTENANCE_KEY` disables FR-40 across the whole network silently, because
   `sweep` treats "somebody else has it" as the normal case. Evict the
   connection on unlock failure, and log at error rather than warn.

---

## 7. What must be verified on a live stack

Unit and Testcontainers tests cover most of the above. These do not, and each
maps to a `NEXT-STEPS.md` checkbox that is still open:

- [ ] **R1.** Three accounts, two worlds, one node. `/list`, `/tell`, `/msg` and
      `@a` from inside a player world reveal nobody outside the group.
- [ ] **R4 + R9.** Owner inside a loaded world runs `/world promote`, then
      `/world set pvp on`, then `/world set containers on`. All three take
      effect; the owner can still build throughout.
- [ ] **R2 + R3 + R22.** Full archive and restore round trip on a real world
      with a real inventory — spec milestone 11 — asserting the inventory comes
      back.
- [ ] **R12 + R13.** Stop MinIO, join a cold world, and assert the player is
      ejected at the holding timeout *and* the world is joinable again
      immediately once MinIO returns, rather than after the lease expires.
- [ ] **R14.** Clean restart with a player inside; the last minutes of play
      survive and no lease is left behind.
- [ ] **R15.** Drop the LISTEN connection, then `/world admin migrate`; the
      migrate completes and the heartbeat never misses a beat.
- [ ] **R16.** Play, idle out, restart the node, rejoin: the load is warm.
      Then `kill -9` mid-session, restart, rejoin: the directory is quarantined
      and the load is cold and correct.

The e2e harness boots two Paper nodes and a Velocity proxy but cannot yet drive
`/world` — `NEXT-STEPS.md` records that the smoke joins a lobby and stops. Most
of the list above needs a bot that sends chat commands or console access to
Velocity. That harness work is a prerequisite for believing any of it, and it is
the same prerequisite milestone 8's open checkboxes already have; it is not
duplicated as a task here.

---

## 8. Sequencing

Phase A (R0–R5) is complete. Remaining work is one branch per task from Phase B
onwards, each with its failing test first, in the order given. R7 before R12,
R21 before R20's GC half, R16 after R21's marker. Nothing else has a hard
ordering constraint.

Each commit references the requirement it closes, per CONTRIBUTING. Where a
task closes a spec *gap* rather than a spec requirement, the commit body should
name the §4 amendment it depends on, so the two land traceably together.
