# Next steps

Short working list. Full detail and acceptance criteria live in
[`00-repo-foundation.md`](00-repo-foundation.md) and
[`01-world-lifecycle.md`](01-world-lifecycle.md); the specification is
[`../spec/v0.4.md`](../spec/v0.4.md).

F0–F12 are done. `./gradlew build` is green on all modules against Paper 26.2 and
Velocity 4.0.0, each quality gate has been verified by deliberately breaking it,
and both plugin jars have been loaded on real servers.

## Plan 05 — audit remediation: complete

Full detail, acceptance criteria and the failing test each fix started from live
in [`05-audit-remediation.md`](05-audit-remediation.md). This is the working
summary.

**R0–R28 have all landed.** `./gradlew check` is green on all four modules after
each of them, and every fix started from a test that fails against the old
behaviour — proven by temporarily reverting the fix and watching the test go red,
not by inspection. What is left is the live-stack verification below, which no
amount of unit testing can stand in for.

### What each phase closed

- **A (R0–R5)** — data loss and access control. The Paper 26 world layout was
  wrong, so object storage held no world data at all; archival compared a length
  and then deleted the only remaining copy; `CommandGuardListener` was never
  registered, so FR-21 and FR-22 did not run on a live node; permission checks
  lived in Brigadier clauses the GUI bypassed.
- **B (R6–R10)** — the commit path. A failed commit lost departing profiles,
  a fenced world kept re-uploading itself, and `generation` doubled as a "not
  found" sentinel.
- **C (R11–R15)** — lease and lifecycle. FR-16's refusal cleared an inventory and
  then said it had not; a failed load left a world unjoinable for the lease
  duration; nothing bounded the holding area; shutdown released leases before
  unloading; a blocking control-plane handler deadlocked the scheduler its own
  continuations needed.
- **D (R16–R20)** — FR-40. The maintenance job implemented four of its nine
  duties. It now keeps warm caches instead of quarantining them on every restart,
  runs the three pruners that had no caller, sweeps `node_command`, invites and
  `pending_transfer`, warns owners before auto-archival, and collects the object
  storage MN-2b describes.
- **E (R21–R23)** — the storage model. A manifest could not express a deletion;
  a restore silently wiped every inventory and rewrote the same manifest key;
  hard deletion orphaned every archive object permanently.
- **F (R24–R28)** — reporting and messaging. `node_command.result` was written by
  every handler and read by nothing; `/world delete` on a CREATING world reported
  failure after succeeding; every GUI action delivered its message twice; two
  messages described behaviour the code no longer had; `lastLocation` was stored
  and never read.

### The specification changed, once

**FR-11's holding timeout is now the outer budget of the join path, default 90.**
It was 30 while NFR-1 gave a cold load 60, and neither was enforced, so the
conflict had never surfaced; implementing R13 literally would have ejected cold
loads still well inside the budget NFR-1 grants them. Of the two numbers NFR-1's
is the one derived from something real, so the outer budget moved.
`storage.cold-load-budget-seconds` and `storage.commit-timeout-seconds` must now
both be strictly smaller, and startup validation enforces it. `docs/spec/v0.4.md`
§5.3, §7, §8 and §9 are edited; plan 05 §4 item 5a records it.

Three places the plan proposed something that turned out not to work, each
changed deliberately and recorded in plan 05 with the reasoning:

- **R20's warning channel.** A `node_command` on `gzmn_proxy` is claimed by the
  control-plane poll within seconds (CP-2) and completed as "no handler" (CP-6),
  and R18's sweep then deletes it. A notice meant to wait days is state, not an
  instruction — CP-6 says as much — so V5 adds `player_notice`.
- **R24's wait.** Waiting for `completed_at` bounded by the row's TTL would hold
  a database thread for ninety seconds and make every *successful* archive sit
  silent. CP-6's outcomes are decided at *claim*, so the wait ends there.
- **R25's ordering.** Enqueueing inside the delete's transaction does not help:
  `node_command.world_id` cascades, so an insert before the delete is removed by
  it and one after violates the key. The world is named in the payload instead.

### Schema

**V5** adds `player_notice` and `player_world.archive_warned_days` (FR-34).
`Schema.MAX_SUPPORTED` is 5.

### What must happen on a live stack before any of this is believed

Unit and Testcontainers tests cover the logic; none of the below is reachable
from them. No node and no e2e run were available while this was written.

- [ ] **R1.** Three accounts, two worlds, one node. `/list`, `/tell`, `/msg` and
      `@a` from inside a player world reveal nobody outside the group.
- [ ] **R4 + R9.** Owner inside a loaded world runs `/world promote`, then
      `/world set pvp on`, then `/world set containers on`. All three take
      effect; the owner can still build throughout.
- [ ] **R2 + R3 + R22.** Full archive and restore round trip on a real world with
      a real inventory — spec milestone 11 — asserting the inventory comes back.
- [ ] **R12 + R13.** Stop MinIO, join a cold world, and assert the player is
      ejected at the holding timeout *and* the world is joinable again
      immediately once MinIO returns, rather than after the lease expires.
- [ ] **R14.** Clean restart with a player inside; the last minutes of play
      survive and no lease is left behind. Worth watching the log for the
      `DrainableMainScheduler` path: Paper marks a plugin disabled *before*
      calling `onDisable`, which is why the old shutdown could not use the
      ordinary give-up sequence at all.
- [ ] **R15.** Drop the LISTEN connection, then `/world admin migrate`; the
      migrate completes and the heartbeat never misses a beat.
- [ ] **R16.** Play, idle out, restart the node, rejoin: the load is warm. Then
      `kill -9` mid-session, restart, rejoin: the directory is quarantined and
      the load is cold and correct.
