-- V5. FR-34's archival warnings, and somewhere to put a message for a player
-- who is offline.

-- Which warning threshold this world's owner has already been told about, in
-- days-before-archival. NULL means none yet. The FR-40 sweep runs every five
-- minutes by default, so without this it would re-send the same warning
-- seventeen thousand times over the fortnight FR-34 gives it.
--
-- Cleared whenever last_played moves: a world that is played again and then
-- goes quiet again earns its warnings a second time.
ALTER TABLE player_world ADD COLUMN archive_warned_days INT;

-- FR-34 warns the owner "in game", and the owner is offline by definition --
-- that is why the world is being archived. The message therefore has to
-- outlive the sweep that wrote it and wait for a login.
--
-- Not a node_command on gzmn_proxy: those are claimed by the control-plane
-- poll the moment they appear (CP-2), so a notice meant to sit for days would
-- be claimed within seconds and completed as "no handler" (CP-6) -- and CP-7's
-- sweep, which now runs, deletes finished and expired rows. A command is an
-- instruction to act on state committed elsewhere; this is the state.
CREATE TABLE player_notice (
  id           BIGSERIAL   PRIMARY KEY,
  uuid         UUID        NOT NULL,
  world_id     UUID        REFERENCES player_world(id) ON DELETE CASCADE,
  message      TEXT        NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  delivered_at TIMESTAMPTZ
);

-- The only read on the hot path is "what is waiting for this player", on login.
CREATE INDEX player_notice_undelivered_idx
  ON player_notice (uuid, id) WHERE delivered_at IS NULL;
