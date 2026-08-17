# 0008. Item serialisation uses Paper NBT bytes; format_version is our envelope

* Status: Accepted
* Date: 2026-08-17
* Plan: F5 / section 5.5
* Spec: FR-14, FR-16, FR-17, FR-17a

## Context

`player_world_profile.data` is the one place Minecraft's own item format is
stored outside a world folder. A profile written under one server version has to
deserialise on the next, or FR-16 locks the player out of their world until an
admin rolls them back (FR-16a).

Two layers are easy to conflate:

1. **Item NBT** — the bytes of each `ItemStack` (and of inventory slot arrays).
2. **Our envelope** — which fields sit around those blobs, in what order, with
   what meaning (inventory, armour, ender chest, xp, health, location, …).

FR-17's `format_version` column exists so the envelope can migrate. It does not
describe the item NBT. Treating them as one version number is how the migration
story gets lost: either every Minecraft upgrade forces an envelope bump, or an
envelope change pretends to be an item-format change and skips DataFixerUpper.

## Decision

**Item bytes go through Paper's `ItemStack#serializeAsBytes()` /
`ItemStack#deserializeBytes(byte[])` (and the array helpers
`serializeItemsAsBytes` / `deserializeItemsFromBytes`).** They produce
version-tagged NBT that Mojang's DataFixerUpper migrates on read. A profile
written under 26.2 deserialises on a newer Paper without us writing an item
migration.

**Do not use** `BukkitObjectOutputStream`, YAML `ConfigurationSerializable`, or
a hand-rolled item encoder. Those are Bukkit-version-coupled and have broken
across updates before; FR-16 turns a deserialisation failure into a lockout.

**`format_version` tags only our envelope.** The column is outside the payload
so a blob that cannot be parsed at all can still be identified and migrated
(FR-17). Bumping it means the set or order of fields around the item blobs
changed, not that Minecraft did.

**The codec lives in `backend.platform.ItemCodec`.** `:core` stores and loads
`BYTEA`; it never imports `ItemStack`. That keeps profile durability logic
testable without a server and keeps the version-sensitive call in the one
package ArchUnit confines Minecraft knowledge to.

## Consequences

* A Minecraft upgrade that renames the Paper methods is a compile failure in
  `PaperItemCodec`, which is the pin F5 holds. Behavioural round-trip of a real
  item still needs a running Paper node (the methods bottom out in the server's
  bridge); the unit test pins signatures on the API jar.
* Envelope migrations are ordinary application code keyed by `format_version`.
  Item migrations are Mojang's problem via DataFixerUpper.
* Profiles remain `BYTEA`, not `JSONB` (FR-17a): item NBT contains null bytes
  that PostgreSQL rejects inside `jsonb` strings.