- [ ] **R20.** A world 76 days idle warns its owner on next login, once, and
      again at 3 days out. And a world that has churned its region files
      reclaims storage after its old manifests age out.
- [ ] **R23.** Hard-delete an archived world and confirm the bucket no longer
      holds its archive object or its `worlds/<id>/` prefix.
- [ ] **R26.** Drive an action from the GUI and from chat. The GUI shows the
      message once; chat shows it once; a routing refusal shows the real reason
      on both.
- [ ] **R28.** Log out in a player world away from spawn, rejoin, and land where
      you left rather than at spawn.

One question R14 raised is still unanswered on a live node — whether Paper kicks
players before or after disabling plugins — but the change no longer depends on
it. The handoff ejects to the holding world first, so it works either way, and
`unloadWorld` refusing is now a `Blocked` outcome that keeps the lease rather
than a log line printed after the lease was already dropped.

### Items reported earlier and not fixed at the time — where they stand now

- **`DrainNodeHandler`'s budget is not clamped to the claim timeout, but says it
  is** — closed by R15. Both handlers call `HandoffBudget.forCountdown`, and
  `ConfigValidator` enforces the relationship rather than a comment asserting it.
- **`/world`'s member commands have no permission checks** — closed by R5. The
  checks moved into `WorldActions`, so the GUI path that bypassed FR-9h entirely
  now returns `PERMISSION_DENIED`.
- **`commitSnapshot`'s fencing predicate is looser than MN-3a** is **still
  open.** R8 landed in its neighbourhood and fixed the sentinel and the
  misleading message, but the predicate still permits a commit when
  `assigned_node IS NULL`.
- **MN-22 has no command in §6** is still open, and is still a spec question
  rather than a code one (plan 05 §4 item 4).

### Still open, and still blocking nothing

- OQ-14's retention period for `player_world_report` chat logs — 30 days, 90, or
  until handled? R19 swept every other expiring table and left this one alone
  for want of the answer.
- Is multi-proxy in scope? Both proxies would use `targetNode = "proxy"` on one
  shared channel, so an `EJECT_PLAYER` claimed by the proxy the player is *not*
  on is silently dropped (`ProxyEjectHandler` returns `ok()`). If yes, ejects
  need per-proxy addressing.

### Worth doing next, in the order they would pay off

1. **The e2e harness needs to drive `/world`.** Every unchecked box above needs
   either a bot that sends chat commands or console access to Velocity. That one
   piece of harness work is the prerequisite for believing any of milestone 8's
   open checkboxes as well as plan 05's — it is the same prerequisite, and it has
   been the same prerequisite for two milestones.
2. **Plan 05 §6's four guards**, so each shape cannot come back: every `Listener`
   registered, Bukkit mutation asserting the main thread, a config key nothing
   reads failing the build, and no `pg_advisory_unlock` failure returning a
   connection to the pool.


## Milestone 8 — the second node: built, tested and booted on two nodes

Spec §11 milestone 8: "heartbeats, placement service, dynamic Velocity
registration, `/world admin migrate`, node draining. Version gating (12.9) is
tested here, because this is the first milestone with two nodes to disagree."

### Verified

`./gradlew check build` **is green**, on a JDK 25 toolchain with
`repo.papermc.io` reachable and a Docker daemon available — the three things the
environment this milestone was written in did not have.

- **`./gradlew check build`: BUILD SUCCESSFUL.** 378 tests, 0 failures, 0
  skipped (`:core` 234, `:backend` 106, `:proxy` 17, `:testing` 21). Re-run with
  `--no-build-cache --rerun-tasks` to confirm the result is not a restored one.
- **`:backend` and `:proxy` compile**, under Error Prone, NullAway,
  forbidden-apis, the licence gate and Spotless. This was the milestone's largest
  unknown, and nothing in their main sources needed changing.
- **The 96 Testcontainers suites run**, against real PostgreSQL 18.3 and MinIO,
  rather than being skipped.
- **The two new ArchUnit rules were verified by breaking them**, not by grep: a
  deliberate `Instant.now()` in `proxy.node.Placement` and a
  `System.currentTimeMillis()` in `backend.world.IdleUnloadTask` each failed the
  rule with the offending method named. Both injections were reverted.
- **The e2e compose harness boots the whole two-node stack**: `paper-a` and
  `paper-b` both enable `gzmn-worlds` against real PostgreSQL, Velocity enables
  `gzmn-worlds-proxy`, and a player joins the lobby through the proxy. This is
  the first time either plugin jar has run in the harness.
