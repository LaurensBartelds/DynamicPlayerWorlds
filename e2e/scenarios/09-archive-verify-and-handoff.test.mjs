import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withTestContext } from '../lib/test-context.mjs';

/**
 * Scenario 09: FR-35's archival, driven through `/world delete`.
 *
 * No scenario covered archival before this one, which is how R2 and R3 survived:
 *
 *  - R2 / FR-35: step 6 compared only the stored archive's *length* against the
 *    locally packed one, then steps 7 and 8 deleted the live folders and the
 *    whole per-world object prefix. At that moment the archive is the only copy
 *    of the world, and a corrupt part with the right length passes a length
 *    check. The checksum is now verified before anything is deleted.
 *
 *  - R3 / FR-35, MN-5a: the `WorldHandoff.Outcome` was discarded, so an archival
 *    whose unload was Blocked or whose final commit failed went on to pack a
 *    live, ticking world folder and then delete it from under three loaded
 *    Bukkit worlds.
 *
 * This drives the happy path — the one that has to keep working now that the
 * gate exists — and asserts the ordering FR-35 requires: the archive row and the
 * ARCHIVED state appear together, the live folders are gone from the node, and
 * the per-world live prefix is purged.
 */

async function waitFor(predicate, timeoutMs, label) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const value = await predicate();
    if (value) return value;
    await new Promise((r) => setTimeout(r, 750));
  }
  throw new Error(`Timed out after ${timeoutMs}ms waiting for: ${label}`);
}

const TAG = '09';

