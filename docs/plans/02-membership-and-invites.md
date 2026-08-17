# Implementation Plan 02 — Membership, Invites and Roles

Status: in progress
Covers: spec milestone 2 (§11.2) — membership and invites, still single-server
Spec baseline: `docs/spec/v0.4.md`
Predecessors: `00-repo-foundation.md` (F0–F12), `01-world-lifecycle.md` (M1)

---

## 0. What this milestone is

Milestone 1 gave a world a lifecycle. This gives it *people*.

Requirements in scope:

| Area | Requirements |
| --- | --- |
| Invites | FR-6 (invite, expiry, notify), FR-7 (accept) |
| Membership | FR-8 (kick, members), FR-31a (`owner_uuid` is authoritative) |
| Roles | FR-9 (OWNER / BUILDER / VISITOR), and their **enforcement** in world |
| Proxy | enough of the proxy to host the commands section 6 puts there |

Deliberately not in scope: visibility isolation (milestone 3), profiles
(milestone 4), `pending_transfer` handoff and placement (milestones 5 and 8),
public worlds, `/world browse` and bans (milestone 9).

---

## 1. Two consequences of decisions already taken

### D9 — the commands live on the proxy, which pulls its foundation forward

Specification §6 registers management commands on the proxy, and FR-6 gives the
reason in one line: the invite notification has to reach a player who may be on
any server, and *"a backend node cannot message across the network by itself"*.
A backend-side `/world invite` would work only when both players happened to be
on the same node, which is the case that needs it least.

So milestone 2 builds the proxy foundation that milestone 5 was going to:
`config.toml`, a database pool, and the enable bootstrap. That is more than spec
§11.2 implies, and it is the honest cost of putting the commands where §6 says
they go rather than writing them twice.

**This settles OQ-15.** The proxy owns the `/world` root. The two backend-side
entries in §6 — `/world leave` and `/world report` — are reached by the proxy
forwarding a known list, and neither is implemented yet (milestones 5 and 9),
so the forwarding list starts empty and the mechanism is designed in rather
than discovered.

### D10 — roles are enforced here, not left as data

FR-9 says all three roles ship in v1 *"because public worlds depend on VISITOR
being a real role rather than a placeholder"*. Storing a role without enforcing
it means milestone 3's isolation testing runs against worlds where the roles do
nothing, and milestone 9 inherits an untested permission model at the same
moment it first admits strangers.

Enforcement is a backend concern — it is block breaking, block placing and
container access — so it lands on the node while the commands that *set* roles
land on the proxy.

---

## 2. What goes where

```
core/
  model/Role.java                 # OWNER | BUILDER | VISITOR, with the FR-9 capabilities
  model/WorldMember.java          # a player_world_member row
  model/WorldInvite.java          # a player_world_invite row
  db/MembershipRepository.java    # members and invites, one seam
proxy/
  config/ProxyConfigLoader.java   # config.toml -> ProxyConfig
  GzmnWorldsProxyPlugin.java      # enable bootstrap, mirroring the backend's
  command/WorldCommand.java       # the /world root (Brigadier)
backend/
  world/MembershipCache.java      # per-world roles, main-thread readable
  world/RoleEnforcementListener.java  # FR-9 in world
```

`:core` holds every membership *rule*, and both plugins call it. That is the
point of the split: `worlds.max-per-player` and `invites.expiry-minutes` are
already one value in `network_setting` (ADR 0007), and the logic that reads them
should be one implementation too.

---

## 3. The shape of each path

### 3.1 Invite and accept (FR-6, FR-7)

`/world invite <player>` on the proxy: verify the caller owns the world
(`owner_uuid`, FR-31a), write a `player_world_invite` row expiring in
`invites.expiry-minutes`, and notify the target **if they are online anywhere on
the network** — which is the whole reason this is a proxy command.

`/world accept <owner>` promotes the invite to a `player_world_member` row with
role BUILDER and deletes the invite, in one transaction. FR-7 also says it
"sends them to the world"; that is the `pending_transfer` handoff and belongs to
milestone 5, so milestone 2 confirms the membership and says where to go.

Expiry is evaluated in **database time**, in the SQL predicate, never by
comparing against a node clock (CONTRIBUTING rule 5).

### 3.2 Kick and members (FR-8)

`/world kick <player>` removes the membership row. FR-8 requires an online
member be removed from the world immediately and returned to lobby — that is
the control plane's `KICK_MEMBER`, which F7 already built the transport for and
which milestone 5 wires to an actual ejection. Milestone 2 removes the
membership and enqueues the command; the handler lands with the transfer path.

`/world members` is a plain read, ordered owner first.

### 3.3 Roles (FR-9) and their enforcement

| Role | Build and break | Containers | Interact |
| --- | --- | --- | --- |
| OWNER | yes | yes | yes |
| BUILDER | yes | yes | yes |
| VISITOR | no | only if the owner enables it (FR-9e) | yes |

A non-member inside a player world is treated as a visitor with nothing
enabled, which is the safe direction until milestone 3 makes it impossible to
be there at all.

Enforcement reads a role on the main thread, so it cannot query
(NFR-2). `MembershipCache` holds the roles for each loaded world, filled when
the world loads and invalidated over the control plane when membership changes.
This is the same shape Q3 in plan 00 predicted for the proxy's tab completion,
arriving first on the node.

`owner_uuid` beats the `OWNER` role value in every disagreement (FR-31a), so the
cache stores the owner separately rather than trusting a denormalised row.

---

## 4. Findings and open questions

Recorded here as they arise; folded into the spec as OQ numbers when they are
questions rather than choices.

---

## 5. Work breakdown

| ID | Task | Done when |
| --- | --- | --- |
| M2-1 | `core`: `Role`, `WorldMember`, `WorldInvite`, `MembershipRepository` | Testcontainers tests: invite expiry in DB time, accept is atomic, owner precedence |
| M2-2 | Proxy foundation: `config.toml`, database, enable bootstrap | An invalid config refuses enable; a valid one migrates nothing and reads policy |
| M2-3 | Proxy `/world` command tree: invite, accept, kick, members | Commands run against a real database in tests |
| M2-4 | Backend `MembershipCache` + `RoleEnforcementListener` | Unit tests over the capability matrix; no query on the tick thread |
| M2-5 | Backend `/pworld tp <owner> <name>` for members | A member can enter another player's world, so enforcement is testable single-server |
| M2-6 | Docs — `NEXT-STEPS`, `CHANGELOG`, OQ-15 resolution | This file's decisions reflected in the spec |