- **V3 applied against real PostgreSQL** — `flyway_schema_history` shows
  `3 placement`, and `player_world.last_node` exists (MN-15a's warm-copy source).
- **The heartbeat publishes TPS** — `worlds_node` carries `tps` 19.8 and 20.0 for
  the two nodes, varying between reads rather than constant. This is the
  milestone 8 fix for a column that was previously always NULL, and MN-15
  excludes on it.
- **Dynamic Velocity registration works with two nodes** (MN-17, MN-18): the
  proxy logs `registered node paper-a` and `registered node paper-b`, and
  registers `/world` with all ten subcommands including the `admin` subtree.

One fix was needed to make the build pass: eight of the new lease setups in
`PlayerWorldRepositoryTest` discarded the result of `acquireLease(...)
.orElseThrow()`, which Error Prone rejects as `ReturnValueIgnored`, so
`:core:compileTestJava` did not compile. Nothing had caught it because no JDK 25
had ever run javac with Error Prone attached over this code.

Two defects in the e2e harness itself had to be fixed before it could reach the
proxy at all, both of them older than milestone 8:

- **`prepare.sh` staged the proxy jar and no configuration for it.** The plugin
  fell back to its bundled default of `127.0.0.1:5432`, the bootstrap timed out,
  and it logged `/world will not be registered` and carried on. Velocity forwards
  a lobby join whether or not `gzmn-worlds-proxy` is awake, so the F11 smoke
  passed over the top of an inert proxy — the harness had never exercised
  placement, `/world`, or the proxy control plane.
- **The scripts were committed non-executable.** `e2e/scripts/run.sh`, the
  documented entry point, fails with "Permission denied" on a fresh clone, as do
  the `prepare.sh` and `smoke.sh` it invokes by path.

### Still not verified

The behavioural two-node checks below are still open. The harness now reaches
the proxy, so they are reachable, but no scenario drives them yet: the smoke
joins a lobby and stops there. Driving `/world` needs either a bot that sends
chat commands or console access to Velocity, neither of which exists.

### Landed

- **Placement (MN-14 to MN-16, MN-15a, MN-28)** — `core.placement` holds the
  decision as a pure function, and `proxy.node.Placement` the three queries that
  feed it. A live lease wins outright and is not scored; MN-28's version filter
  is evaluated before every other term; MN-15's loaded-world, heap and TPS
  thresholds exclude hard; MN-15a's warm copy and public/private separation score
  as preferences, warm copy dominating. Thirteen unit tests, all green.
- **MN-16 was broken and is fixed.** `/world join` scored a node and *then*
  checked whether the world held a live lease on that node. With one node the two
  always agreed. With two, the second member of a loaded world was routed to
  whichever node was emptier, where `acquireLease` lost to the holder's live
  lease and the join was refused with "could not acquire a lease". This is the
  defect milestone 8 exists to expose, and it was in the milestone-5 stub exactly
  as that stub's comment said it would be.
- **Version gating (12.9)**, on both halves: placement excludes an older node
  (MN-28) and MN-26's predicate refuses it the lease anyway. A world newer than
  every live node now reports *that* rather than "no server available" —
  §12.7's rolled-back pool is a wait, not a capacity problem.
- **`/world admin list | unload | migrate`** (§6), gated on `gzmn.worlds.admin`.
  `migrate` drives MN-19 in MN-8's only safe order: ask the holder to give the
  world up, wait for the control-plane row to complete, then acquire the lease on
  the target. There is no window in which two nodes hold it.
- **`/world admin drain <node> [on|off]`** (MN-22), plus `DRAIN_NODE` and
  `MIGRATE_WORLD` handlers on the node. Both run `WorldHandoff` — one
  implementation of MN-19's warn, eject, commit, unload, release.
- **The heartbeat reports TPS.** MN-15 excludes on it; it was published as NULL.
- **`player_world.last_node` (V3)**, written by the same conditional `UPDATE`
  that moves the manifest pointer, so a fenced commit cannot claim a warm copy it
  did not write. MN-15a's warm-copy term has no other source: `assigned_node` is
  NULL for exactly the worlds placement is asked about.

### Found while reviewing milestones 1–7

Four defects, all fixed here, none of which a single-node test could have caught.

- **FR-25's pre-unload commit was missing.** FR-25 orders it *commit, unload,
  release*; the idle sweep did only the last two. Every idle unload therefore
  discarded up to `storage.sync-minutes` of play — for the world and for every
  profile in it together (FR-15). The sweep now commits first, leaves the world
  loaded if the commit fails, and cancels the unload if somebody rejoins while it
  is in flight. `UNLOAD_WORLD` owed the same commit and now runs the same path.
- **Lease decisions read the local clock.** `WorldLifecycleService.readForLoad`
  and the proxy's `/world join` both compared `lease_expires` to
  `Instant.now()` to decide whether to skip MN-8's acquisition. That is
  CONTRIBUTING rule 5 and MN-10b, and the ArchUnit rule that forbids it only
  covers `core.db`, so both slipped through. Liveness is now asked of the
  database (`leaseHolder`, `placementContext`).
- **Self-fencing could not unload.** `SelfFencingHandler` messaged the players
  inside and then called `unloadWorld`, which Bukkit refuses while a world holds
  a player. The proxy eject that would have moved them was enqueued *after*, and
  asynchronously. So a fenced world kept ticking, which is the one thing MN-10a
  exists to prevent. Players are now moved to the holding area first.
- **`/world create` placed one world id and inserted another.** Two independent
  `WorldId.random()` calls. Invisible only because placement did not key on the
  id; it does now.

### Guarded so it cannot come back

`backend` and `proxy` each gained an ArchUnit rule banning `Instant.now()`,
`LocalDateTime.now()` and `System.currentTimeMillis()`, module-wide rather than
scoped to the lease packages — the two sites that got it wrong were in the world
and command packages, and a rule scoped to where the mistake was already made
prevents nothing. `System.nanoTime` is untouched: it is monotonic, measures
elapsed time, and is what `DbClock.elapsedSince` wraps. The proxy had no
architecture test at all before this; it now also has `core`'s JDBC-confinement
rule.

### Reported rather than fixed

- **MN-22 has no command in §6.** Section 6's table predates section 12, and
  draining a node is an operational requirement with no other way to invoke it.
  `/world admin drain` is therefore an addition to that table, not an entry from
  it. Either §6 should gain the row or MN-22 should name the mechanism.
- **`commitSnapshot`'s fencing predicate is looser than MN-3a.** It permits a
  commit when `assigned_node IS NULL`, which lets a node commit after its own
  `releaseLease` — harmless today, because generation still gates it and only the
  releasing node can match, but it is not what MN-3a says.
- **`/world`'s member commands have no permission checks.** §6 gives `create`
  and `join` `gzmn.worlds.create` / `gzmn.worlds.join`; the proxy checks neither.
  Only the `admin` subtree added here is gated. Out of scope for milestone 8, but
  it is a gap in milestone 2's work rather than a deferred feature.
- **`DrainNodeHandler`'s budget is not clamped to the claim timeout, but says it
  is.** Its comment argues the drain "fits inside one
  `control.claim-timeout-seconds` instead of being reclaimed halfway through by a
  second poller", yet `budget()` returns `countdown + storage.commit-timeout + 5s`
  with no ceiling. `MigrateWorldHandler.budget()` clamps to
  `controlClaimTimeout - 1s` for exactly this reason. At the defaults (countdown
  10s, commit timeout 15s, claim timeout 60s) the drain budget is 30s and the
  gap cannot bite; raise `storage.commit-timeout-seconds` past ~45s, which is a
  plausible tuning for large worlds, and a second poller can reclaim a
  `DRAIN_NODE` command while the first is still draining. Left alone rather than
  changed because it alters the timing of a path no test here can drive.

### What must happen on two nodes before this milestone is believed

Nothing below can be checked without a second node, which is the whole point of
the milestone.

- [x] **Build it.** `./gradlew check build` on a JDK 25 with `repo.papermc.io`
      reachable. Green: 378 tests, 0 failures, 0 skipped.
- [ ] **MN-16.** Two accounts, one world. The first joins and loads it on node A;
      the second joins while node B is emptier, and must land on node A.
- [ ] **MN-28 / MN-26, the §11 acceptance case.** Run the pair at different
      Minecraft versions, open a world on the newer one so a commit stamps its
      `data_version`, then confirm the older node is excluded from placement for
      it *and* cannot acquire its lease.
- [ ] **MN-15a's warm copy.** Load a world on A, let it idle out, then rejoin:
      placement should return it to A, and the load should be warm.
- [ ] **`/world admin migrate <id> <node>`** with a player inside: countdown
      shown, player to lobby, snapshot committed, lease on the target, and the
      player's inventory intact when they rejoin.
- [ ] **`/world admin drain`**: the node stops taking placements, releases its
      worlds, and leaves Velocity's server list on the next sweep. Then
      `drain <node> off` brings it back.
- [ ] **The FR-25 commit.** Play, let a world idle out, wipe local scratch,
      rejoin: the last few minutes before the unload must still be there. This
      is the regression the fix above is for.
- [ ] **Self-fencing with a player inside**, which is milestone 7's SIGSTOP test
      re-run now that the unload can actually succeed. Before this it could not:
      Bukkit refuses to unload a world holding a player, so the fenced world kept
      ticking and the test would have passed on the data-integrity assertion
      while the liveness half silently did nothing.

The e2e compose harness already boots two Paper nodes, so MN-16 and the version
gate are reachable there rather than only by hand. Writing that scenario needs
Docker, which this environment does not have, so it is not attempted here.

## Milestone 5 — transfer handoff, node registration and control plane: code complete, unverified

`./gradlew check build` is green. Landed:

- **FR-10 / FR-11** — the proxy writes `pending_transfer` and connects the
  player; the node claims it with a single `DELETE ... RETURNING`, so a
  reconnect racing the first join cannot consume the same route twice. Every
  refusal branch FR-11 names is present, including the generation check (zero
  against zero until milestone 7, written now because a comparison added later
  is one nobody tests).
- **MN-17 / MN-18** — nodes publish a heartbeat carrying their chunk
  `DataVersion`, and the proxy mirrors the alive set into Velocity's server
  list. `velocity.toml` is never edited to add capacity; the sweep only ever
  unregisters names it registered itself.
- **FR-13** — `player_last_world` for the resume prompt.
- **`/world join <owner> [name]`** on the proxy, membership-checked, with a
  non-member getting the same answer as a world that does not exist.
- **Control-plane runtime & handlers (CP-1–7)** — `ControlPlane.forNode` and
  `ControlPlane.forProxy` wired and started. `UNLOAD_WORLD` unloads dimensions
  and ejects players; `INVALIDATE_CACHE` refreshes network policy and drops
  cached roles; `KICK_MEMBER` and `EJECT_PLAYER` eject players on member
  revocation. Emitted on `/world delete confirm`, `/world kick` and `/world promote`.
- **The return leg & `/world leave` (FR-11, FR-12, OQ-15)** — `/world leave`
  forwarded by proxy, moves player to holding area, and enqueues `EJECT_PLAYER`
  on `gzmn_proxy` for the proxy to transfer them back to lobby. FR-11 refusal
  branches safely bounce players back to lobby.

### Still to do in milestone 5

- ~~**MN-14 placement** is a stub that picks the least-loaded alive node after
  MN-28's version filter.~~ Done in milestone 8, along with the MN-16 defect the
  stub was hiding: it scored a node and only then checked the lease, which is
  indistinguishable from correct until a second node exists.

## Milestone 3 — visibility isolation: code complete, unverified

Plan [`03-visibility-isolation.md`](03-visibility-isolation.md).
`./gradlew check build` is green: 202 tests, none failing and none skipped.

- **FR-18** — hide/show recomputed in both directions on join and on every
  `PlayerChangedWorldEvent`. Both directions, because `hidePlayer` is one-way.
- **FR-19** — join, quit, death and advancement messages are suppressed
  globally and re-emitted to the group. Suppress-then-re-emit rather than
  filter-in-place, so a broadcast nobody remembered to route reaches *nobody*
  rather than everybody.
- **FR-20** — chat scoped by mutating `AsyncChatEvent`'s viewer set, never by
  cancelling and rebroadcasting, so other chat plugins keep their formatting.
- **FR-21 / FR-22** — command allow-list inside a player world, with
  `plugin:` prefixes stripped so `/minecraft:list` cannot walk around it.

The group rule and the case the spec leaves implicit: a player **not** in a
player world is a group of one. FR-11 answers it in passing — the holding area
"is not a world they can interact with or see anyone else from" — and it is the
safe direction, since grouping everyone outside a world together would put two
mid-join strangers in each other's tab list.

### Decisions taken (plan 03)

- **D11** — the allow-list always permits `/world` and `/pworld`.
  `worlds.allowed-commands` defaults to empty, which taken literally denies the
  command a player would use to *leave*, trapping them until they disconnect.
- **D12** — **FR-24d answered**: the network MOTD counts player-world players. A
  total reveals no identities, and a count that visibly dropped when somebody
  entered a private world would itself leak that they had.

### Deferred, with reasons rather than silence

- **FR-23** (player-count placeholders) needs PlaceholderAPI, a soft dependency
  on a plugin that may not be installed. `VisibilityGroups.groupCount` computes
  the number; the expansion waits for a scoreboard that wants it.
- **FR-24** (Discord bridge) is an integration with a plugin this repo does not
  have. The hook it needs — FR-19's group filter — now exists.
- **FR-24a** is a deployment rule: *do not install a network-wide tab list
  plugin*, because it overrides backend `hidePlayer` and breaks FR-18. Nothing
  in this plugin can prevent one being installed; belongs in a runbook.
- **FR-24b / FR-24c** cover third-party proxy commands that enumerate players.
  Our own `/world` completion is already scoped to the caller; `/glist` and
  friend/party plugins cannot be policed from inside our plugin.

### What must happen on a node

Spec §11.3 says it: **three accounts across two worlds and the lobby.**

- [ ] Two players in different worlds see neither each other's tab list entry,
      entity, chat, join/quit, nor death messages.
- [ ] Two players in the *same* world see all of it, and keep seeing it after
      one walks into the nether.
- [ ] A player in the holding area sees nobody.
- [ ] `/list`, `/msg` and `/minecraft:list` are denied inside a world; `/world`
      and `/pworld` still work; a `gzmn.worlds.admin` holder is exempt.

## Milestone 2 — membership, invites and roles: code complete, unverified

Plan [`02-membership-and-invites.md`](02-membership-and-invites.md).
`./gradlew check build` is green: 195 tests, none failing and none skipped.

The proxy is now a real plugin. It has `config.toml`, a database pool, the same
enable bootstrap the backend has, and it owns `/world` — **which resolves
OQ-15**. Subcommands implemented: `invite`, `accept`, `kick`, `members`,
`promote`. `create`, `join` and `browse` need placement and the transfer handoff
and stay on `/pworld` until milestones 5 and 8.

Roles are enforced, not merely stored (D10): a VISITOR cannot break or place
blocks and cannot open containers, while interact — buttons, levers, doors —
stays permitted per FR-9.

### What must happen on a node before this milestone is believed

Two servers are needed here, or two accounts on one:

- [ ] **Deploy the proxy plugin.** It has never run. Its `config.toml` needs a
      database URL and a `lobby-server` matching a server in `velocity.toml`.
- [ ] `/world invite`, then `/world accept` from the other account, then
      `/world members` shows both names.
- [ ] **Role enforcement.** `/world promote` and demote a member, then have them
      enter with `/pworld tp <owner> <name>` and confirm a VISITOR cannot break a
      block or open a chest, and a BUILDER can.
- [ ] `/world kick` removes the membership and the invite together.

### Known gaps, deliberate

- **A membership change does not reach an already-loaded world.** The node
  caches roles when the world loads and drops them when it unloads; the control
  plane's `INVALIDATE_CACHE` that would push a change through lands in milestone
  5. Until then a promote or kick takes effect on the world's next load. Stated
  here rather than buried, because it is surprising.
- **FR-8's immediate ejection is not wired.** Kicking removes the membership and
  the invite; ejecting a player who is *inside* the world at the time is the
  control plane's `KICK_MEMBER`, whose handler arrives with the transfer path.
- **The membership commands take no world name.** Section 6 gives them none
  either, so with a cap of two worlds they refuse rather than guess which world
  is meant. A world argument arrives with milestone 5.

### Found while building

- **Section 4 has no username storage anywhere**, while every section 6 command
  takes a player name. Added `player_name` (V2) as a proxy-filled cache, carried
  as **OQ-20**. Its one consequence: a player who has never logged in since the
  cache existed cannot be named as a command argument.
- The shaded-jar gate caught gson's Error Prone annotations arriving through
  toml4j — the fourth time that gate has paid for itself.

## Milestone 1 — world lifecycle: done, verified on a real node

Confirmed on the GZMN test instance (Paper 26.2-112, PostgreSQL 17.11): the
plugin enables, migrates V0 → V1, passes the capability probe, and
`/pworld create` generates a world whose **three dimensions all materialise** —
the overworld eagerly and the nether and end on first transit (FR-2, FR-3a,
FR-4). Portal linking resolving to the world's own dimensions is the part spec
section 11 said to do first because it was most likely to surprise; it did not.

Two things the first boot found, both now fixed and both invisible to any test
that does not involve a real server:

- The relocated JDBC driver never registered with `DriverManager`, so a node
  refused to enable with "No suitable driver" for a driver sitting in its own
  jar. Same trap existed a second time in the control-plane listener.
- `/pworld` is `default: op` and a fresh server has an empty `ops.json`. Paper
  hides commands the caller cannot use, so a permission denial is
  indistinguishable from an unregistered command. Enable now logs the
  registration and the permission it needs.

Still open from milestone 1, and worth doing while a world is in front of you:

- [ ] **OQ-17** — the end *generates*, but confirm a player arriving there lands
      on the obsidian platform rather than falling into the void.
- [ ] The `createWorld` stall number for `worlds.create-stall-budget-ms`. Every
      generation now logs it at INFO, so the next create prints it.
- [ ] Idle unload after ten minutes, and the FR-25a retry.
- [ ] Dragon fight state across an unload and reload (FR-3b).

## Milestone 1 — how it was built

Plan [`01-world-lifecycle.md`](01-world-lifecycle.md). `./gradlew check build` is
green: 168 tests across `:core`, `:backend` and `:testing`, none failing and
none skipped.

Taken as decisions before writing code, both recorded in plan 01 §1:

- **D7** — milestone 1 is database-backed, not the spec's "hardcoded owner, no
  database" staging. F3 already delivered `player_world` and its harness, so the
  in-memory version would have been a throwaway implementation of a tested table.
- **D8** — the backend command root is `/pworld`, not `/world`. The proxy claims
  `/world` in milestone 5 (OQ-15), and a registration here would have to be torn
  back to two subcommands.

### What must happen on a real node before this milestone is believed

These are the acceptance criteria, and none can be checked off a server. Spec §11
says to measure the stall *here*, which is the same point.

- [ ] **`/pworld create` end to end**, and the `createWorld` stall it reports.
      That number sets `worlds.create-stall-budget-ms` and is release-gating
      (FR-4). It is measured continuously into `create_stall_ms`, so a scrape
      after a few creates is the answer.
- [ ] **Portal linking both ways** (FR-3a) — overworld to nether and back with
      8:1 scaling, and the end in both directions. The routing maths is
      unit-tested; what is untested is the event surface.
- [ ] **The end arrival platform (OQ-17).** Vanilla generates it as part of its
      own end-portal handling. If it does not do so for a plugin-supplied
      destination world, a player entering the end falls into the void. **Check
      this one first** — it is the only item here that is unsafe rather than
      merely unmeasured.
- [ ] **Dragon fight state across unload and reload** (FR-3b). MockBukkit has no
      `DragonBattle`, so this has never been exercised.
- [ ] **Idle unload** after `worlds.idle-unload-minutes` (FR-25), and the FR-25a
      retry against a world held open by a force-loaded chunk.

### Found while building, and fixed

- **A `database.pool-size` below 4 deadlocked startup.** The migration advisory
  lock holds one connection while Flyway takes two; a pool of three or fewer
  waited for a connection that could not arrive, then failed the enable with a
  timeout message that named the wrong cause. `DatabaseSettings.MIN_POOL_SIZE`
  now refuses it up front. The default of 8 had hidden it.
- **The capability probe ran on the main thread.** Plan 00 §10.4's probe does a
  database round trip, a free-space stat and a reflink trial copy. `MainThread`
  caught it on the first real caller and turned an invisible startup stall into a
  refused enable; it now runs on the io pool under a budget.
- **A MockBukkit test can vanish into a skip.** `ServerMock.getWorldContainer()`
  throws `UnimplementedOperationException`, which MockBukkit reports as *skipped*
  rather than failed — the plugin smoke test silently stopped testing anything.
  `worldContainer()` is now a `protected` hook like `detectIdentity()`. The
  general caution is the point: anything load-bearing needs a real node or a
  `:core` test.
- **The backend was holding `java.sql.Connection`.** ArchUnit caught the
  orchestration composing transactions with `Connection`-taking lambdas.
  `PlayerWorldRepository` gained transaction-owning overloads; the rule now
  permits `SQLException` alone, since `:core`'s repositories declare it.

Not fixed, and deliberate: `assigned_node`, `lease_expires` and `generation` stay
at their defaults. MN-8's conditional `UPDATE` is the whole of the lease
guarantee, and a milestone-1 statement that set those columns without it would
read like a lease while providing none of one (plan 01 §5.3).

## First, in an environment that can reach repo.papermc.io — done

`:backend` and `:proxy` had never compiled, because the sandbox they were written
in blocks `repo.papermc.io`. They compile now.

- [x] `paperApi` and `velocityApi` set to Paper `26.2.build.112-stable` and
      Velocity `4.0.0`. Minecraft's versioning changed after 1.21.11 — releases
      are now year.season — so `paperApi` carries the Paper build number and the
      jar-name derivation reduces both coordinate shapes to the Minecraft version
      alone. Jars are `gzmn-worlds-0.1.0-SNAPSHOT+mc26.2.jar`.
- [x] **Java toolchain 21 → 25.** `paper-api` 26.x is published with a Java 25
      target and Gradle refuses to resolve it against a 21 toolchain, so this was
      not optional. ADR 0003 has a note; its "Java 21" title records the
      language decision, not the JDK number.
- [x] `./gradlew build` green. What the two plugin modules turned up, in order:
      `com.mojang:brigadier` (a `paper-api` transitive absent from Maven Central,
      so the papermc content filter had to allow `com.mojang`);
      `String#formatted` in `GzmnWorldsPlugin` (banned by forbidden-apis for
      using the default locale); and the packaging defects below. `ServerIdentity`
      and the Velocity `@Plugin` processor both compiled unchanged.
- [x] **Relocation was broken, and is the reason this list gained a gate.** Both
      jars shipped unrelocated Netty, Apache HttpClient 5, Jackson,
      reactive-streams and HdrHistogram, plus a second `org.slf4j` colliding with
      the one both platforms provide. The relocation list named only the direct
      dependencies, so every transitive escaped. Fixed, and `verifyShadedJar` now
      fails `check` if any class in a plugin jar sits outside
      `nl/gzmn/playerworlds/`. Jars went 29 MB → 22 MB.
- [x] `:backend:test` runs: `ArchitectureTest` 4/4, including the FR-25b rule
      that no field may hold a `World`. 13 tests green across `:core` and
      `:backend`.
- [x] **Both shaded jars load on a real Paper and Velocity and log `enabled`.**
      F1's acceptance criterion is met. Verified on the GZMN test instances
      (Pterodactyl on Unraid, Linux x86_64, Temurin 25.0.3): Paper
      `26.2-112-main`, implementing API version `26.2.build.112-stable` — exactly
      the pin — and Velocity `4.1.0-SNAPSHOT`. A player connected through the
      proxy to the backend and joined.
      - **This node's chunk `DataVersion` is 4903.** That is the number every
        decision in spec §12.9 is taken against (ADR 0001), and the first time it
        has been observed rather than assumed.
      - No duplicate-binding SLF4J warning and no Netty or Jackson complaint from
        either server, which is the relocation fix holding up in the one place
        that can actually prove it.
      - `plugin.yml` now expands `api-version` from the `paperApi` pin, so the
        descriptor cannot claim an older API than the jar was built for.

### Things found while doing it

- [ ] **zstd natives are 6.4 MB of the 22 MB jar**, covering eighteen
      platform/arch pairs including `aix/ppc64`, `linux/mips64`, `linux/riscv64`
      and `linux/loongarch64`. The F1 boot confirms nodes run **Linux x86_64**
      under Pterodactyl, so trimming to `linux/amd64` plus `linux/aarch64` is now
      a decision that can actually be taken, saving about 5 MB per jar. The one
      thing it would break is a developer running a Paper node on Windows or
      macOS, since the failure is at first compression rather than at startup.
      Decide alongside OQ-10.
- [ ] **The proxy runs Velocity `4.1.0-SNAPSHOT` while `velocityApi` pins
      `4.0.0`.** Deliberately left as is: compiling against a stable release and
      running a newer server is the safe direction, and pinning a SNAPSHOT would
      make the build non-reproducible, which §3 of the plan explicitly buys with
      the reproducible-jar work. Revisit only if 4.1.0 ships API the proxy needs.
- [x] **Relocating `net.logstash.logback` means a logback configuration must name
      the encoder by its relocated class**, not
      `net.logstash.logback.encoder.LogstashEncoder`. Documented under
      `config/logback/` (F8).

## Remaining foundation tasks

- [x] **F3** Database. Done: `V1__baseline.sql` (spec §4 verbatim — the version
      columns were already folded into v0.4 — plus `player_world_report` for
      FR-39 and `network_setting` for plan §8.1), `Database` on Hikari with
      autocommit off, `DbClock`, `AdvisoryLock` for the FR-40 election,
      `Repository` + `RowMapper` as the seam, `Schema` with the version guard, and
      13 Testcontainers tests against PostgreSQL 18.3. `:core:test` is 22 green.
      - The guard refuses **before** migrating when the schema is newer than
        `Schema.MAX_SUPPORTED`, which is the rolling-deploy case it exists for.
        Adding a migration means bumping `MAX_SUPPORTED` in the same commit.
      - Migrations run under `AdvisoryLock.MAINTENANCE_KEY`, bounded to 60s and
        refusing rather than hanging, per plan §6.
      - `Repository` takes a `Connection` per call rather than fetching its own,
        so MN-3a can commit a manifest pointer and its profiles in one
        transaction.
      - Testcontainers is declared on `:core`'s test classpath directly rather
        than via `:testing`, because `:testing` depends on `:core` and the
        reverse would be a cycle. F9's fixtures serve `:backend`, `:proxy` and
        e2e; `:core` owns the database and tests it itself.
- [x] **F4** Config. Done: typed `NodeConfig` / `ProxyConfig` / `StorageClientSettings`,
      `NetworkPolicy` with specification defaults, `NetworkSettings` (cache +
      `invalidate` for the control-plane `INVALIDATE_CACHE` command), and
      `ConfigValidator` for every §8.2 check. ADR 0007 records the three key
      reconciliations and answers OQ-16.
      - `worlds.storage-path` → `storage.local-scratch-path` (node-local).
      - `archive.s3.*` credentials → one `storage.s3.*` client with optional
        `archive-bucket` override.
      - `profiles.retain-snapshots` and `storage.manifest-retention-count` → one
        key, `storage.manifest-retention-count`; a leftover
        `profiles.retain-snapshots` row is refused at policy load.
      - Invalid config throws `ConfigException` so enable refuses rather than
        running with a default that silently violates a safety property.
- [x] **F5** Minecraft version seam. Done: `backend.platform` holds `WorldLayout`,
      `ItemCodec`, `WorldRuntime`, `PortalRouting` and `ServerIdentity`, selected
      by chunk data version through `Platform` at enable. ADR 0008 separates item
      NBT from the profile envelope's `format_version`.
      - `DefaultWorldLayout` encodes the Bukkit `DIM-1`/`DIM1` layout and the
        MN-2a required set (`region/`, `entities/`, `poi/`, `data/`, `level.dat`).
      - `PaperItemCodec` calls `serializeAsBytes` / `deserializeBytes` by name so
        a Paper rename is a compile failure; unit tests pin the signatures. A
        behavioural item byte round-trip still needs a running Paper node (the
        methods bottom out in the server bridge).
      - `PaperWorldRuntime.disableAlwaysLoadedSpawnChunks` is a documented no-op:
        Minecraft 1.21.9+ dropped always-loaded spawn chunks, so FR-25c is the
        platform default on this API line.
      - Unknown newer data version: warn and use the default layout. Older than
        `Platform.MIN_SUPPORTED_DATA_VERSION` (4903): refuse enable.
- [x] **F6** Threading foundation. Done: `core.concurrent` holds `MainThread`,
      `PluginExecutors` (main / db / io / sched), `BoundedOperations` and
      `MainScheduler`. `Database` calls `MainThread.assertOff()` on every entry
      point so JDBC on the tick thread fails the build (NFR-2). Ordered shutdown
      drains sched → db → io under a budget (FR-28's executor half). The Paper
      entry point marks the main thread at enable and opens the pools with
      specification defaults until config load is wired.
- [x] **F7** Control plane. Done: `core.control` protocol types (`CommandKind`,
      `NodeCommand`, `CommandHandler`, `CommandResult`, `ControlChannels`) and
      `ControlPlane` (poll + LISTEN, claim/complete, generation discard, unknown
      kind completes with error). `NodeCommandRepository` and
      `PgNotificationListener` stay in `core.db` so JDBC remains confined.
      - Insert and `pg_notify` share one transaction (CP-2 / ADR 0002).
      - Claim is a conditional `UPDATE` with claim-timeout reclaim (CP-5); two
        concurrent claimers never both run the handler.
      - Poll is the contract; killing the LISTEN connection still delivers via
        poll (CP-3). NOTIFY only shortens the wait.
      - No feature handlers yet — those arrive with the milestones that need them.
- [x] **F8** Observability. Done: `core.obs` holds MDC keys/`MdcContext`,
      `EventLogger` over the typed `LogEvent` set (NFR-6), `WorldsMetrics` with
      the §10.2 meter names on a Prometheus registry, `PrometheusEndpoint` on
      loopback:9464 by default, and `CapabilityProbe` (filesystem type, reflink
      verdict via `cp --reflink=always`, free space, optional DB/schema and
      storage health). Paper enable runs the probe and opens the scrape socket.
      Relocated Logstash encoder class names live in `config/logback/`.
- [x] **F9** Test harness. Done: `:testing` holds `TestDatabase` (pinned
      Postgres 18.3), `TestObjectStore` (pinned MinIO + path-style S3 client) and
      `WorldFixture` (synthetic MN-2a Anvil layout). One smoke per CI layer:
      unit (`WorldFixture`), database, object storage in `:testing`; architecture
      remains in the owning modules; MockBukkit plugin-surface smoke in
      `:backend`. `:core` keeps `TestPostgres` to avoid a dependency cycle.
- [x] **F10** CI/CD. Done: five workflows under `.github/workflows/` (`build`,
      `paper-latest`, `e2e`, `release`, `dependency-review`), Renovate with a
      `minecraft-update` group for Paper/Velocity/MockBukkit, licensee licence
      gate on `check`, CycloneDX SBOM on release, and `-PpaperApi=` override so
      the nightly compiles and boots against Paper's newest API/server without
      rewriting the catalog. Branch-protection settings are documented in
      `CONTRIBUTING.md` (GitHub UI; not codable here). The e2e workflow body
      landed with F11.
- [x] **F11** e2e docker compose harness. Done: `e2e/compose.yml` boots Postgres
      18.3, MinIO, Velocity and two Paper nodes; `:e2e-harness` answers `/e2e`
      over RCON and logs join markers; a minecraft-protocol bot joins the lobby
      through the proxy (handshake advertises protocol 776 with 26.1 packet
      schemas). Nightly workflow `e2e.yml` runs `e2e/scripts/run.sh`.
      Acceptance: one player joins a lobby in CI.
- [x] **F12** Durability primitives. Done: `core.storage` holds the reflink
      copier with plain fallback (`ReflinkFileCloner` / `PlainFileCloner`),
      `SnapshotCopier` (post-copy re-stat and bounded retry, MN-5a steps 4–5),
      `RegionStructure` (MN-5c Anvil header/sector/length checks), and
      `ContentHasher` (SHA-256 fused with optional region validation on one
      read, plan §9.1 step 7). Property tests flip every location-table byte on
      a synthetic `.mca` and assert rejection; a mid-copy mutation is detected
      and retried until settle or `UnstableFileException`.

Foundation complete. Spec milestone 1 followed; see the top of this file and
plan [`01-world-lifecycle.md`](01-world-lifecycle.md).

## Open questions, none blocking

Carried in the spec as OQ-13 to OQ-16 so they are not lost.

- [ ] **OQ-13** Replace MN-5b's chunk-save hook with a size and mtime stat walk
      against the last manifest? Same result, no coupling to server internals.
      Needed by milestone 6 (`FileFingerprint` from F12 is the comparison unit).
- [ ] **OQ-14** Retention period for FR-39's captured chat log — 30 days, 90, or
      until the report is marked handled? No longer blocks the schema: V1 carries
      `created_at` and `handled_at`, which is what any of the three answers sweeps
      on, and the period itself belongs in `network_setting` so it can change
      without a migration. Now needed by the F40 maintenance sweep instead.
- [x] **OQ-15** Resolved in milestone 2: the proxy owns `/world` and forwards a
      declared list to the backend. The list is empty until `/world leave` and
      `/world report` exist, but naming it in code is what stops either from
      being silently unreachable.
- [ ] **OQ-20** Is a proxy-filled `player_name` cache the right way to turn the
      player names in section 6's commands into the UUIDs section 4 stores?
- [ ] **OQ-17** Does Paper generate the end arrival platform when the
      destination world comes from a plugin rather than from its own portal
      search? Blocking for milestone 1's acceptance; see the checklist above.
- [ ] **OQ-18** Does an ARCHIVED world count against FR-1's per-player cap?
      Implemented as "no", because `/world delete` *is* the archival flow and
      would otherwise never free a slot. Confirm.
- [ ] **OQ-19** `storage.local-scratch-path` is necessarily the server's world
      container — Bukkit cannot create a world anywhere else. Matters beyond
      naming, because MN-13's quarantine and MN-5a's snapshot directory are
      specified relative to it and must share its filesystem.
- [x] **OQ-16** Answered by F4 / ADR 0007: network-wide policy lives in
      `network_setting`, read by both components through `NetworkPolicy`.
      Node-local facts stay in files.
- [ ] **OQ-10, OQ-12** Deployment facts: how many nodes at launch, and does
      MinIO share a host with them?
