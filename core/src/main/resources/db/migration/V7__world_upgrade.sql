-- V7. One-time purchased world upgrades (FR-44, FR-45, FR-47).
--
-- The half of section 5.10 that cannot be a permission. A subscription is
-- granted and revoked by the network's permission backend and has no state
-- here on purpose (FR-41); what needs a table is the one-time purchase, for
-- two reasons that a permission string cannot answer:
--
--   * It applies to one world, and a permission node has nowhere to put a
--     world id. `gzmn.worlds.border.10000` is a statement about a player.
--   * It must outlive the grantor. A subscription that stops being paid for
--     should stop applying; a purchase that has been paid for once should not.

CREATE TABLE world_upgrade (
  id          UUID PRIMARY KEY,
  owner_uuid  UUID NOT NULL,
  world_id    UUID REFERENCES player_world(id) ON DELETE SET NULL,
  kind        TEXT NOT NULL CHECK (kind IN ('STORAGE', 'BORDER')),
  amount      BIGINT NOT NULL CHECK (amount > 0),
  reference   TEXT NOT NULL UNIQUE,
  granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  redeemed_at TIMESTAMPTZ,

  -- world_id and redeemed_at are set by the same statement and cleared by the
  -- same cascade, so a row can only be unredeemed or redeemed and never half of
  -- each. Without this, ON DELETE SET NULL below would leave rows carrying a
  -- redeemed_at and no world, and every reader would have to decide for itself
  -- which of the two columns means "redeemed" (FR-45, FR-47).
  CONSTRAINT world_upgrade_redemption_consistent
    CHECK ((world_id IS NULL) = (redeemed_at IS NULL))
);

-- NOTE: this is the one child of player_world that does NOT cascade, and the
-- exception is deliberate. Every other child table in V1 cascades because
-- deleting a world (FR-27) must not leave orphans behind. Here a cascade would
-- delete something a player paid for, at the moment they delete the world they
-- spent it on. ON DELETE SET NULL returns the upgrade to unredeemed instead, so
-- they can spend it again (FR-47). The CHECK above is what makes that safe: the
-- database clears world_id, and the constraint is what stops redeemed_at
-- surviving it -- so the trigger below exists to clear the pair together.
CREATE OR REPLACE FUNCTION world_upgrade_release() RETURNS TRIGGER AS $$
BEGIN
  UPDATE world_upgrade
     SET world_id = NULL, redeemed_at = NULL
   WHERE world_id = OLD.id;
  RETURN OLD;
END;
$$ LANGUAGE plpgsql;

-- BEFORE DELETE, so the rows are already released by the time the foreign key's
-- own SET NULL would fire and trip the consistency CHECK.
CREATE TRIGGER world_upgrade_release_on_world_delete
  BEFORE DELETE ON player_world
  FOR EACH ROW EXECUTE FUNCTION world_upgrade_release();

-- "what has this player bought, and what is still unredeemed" is read on every
-- storage evaluation and on every /world upgrades. The primary key cannot serve
-- it: upgrades are found by owner, never by their own id except when redeeming
-- one the player has just been shown.
CREATE INDEX world_upgrade_owner_idx ON world_upgrade (owner_uuid);

-- A BORDER upgrade is read back for one world when its allowance is worked out.
CREATE INDEX world_upgrade_world_idx ON world_upgrade (world_id) WHERE world_id IS NOT NULL;
