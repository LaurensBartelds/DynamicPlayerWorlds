# Implementation Plan 03 — Visibility Isolation

Status: in progress
Covers: spec milestone 3 (§11.3) — FR-18 to FR-24d
Predecessors: `01-world-lifecycle.md`, `02-membership-and-invites.md`

---

## 0. What this milestone is

Everything else in the specification assumes this works. FR-39 exists *because*
"the isolation in 5.5 is deliberately total"; FR-9h gates public worlds on that
isolation having been exercised; §5.5 calls a leak a defect rather than a
shortfall. It is the one milestone whose failure mode is silent — nothing breaks,
a player just sees somebody they should not.

A **visibility group** is one player world: all three dimensions as a single
unit. Moving between overworld, nether and end must not change who you can see.

Most cross-*backend* isolation is free, because a Paper backend only knows its
own players. The work is isolating one player world from another *within* the
`worlds` backend, plus closing the proxy-level leaks.

---

## 1. The group rule, and the case the spec does not name

Two players are mutually visible when they are in the same player world.

The case §5.5 leaves implicit is a player on the `worlds` backend who is **not
in a player world** — someone in the holding area, mid-join. FR-11 answers it
in passing: the holding area "is not a world they can interact with or see
anyone else from". So a player outside a player world is a group of one: they
see nobody, and nobody sees them.

That is also the safe direction to be wrong in. Grouping everyone outside a
player world together would put two mid-join strangers in each other's tab list,
which is precisely the leak this milestone exists to close.

---

## 2. What goes where

```
backend/world/
  VisibilityGroups.java      # the rule, pure and testable
  VisibilityListener.java    # FR-18 hide/show, FR-19 broadcasts, FR-20 chat
  CommandGuardListener.java  # FR-21 completion, FR-22 allow-list
```

FR-19 is the one that needs a rule rather than a list. The specification is
explicit that enumerating three broadcasts is not enough and that the list it
gives is "the known cases rather than the complete one", so the code states the
rule — *a broadcast that reaches every player on the server is a defect unless
it has been routed through the group filter* — and each handler cites it.

---

## 3. Decisions this milestone has to take

### D11 — the allow-list always permits this plugin's own commands

FR-22 makes command access inside a player world an allow-list, and
`worlds.allowed-commands` defaults to empty. Taken literally that denies
everything, including the command a player would use to leave — so a player who
enters a world is stuck in it until they disconnect.

The allow-list therefore governs vanilla and third-party commands, and this
plugin's own roots (`/world`, `/pworld`) are always permitted. They are the exit,
and they leak nothing: every one of them is already scoped to the caller.

### D12 — FR-24d: the network MOTD counts player-world players

FR-24d asks for a decision, not a fix, and says why: a total reveals no
identities. Counting them keeps the network's player count honest, and a count
that visibly dropped when somebody entered a private world would itself leak
that they had. Documented here as the choice, per the requirement.

---

## 4. Scope

In: FR-18, FR-19, FR-20, FR-21, FR-22.

Deferred, with reasons rather than silence:

- **FR-23** (player-count placeholders) needs PlaceholderAPI, which is a
  soft-dependency on a plugin that may not be installed. The group count it
  would expose is computed here and ready; the expansion itself waits until
  there is a scoreboard asking for it.
- **FR-24** (Discord bridge) is an integration with a plugin this repository
  does not have. The hook it needs is FR-19's group filter, which now exists.
- **FR-24a** is a deployment rule — *do not install a network-wide tab list
  plugin* — and belongs in the runbook, not in code. Nothing this plugin does
  can stop one being installed; what it can do is state that it would break
  FR-18.
- **FR-24b / FR-24c** cover proxy commands that enumerate players. Our own
  `/world` tab completion already suggests only online players and is scoped to
  the caller. Third-party `/glist`, friend and party plugins cannot be policed
  from inside our plugin; that is also a deployment rule.

---

## 5. Work breakdown

| ID | Task | Done when |
| --- | --- | --- |
| M3-1 | `VisibilityGroups` | Unit tests: three dimensions are one group, two worlds are not, outside-a-world is alone |
| M3-2 | `VisibilityListener` — FR-18, FR-19, FR-20 | Hide/show recomputed on join and on every world change; join, quit, death and advancement broadcasts group-scoped |
| M3-3 | `CommandGuardListener` — FR-21, FR-22 | Allow-list enforced, admins exempt, own roots always permitted |
| M3-4 | Docs — decisions above, `NEXT-STEPS`, `CHANGELOG` | FR-24a/24d recorded as the deployment rules they are |
