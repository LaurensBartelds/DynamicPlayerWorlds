-- V2. A username cache, which specification section 4 never defines and which
-- section 6 assumes throughout.
--
-- Every management command in section 6 takes a player *name* — /world invite
-- <player>, /world kick <player>, /world ban <player> — while every table in
-- section 4 stores a UUID. FR-8's /world members has to render a list of them
-- back as names, and FR-24c wants to tab-complete on them. Nothing in the
-- schema can do either.
--
-- Resolving names through Mojang's API instead was rejected: it is a network
-- call on a command path, it rate-limits, and it fails exactly when the network
-- is unhappy, which is when an owner most wants to kick somebody. The proxy
-- already sees every login and already knows both halves.
--
-- This is a cache and is treated as one. It is never a foreign key, nothing
-- cascades from it, and a missing row degrades a display name to a UUID rather
-- than failing an operation. Names change; the UUID is the identity.

CREATE TABLE player_name (
  uuid       UUID PRIMARY KEY,
  name       TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Lookup by name is the hot direction: it is what every section 6 command does
-- with its first argument. Case-insensitive because Minecraft names are, and
-- unique on the folded name because two live accounts cannot share one.
CREATE UNIQUE INDEX player_name_lower_idx ON player_name (lower(name));
