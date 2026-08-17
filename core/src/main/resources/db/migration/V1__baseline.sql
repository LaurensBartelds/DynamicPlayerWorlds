-- V1 baseline. Specification v0.4 section 4 verbatim, plus the two tables the
-- specification requires but never defines (player_world_report, FR-39) or
-- introduces outside section 4 (network_setting, plan section 8.1).
--
-- This file is immutable once merged (CONTRIBUTING.md rule 6). Schema changes
-- are V2, V3, ... forever. Editing a merged migration leaves every database that
-- already applied it permanently inconsistent with the checksum Flyway recorded,
-- and the node that notices is the one that refuses to start.
--
-- Two conventions run through the whole file and are load-bearing:
--
--   * Every timestamp defaults to now(), which is *database* time. Node clocks
--     drift and every safety property in section 12.3 is a timestamp comparison,
--     so a node must never supply its own idea of the current time (MN-10b,
--     CONTRIBUTING.md rule 5).
--   * Every child table cascades from player_world. Deleting a world (FR-27)
--     must not leave orphan members, invites, profiles or commands behind, and a
--     cascade is the only version of that which cannot be forgotten by a caller.

-- ---------------------------------------------------------------------------
-- Worlds
-- ---------------------------------------------------------------------------

CREATE TABLE player_world (
  id            UUID PRIMARY KEY,
  owner_uuid    UUID NOT NULL,          -- authoritative owner; see FR-31a
  name          TEXT NOT NULL,
  folder        TEXT NOT NULL UNIQUE,   -- derived from id, never from name; see FR-2a
  seed          BIGINT NOT NULL,
  border_radius INT NOT NULL DEFAULT 5000,
  visibility    TEXT NOT NULL DEFAULT 'PRIVATE'
                CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
  description   TEXT,                   -- shown in the browse list
  settings      JSONB NOT NULL DEFAULT '{}'::jsonb,  -- per-world owner settings, FR-9e
  assigned_node TEXT,                   -- node holding the lease, NULL if unleased
  lease_expires TIMESTAMPTZ,            -- heartbeat extends this while leased
  generation    BIGINT NOT NULL DEFAULT 0,  -- bumped on every lease acquisition, used for fencing
  manifest_key  TEXT,                   -- current committed snapshot; NULL until first upload
  data_version  INT,                    -- chunk DataVersion of the last commit; NULL until first upload. See 12.9
  mc_version    TEXT,                   -- display only, e.g. '1.21.4'; never compared. See MN-27
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_played   TIMESTAMPTZ,
  state         TEXT NOT NULL
                CHECK (state IN ('CREATING', 'READY', 'ARCHIVING',
                                 'ARCHIVED', 'RESTORING')),
  UNIQUE (owner_uuid, name)
);

-- Placement (MN-14, MN-15) and the maintenance sweep both scan for worlds whose
-- lease has lapsed. Whether a world is loaded is derived from the lease and
-- never stored, precisely so a crashed node cannot leave a stale LOADED flag
-- behind (section 4, "Two derived facts").
CREATE INDEX player_world_lease_idx ON player_world (assigned_node, lease_expires);

-- /world browse (FR-9g) lists public worlds by recent activity.
CREATE INDEX player_world_public_idx
  ON player_world (last_played DESC) WHERE visibility = 'PUBLIC';

-- Auto-archival (FR-34) scans for worlds untouched for longer than the window.
CREATE INDEX player_world_owner_idx ON player_world (owner_uuid);

CREATE TABLE player_world_ban (
  world_id   UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  uuid       UUID NOT NULL,
  banned_by  UUID NOT NULL,
  reason     TEXT,
  banned_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (world_id, uuid)
);