export async function run(ctx) {
  const worldName = 'archiveme';

  console.log('  [09] Spawning Alice...');
  let alice = await ctx.spawnBot('Alice');
  assert.ok(alice.connected, 'Alice should be connected');

  console.log(`  [09] Alice creating '${worldName}'...`);
  alice.runCommand(`/world create ${worldName}`);

  const world = await waitFor(
    async () => {
      const rows = await ctx.db.query('SELECT * FROM player_world WHERE name = $1', [worldName]);
      return rows.length > 0 ? rows[0] : null;
    },
    30000,
    `player_world row for '${worldName}'`
  );
  console.log(`  [09] world ${world.id} folder=${world.folder}`);

  await new Promise((r) => setTimeout(r, 4000));
  if (!alice.connected) {
    console.log('  [09] reconnecting Alice after the transfer...');
    alice = await ctx.spawnBot('Alice');
  }

  const node = await waitFor(
    async () => {
      const rows = await ctx.db.query('SELECT assigned_node FROM player_world WHERE id = $1', [world.id]);
      return rows[0]?.assigned_node || null;
    },
    30000,
    'the world to hold a lease on some node'
  );
  console.log(`  [09] world is leased to ${node}`);

  // Getting Alice *into* the world is explicit rather than assumed. `/world
  // create` routes her there itself, but that is a Velocity server switch: if
  // the bot's session is replaced it reconnects to the lobby, and the world then
  // sits at CREATING with a lease and no player to drive the load. Re-issuing
  // the join is idempotent and makes the scenario independent of whether the
  // create transfer survived.
  async function ensureAliceIsInTheWorld(assignedNode) {
    for (let attempt = 1; attempt <= 3; attempt++) {
      const status = await ctx.rcon(assignedNode, 'e2e status');
      if (status.includes(world.folder) && status.includes('Alice')) return;
      if (!alice.connected) {
        alice = await ctx.spawnBot('Alice');
      }
      console.log(`  [${TAG}] join attempt ${attempt}: /world join Alice ${worldName}`);
      alice.runCommand(`/world join Alice ${worldName}`);
      await new Promise((r) => setTimeout(r, 6000));
    }
    await waitFor(
      async () => {
        const status = await ctx.rcon(assignedNode, 'e2e status');
        return status.includes(world.folder) && status.includes('Alice');
      },
      30000,
      `Alice to be online on ${assignedNode} with ${world.folder} loaded`
    );
  }

  await ensureAliceIsInTheWorld(node);

  // The world must be READY and loaded: that is what makes this exercise R3's
  // handoff rather than archiving an already-cold world.
  const ready = await waitFor(
    async () => {
      const rows = await ctx.db.query('SELECT state, assigned_node FROM player_world WHERE id = $1', [world.id]);
      const row = rows[0];
      return row && row.state === 'READY' ? row : null;
    },
    60000,
    'the world to be READY'
  );
  console.log(`  [09] world is READY (leased to ${ready.assigned_node})`);

  // Let a first snapshot land, so there is a live object prefix for FR-35 to
  // purge. FR-1a commits one at creation, so this should not wait for the
  // periodic sync.
  const liveObjects = await waitFor(
    async () => {
      const keys = await ctx.s3.listObjects(`worlds/${world.id}/`);
      return keys.length > 0 ? keys : null;
    },
    120000,
    'a live snapshot prefix in object storage (MN-2, MN-3, FR-1a)'
  );
  console.log(`  [09] live prefix has ${liveObjects.length} object(s) before archival`);

  // ------------------------------------------------------------------------
  console.log('  [09] FR-27/FR-35: /world delete (archives, does not destroy)...');
  alice.runCommand(`/world delete ${worldName} confirm`);

  const archivedRow = await waitFor(
    async () => {
      const rows = await ctx.db.query('SELECT state, manifest_key, assigned_node FROM player_world WHERE id = $1', [
        world.id,
      ]);
      return rows[0] && rows[0].state === 'ARCHIVED' ? rows[0] : null;
    },
    120000,
    'the world to reach ARCHIVED'
  );
  console.log(`  [09] state=${archivedRow.state} manifest_key=${archivedRow.manifest_key}`);

  // FR-35's commit: the archive row and the ARCHIVED state land together.
  const archiveRows = await ctx.db.query(
    'SELECT object_key, size_bytes, checksum, data_version FROM player_world_archive WHERE world_id = $1',
    [world.id]
  );
  assert.equal(archiveRows.length, 1, 'expected exactly one player_world_archive row (FR-35)');
  const archive = archiveRows[0];
  console.log(`  [09] archive key=${archive.object_key} size=${archive.size_bytes} sha=${archive.checksum}`);
  assert.ok(String(archive.checksum).length === 64, 'the archive row must carry a sha256');
  assert.ok(Number(archive.size_bytes) > 0, 'the archive must not be empty');

  // R2's gate passed, so the artefact really is there and really does hash to
  // what the row claims — the node verified it before deleting anything. This
  // asserts the object survived the deletions that followed.
  const archiveObjects = await ctx.s3.listObjects(`worlds/${world.id}/archive/`);
  console.log(`  [09] archive prefix objects: ${JSON.stringify(archiveObjects)}`);
  assert.ok(
    archiveObjects.length > 0,
    `expected the archive artefact under worlds/${world.id}/archive/, saw ${JSON.stringify(archiveObjects)}`
  );

  // FR-35: the live prefix is purged only after the checksum verified.
  const liveAfter = await waitFor(
    async () => {
      const data = await ctx.s3.listObjects(`worlds/${world.id}/data/`);
      const manifests = await ctx.s3.listObjects(`worlds/${world.id}/manifest/`);
      return data.length === 0 && manifests.length === 0 ? { data: data.length, manifests: manifests.length } : null;
    },
    60000,
    'the live data/ and manifest/ prefixes to be purged (FR-35)'
  );
  console.log(`  [09] live prefix purged: ${JSON.stringify(liveAfter)}`);

  assert.equal(archivedRow.manifest_key, null, 'manifest_key must be cleared by the archival (FR-35)');
  assert.equal(archivedRow.assigned_node, null, 'the lease must be released last (FR-35, MN-12)');

  // R3: the world is no longer loaded on the node. If the handoff had been
  // ignored, the folders would have been deleted while it was still ticking.
  const status = await ctx.rcon(node, 'e2e status');
  console.log(`  [09] ${node} status after archival: ${status.trim()}`);
  assert.ok(
    !status.includes(world.folder),
    `world ${world.folder} must be unloaded after archival, but ${node} still lists it: ${status}`
  );

  console.log('  [09] Scenario 09 completed successfully.');
}

export default run;

const isDirect = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isDirect) {
  withTestContext(run)
    .then(() => {
      console.log('\x1b[32m09-archive-verify-and-handoff passed.\x1b[0m');
      process.exit(0);
    })
    .catch((err) => {
      console.error('\x1b[31m09-archive-verify-and-handoff failed:\x1b[0m', err);
      process.exit(1);
    });
}
