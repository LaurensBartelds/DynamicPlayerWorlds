# Implementation Plan 06 — Paid Tiers

Status: **T1–T9 landed**, `./gradlew check` green. See §4 for where the
implementation differs from §2, and §5 for what that left open (nothing).
Covers: FR-3c and FR-41–FR-47, added to the specification by this change
Spec baseline: `docs/spec/v0.4.md` (§2, §4, §5.1, §5.10, §6, §7 amended)
Predecessors: `05-audit-remediation.md`

---

## 0. What this plan is

The network wants to sell two things: a one-time upgrade to one world's storage
and border, and a subscription that raises every world a player owns along with
how many they may own.

Most of the machinery already exists. `gzmn.worlds.storage.<size>` has resolved
a per-player storage allowance from permissions since milestone 8, through
`StorageQuotaResolver` and the proxy's `StorageTiers`, with the
enumerable-versus-probed split that LuckPerms' absence forces. The subscription
half of this plan is that mechanism applied to two more dials. It needs no new
table and no new state.

The one-time half is the part that does not fit, and it is worth being precise
about why, because the reason decides the design:

- **A permission cannot name a world.** `gzmn.worlds.border.10000` says
  something about a player. "This world's border is 10000 because it was paid
  for" says something about a world, and a permission string has nowhere to put
  a world id.
- **A permission is the wrong lifetime.** The permission backend is
  authoritative about whether a subscription is still being paid for, and
  correctly revokes when it is not. A purchase must survive exactly that event.

So one-time upgrades get a table (`world_upgrade`) and subscriptions do not.

Three consequences of the spec amendment are worth flagging before the tasks,
because they are the parts that touch requirements that were previously closed:

1. **FR-3 said borders were fixed after creation** and §2 listed resizing as a
   non-goal. FR-3c reopens it in the raise-only direction. Lowering stays
   forbidden, and not as a simplification: shrinking a border strands whatever
   was built in the ring it removes.
2. **The storage allowance stays a per-player pool.** A `STORAGE` upgrade adds
   bytes to the pool and records which world prompted it; it does not create a
   per-world limit. A per-world limit would need its own enforcement point on
   every snapshot commit, and there is no requirement asking for one.
3. **`world_upgrade` is the one child of `player_world` that does not cascade.**
   Every other one does, and CONTRIBUTING is emphatic about why. This one must
   not, because a cascade here deletes something a player paid for when they
   delete a world (FR-47).

## 1. Ordering

Core first, because all of it is testable without a server and the two plugins
both depend on it. Then the proxy, which owns every command that enforces an
allowance. Then the two GUI paths, which only display what the proxy decides.

| Task | What | Requirement |
| --- | --- | --- |
| T1 | `V7__world_upgrade.sql` | §4, FR-44, FR-47 |
| T2 | `WorldUpgrade`, `UpgradeKind`, `WorldUpgradeRepository` | FR-44, FR-45, FR-47 |
| T3 | `EntitlementTiers`; `StorageQuota.bonusBytes` | FR-42, FR-43 |
| T4 | `NetworkPolicy` keys and `ConfigValidator` rules | §7, FR-3c |
| T5 | Proxy: effective slot and storage allowance at create and restore | FR-1, FR-42, FR-46 |
| T6 | Proxy: `/world border` | FR-3c |
| T7 | Proxy: `/world upgrades`, `/world admin upgrade` | FR-44, FR-45 |
| T8 | Backend: `APPLY_SETTINGS` re-asserts a changed border | FR-3c |
| T9 | Both GUIs: show the allowance, redeem from the world menu | FR-45 |

## 2. Tasks

### T1 — `V7__world_upgrade.sql`

Creates the table in §4 verbatim, with `ON DELETE SET NULL` on `world_id` and
`UNIQUE` on `reference`.

**Acceptance:** Flyway applies it on a fresh database and on one at V6.
Deleting a world with a redeemed upgrade leaves the upgrade row with
`world_id IS NULL`, proven by a Testcontainers test rather than by reading the
DDL (FR-47).

### T2 — Model and repository

`WorldUpgrade` is a value object over one row, `UpgradeKind` is
`STORAGE | BORDER`. `WorldUpgradeRepository` gets:

- `grant(...)` — idempotent on `reference`. A repeat returns the existing row
  and reports that it already existed, rather than throwing on the constraint
  and leaving the caller to guess which failure it was (CONTRIBUTING rule 7).
- `redeem(id, worldId)` — one conditional `UPDATE` on `world_id IS NULL AND
  redeemed_at IS NULL`, so a double click redeems once.
- `listOwnedBy(uuid)`, `bonusStorageBytes(uuid)`, `borderBonusFor(worldId)`.

**Acceptance:** Testcontainers tests for each, including a concurrent redeem of
one upgrade from two connections that produces exactly one redemption.

### T3 — `EntitlementTiers` and the storage bonus

`EntitlementTiers` resolves `gzmn.worlds.slots.<n>` and
`gzmn.worlds.border.<radius>` the way `StorageQuotaResolver` resolves storage:
an enumerated overload for LuckPerms and a probed overload for a permission
backend that can only answer one node at a time. Highest held wins; a tier
below the network default loses to the default (FR-42).