CREATE TABLE player_world_ownership_log (
  id            BIGSERIAL PRIMARY KEY,
  world_id      UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  from_uuid     UUID NOT NULL,
  to_uuid       UUID NOT NULL,
  reason        TEXT NOT NULL,          -- MANUAL, ADMIN, INACTIVITY
  transferred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE player_world_archive (
  world_id     UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  object_key   TEXT NOT NULL,           -- path or object storage key, not the blob
  size_bytes   BIGINT NOT NULL,
  checksum     TEXT NOT NULL,           -- sha256 of the archive
  data_version INT NOT NULL,            -- chunk DataVersion the archive was packed at; see MN-29
  archived_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  restore_count INT NOT NULL DEFAULT 0,
  PRIMARY KEY (world_id, archived_at)   -- a world may be archived more than once
);

-- ---------------------------------------------------------------------------
-- Membership
-- ---------------------------------------------------------------------------

CREATE TABLE player_world_member (
  world_id   UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  uuid       UUID NOT NULL,
  role       TEXT NOT NULL CHECK (role IN ('OWNER', 'BUILDER', 'VISITOR')),
  invited_by UUID,
  joined_at  TIMESTAMPTZ,
  PRIMARY KEY (world_id, uuid)
);

-- "which worlds is this player in" is the hot lookup: the proxy runs it on
-- every join and on every tab completion (FR-24c). The primary key above
-- cannot serve it.
CREATE INDEX player_world_member_uuid_idx ON player_world_member (uuid);

CREATE TABLE player_world_invite (
  world_id   UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  uuid       UUID NOT NULL,
  invited_by UUID NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (world_id, uuid)
);

CREATE INDEX player_world_invite_uuid_idx ON player_world_invite (uuid);

-- ---------------------------------------------------------------------------
-- Player state
-- ---------------------------------------------------------------------------

-- One row per player per world snapshot. (generation, sequence) identifies
-- the snapshot this profile is consistent with; profiles are only ever
-- committed in the same transaction as that snapshot's manifest pointer.
-- See section 5.4 and MN-6a.
CREATE TABLE player_world_profile (
  world_id       UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  uuid           UUID NOT NULL,
  generation     BIGINT NOT NULL,
  sequence       INT NOT NULL,
  format_version INT NOT NULL,          -- FR-17
  data           BYTEA NOT NULL,        -- inventory, ender chest, xp, health, food, location
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (world_id, uuid, generation, sequence)
);

-- Pruning older snapshots (profiles.retain-snapshots) walks this index.
CREATE INDEX player_world_profile_snapshot_idx
  ON player_world_profile (world_id, generation, sequence);

CREATE TABLE player_world_transfer_request (
  world_id   UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  to_uuid    UUID NOT NULL,
  from_uuid  UUID NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,      -- transfers.pending-expiry-days, FR-32
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (world_id, to_uuid)
);

CREATE INDEX player_world_transfer_request_to_idx
  ON player_world_transfer_request (to_uuid);

-- Backs the resume prompt in FR-13. Written when a player leaves a world.
CREATE TABLE player_last_world (
  uuid     UUID PRIMARY KEY,
  world_id UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  left_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Nodes and the control plane
-- ---------------------------------------------------------------------------

CREATE TABLE worlds_node (
  node_id       TEXT PRIMARY KEY,
  address       TEXT NOT NULL,
  loaded_worlds INT NOT NULL DEFAULT 0,
  online_players INT NOT NULL DEFAULT 0,
  heap_percent  INT,
  tps           NUMERIC(4,1),
  draining      BOOLEAN NOT NULL DEFAULT false,
  data_version  INT NOT NULL,            -- this node's chunk DataVersion; placement filters on it (MN-28)
  mc_version    TEXT NOT NULL,           -- display only
  last_seen     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Directed commands from the proxy (or the maintenance job) to a specific
-- node. The durable row is the contract; pg_notify is only a latency
-- optimisation. See section 13.
CREATE TABLE node_command (
  id           BIGSERIAL PRIMARY KEY,
  target_node  TEXT        NOT NULL,
  world_id     UUID        REFERENCES player_world(id) ON DELETE CASCADE,
  generation   BIGINT,                   -- discard the command if the world has moved on (CP-4)
  command      TEXT        NOT NULL,
  payload      JSONB       NOT NULL DEFAULT '{}'::jsonb,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at   TIMESTAMPTZ NOT NULL,
  claimed_at   TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  attempts     INT         NOT NULL DEFAULT 0,
  result       TEXT
);

CREATE INDEX node_command_pending_idx
  ON node_command (target_node, id) WHERE completed_at IS NULL;

CREATE TABLE pending_transfer (
  uuid       UUID PRIMARY KEY,
  world_id   UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  node_id    TEXT NOT NULL,             -- node the proxy routed to
  generation BIGINT NOT NULL,           -- lease generation the route was resolved against
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Moderation (FR-39)
-- ---------------------------------------------------------------------------

-- FR-39 requires "a table network staff can read" and section 4 never defines
-- one. This is it.
--
-- The chat log is a JSONB column on the report row rather than an always-on chat
-- table, and that is the point: ordinary conversation inside a player world is
-- never persisted, and only the window around a report is retained. The
-- isolation in section 5.5 is deliberately total, so this is the single channel
-- out of a world and it should carry as little as it can while still being
-- useful to staff.
--
-- Retention is OQ-14 and still open (30 days, 90 days, or until the report is
-- handled). It does not block the schema: created_at and handled_at are what any
-- of those three answers sweeps on, and the period itself belongs in
-- network_setting so it can change without a migration.
CREATE TABLE player_world_report (
  id            BIGSERIAL PRIMARY KEY,
  world_id      UUID NOT NULL REFERENCES player_world(id) ON DELETE CASCADE,
  reporter_uuid UUID NOT NULL,
  target_uuid   UUID NOT NULL,
  reason        TEXT NOT NULL,
  chat_log      JSONB NOT NULL DEFAULT '[]'::jsonb,  -- group-scoped chat around the report
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  handled_at    TIMESTAMPTZ,
  handled_by    UUID,
  -- A report is handled by somebody, or by nobody, but never half of each.
  CHECK ((handled_at IS NULL) = (handled_by IS NULL))
);

-- The staff queue: open reports, oldest first. Partial, because a handled report
-- is history and history is not what staff page through.
CREATE INDEX player_world_report_open_idx
  ON player_world_report (created_at) WHERE handled_at IS NULL;

-- The retention sweep walks reports by age regardless of state.
CREATE INDEX player_world_report_created_idx ON player_world_report (created_at);

-- ---------------------------------------------------------------------------
-- Network policy (plan section 8.1)
-- ---------------------------------------------------------------------------

-- Network-wide policy lives in the database, not in each component's config
-- file. Specification section 7 puts caps and expiries in the backend's
-- config.yml, but the proxy is what enforces most of them —
-- worlds.max-per-player at /world create, invites.expiry-minutes at
-- /world invite, transfers.expiry-seconds at handoff. Two components holding
-- their own copies of shared policy can silently disagree, and in a pool of
-- interchangeable nodes several nodes can disagree with each other too.
--
-- One row per key, read by every component, changeable without a restart, and
-- auditable. Node-local facts — node.id, node.address, paths, pool size,
-- credentials — stay in files, because they are not shared and must be readable
-- before the database is.
--
-- Whether this replaces the config keys outright or shadows them is OQ-16, which
-- F4 answers. The table exists now so F4 has somewhere to land.
CREATE TABLE network_setting (
  key        TEXT PRIMARY KEY,
  value      JSONB NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by TEXT                        -- staff uuid or 'system'; NULL for seeded defaults
);
