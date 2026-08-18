-- V4. Storage accounting for live snapshots and player quotas.
ALTER TABLE player_world ADD COLUMN storage_bytes BIGINT NOT NULL DEFAULT 0;