`StorageQuota` gains `bonusBytes`, and the effective limit becomes
`limitBytes + bonusBytes`. A field rather than a pre-summed limit, so the GUI
can say "5 GB, plus 2 GB purchased" — and so every construction site has to be
revisited by the compiler rather than by grep.

**Acceptance:** unit tests in `:core` for the resolver ladder, the default
floor, and `isExceeded`/`percentage` against a bonus.

### T4 — Configuration

`worlds.slot-tiers`, `worlds.border-tiers`, `worlds.max-border-radius`
(default 25000). `ConfigValidator` rejects a `max-border-radius` below
`default-border-radius`, which would make every world in the network
un-enlargeable and is always a mistake.

### T5–T7 — Proxy

The cap at `/world create` becomes the effective cap rather than
`policy.maxWorldsPerPlayer()`, in all four places `WorldActions` and
`WorldCommand` check it. The storage quota picks up the bonus in `quotaFor`.

`/world border <radius> [world]` resolves its world through §6.1 like every
other owner command, refuses a radius below the current one with FR-3c's
reason stated, refuses one above the entitlement or above
`worlds.max-border-radius`, writes `player_world.border_radius`, and enqueues
`APPLY_SETTINGS` for the holding node if there is one.

### T8 — Backend

`LoadedWorld.borderRadius` becomes volatile and updatable, for the same reason
`settingsJson` is: a dimension materialised after the change must apply the new
radius, not the load-time one. `ApplySettingsHandler` re-asserts the border
across every materialised dimension alongside the gamerules it already
re-asserts.

### T9 — GUI

The storage screens show the bonus as its own line. The world menu grows a
redeem entry per kind, drawn only when the viewer owns an unredeemed upgrade of
that kind, spending the oldest of them on the world being managed.

## 3. What this plan does not do

- **No payment handling.** Nothing in this plugin sees a price, a currency or a
  transaction. `/world admin upgrade grant` is the whole integration surface,
  and a webstore calls it as console (FR-44). §2 still lists in-game economy
  integration as out of scope.
- **No per-world storage limit.** See §0 point 2.
- **No expiry stored for subscriptions.** The permission backend owns that
  (FR-41).
- **No border shrinking**, at any privilege level. An admin who must undo an
  enlargement edits the row.

## 4. What landed, and where it differs from §2

- **T1–T8 as written.** One addition worth naming: `world_upgrade` carries a
  `CHECK ((world_id IS NULL) = (redeemed_at IS NULL))` and a `BEFORE DELETE`
  trigger on `player_world`. The foreign key's own `ON DELETE SET NULL` clears
  `world_id` and would leave `redeemed_at` behind, tripping that check; the
  trigger clears the pair together. Half a redemption would leave every reader
  to decide for itself which of the two columns means "redeemed".
- **`StorageQuota` has no four-argument constructor.** Every construction site
  states the purchased bytes, because a site that forgets refuses a create to
  somebody who paid for the space, and a defaulted zero would compile.
- **The proxy's test mocks changed.** They answered `true` to every permission,
  which under FR-43 reads as a player holding the top slots tier — so the cap
  tests stopped hitting a cap. They now answer `true` to every *ordinary* node
  and no tier. This is worth knowing before writing another one.

## 5. Still open

Nothing from §4 is outstanding. The two gaps this section used to record are
closed:

- **T9's menu half.** `MenuViewService` and the backend `MenuService` load the
  viewer's unspent upgrades and the world menu draws a *Spend Storage Upgrade*
  entry at slot 22 and a *Spend Border Upgrade* at slot 23, each only for a kind
  the owner actually holds. A click sends `MenuIntent.RedeemUpgrade(worldId,
  kind)` (codec byte 17), which the proxy answers by spending the oldest
  unredeemed upgrade of that kind — `WorldUpgradeRepository.listUnredeemed`
  orders them for exactly this. Neither entry is drawn for a viewer who is not
  the owner, and the click path refuses them there too (FR-31a), so the
  screen is not the only thing standing between a visitor and somebody else's
  purchase. `/world upgrades redeem <id> [world]` remains the way to spend one
  specific upgrade.
- **Tier resolution in the menus.** `MenuViewService` now takes the `Player` and
  shares `WorldActions`' `StorageTiers`, so a screen resolves a subscription
  exactly as the command does: enumerated through LuckPerms where it is
  installed, probed against the configured tiers where it is not. A
  `gzmn.worlds.slots.7` node that no operator named in `worlds.slot-tiers` now
  reaches the screens as well as the commands (FR-43). `StorageTiers` grew a
  `PermissionEnumerator` seam so the enumerated route is testable without a
  LuckPerms instance.

## 6. Open question

- **OQ-P1: what an operator should do about a subscriber who lapses while over
  the slot cap.** FR-46 settles the behaviour — they keep every world and may
  not create another — but not whether staff want a report of who is in that
  state. Deferred until there are subscribers to report on.
