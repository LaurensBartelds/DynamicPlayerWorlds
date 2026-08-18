-- V3. What MN-15a's warm-copy preference needs, which section 4 does not store.
--
-- MN-15a makes "a node that already holds a local copy matching the world's
-- current manifest" the single largest lever on join latency, and therefore the
-- heaviest scoring term in placement. Nothing in section 4 says which node that
-- is: `assigned_node` is the *current* lease holder and is NULL for exactly the
-- worlds placement is being asked about, since by FR-25 a world is unloaded most
-- of the time.
--
-- The node that wrote `manifest_key` is the answer. MN-5 retains its local files
-- as a warm cache after unload, so that node can turn a cold load into a warm
-- one. It is a preference and never a constraint: the copy may have been evicted
-- by `storage.local-cache-max-gb`, quarantined by MN-10, or lost with the node's
-- disk. Placement that guessed wrong pays a cold load, which is the same cost it
-- would have paid anyway.
--
-- Written by the snapshot commit (MN-3a) in the same conditional UPDATE that
-- moves the manifest pointer, so it can never name a node whose commit was
-- fenced.
ALTER TABLE player_world ADD COLUMN last_node TEXT;

-- Placement asks "who wrote this world's current snapshot" for one world at a
-- time, which the primary key already answers. What needs an index is the other
-- direction: MN-15a's public/private separation counts the live leases a node
-- holds, grouped by visibility, once per placement decision.
CREATE INDEX player_world_occupancy_idx
  ON player_world (assigned_node, visibility) WHERE assigned_node IS NOT NULL;
