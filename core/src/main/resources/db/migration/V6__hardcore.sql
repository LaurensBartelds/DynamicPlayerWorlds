-- V6. Hardcore worlds (FR-1b) and the permanent death they record (FR-5b).

-- Fixed at creation and never updated afterwards, which is why it is a column
-- rather than another key in the `settings` JSONB beside FR-9e/FR-9i. Those are
-- all things an owner may change at any time; standing this next to them would
-- say the opposite of what FR-1b means.
--
-- Defaulted false so every world that already exists reads back as an ordinary
-- world, which is what it is. NOT NULL because "unknown" is not a third kind of
-- world and a nullable flag would let one exist.
ALTER TABLE player_world ADD COLUMN hardcore BOOLEAN NOT NULL DEFAULT false;

-- FR-5b: when an OWNER or BUILDER dies in a hardcore world they may not return
-- to it. NULL means alive; a timestamp means dead, and the timestamp is
-- database time (DEFAULT/now() at the write site) rather than a value a node
-- supplies, for the reason in CONTRIBUTING.md rule 5.
--
-- Nullable rather than a boolean because when a death happened is worth having:
-- it is what an operator needs to answer "this member says they were killed by
-- the server crash on Tuesday", and it costs nothing over a flag. It also makes
-- the write idempotent for free -- the UPDATE that stamps it is conditional on
-- the column still being NULL (rule 7), so a retried delivery cannot move an
-- earlier death forward.
--
-- Meaningless for a world that is not hardcore, and deliberately not enforced
-- as such: the flag is on player_world and a CHECK cannot see across the two
-- tables. Readers gate on player_world.hardcore first.
ALTER TABLE player_world_member ADD COLUMN died_at TIMESTAMPTZ;

-- No index. The only question asked of died_at is "is this one member dead in
-- this one world", which the (world_id, uuid) primary key already answers; the
-- column is read from a row the caller has already located.
