import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withTestContext } from '../lib/test-context.mjs';

/**
 * Scenario 10: `/world admin migrate` with a player inside (MN-19, MN-21, FR-15).
 *
 * Named in docs/plans/NEXT-STEPS.md as milestone 8's core acceptance case and,
 * as of this scenario, still unverified anywhere: "`/world admin migrate <id>
 * <node>` with a player inside: countdown shown, player to lobby, snapshot
 * committed, lease on the target, and the player's inventory intact when they
 * rejoin."
 *
 * Drives the whole handoff for real: gives Alice a marker item while she is
 * inside the world (so it is part of *this world's* profile, not the lobby
 * inventory), migrates the world to the other node while she is still in it,
 * and asserts every step MN-19's handoff promises — the countdown message,
 * the world unloading on the source, the lease moving, and finally the same
 * marker item surviving the commit-and-cold-load round trip when she rejoins
 * on the new node.
 */

async function waitFor(predicate, timeoutMs, label) {
  const started = Date.now();
  let last;
  while (Date.now() - started < timeoutMs) {
    last = await predicate();
    if (last) return last;
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`Timed out after ${timeoutMs}ms waiting for: ${label}`);
}

const TAG = '10';
const MARKER_ITEM = 'diamond';
const MARKER_COUNT = 5;

export async function run(ctx) {
  const worldName = 'migrateworld';

  console.log(`  [${TAG}] Spawning Alice...`);
  let alice = await ctx.spawnBot('Alice');
  assert.ok(alice.connected, 'Alice should be connected');

  console.log(`  [${TAG}] Alice creating '${worldName}'...`);
  alice.runCommand(`/world create ${worldName}`);

  const world = await waitFor(
    async () => {
      const rows = await ctx.db.query('SELECT * FROM player_world WHERE name = $1', [worldName]);
      return rows.length > 0 ? rows[0] : null;
    },
    30000,
    `player_world row for '${worldName}'`
  );
  console.log(`  [${TAG}] world ${world.id} folder=${world.folder}`);

  await new Promise((r) => setTimeout(r, 4000));
  if (!alice.connected) {
    console.log(`  [${TAG}] Alice was disconnected by the transfer; reconnecting...`);
    alice = await ctx.spawnBot('Alice');
  }

  const source = await waitFor(
    async () => {
      const rows = await ctx.db.query('SELECT assigned_node FROM player_world WHERE id = $1', [world.id]);
      return rows[0]?.assigned_node || null;
    },
    30000,
    'the world to hold a lease on some node'
  );
  const target = source === 'paper-a' ? 'paper-b' : 'paper-a';
  console.log(`  [${TAG}] world starts on ${source}; migrating to ${target}`);

  async function ensureAliceIsInTheWorld(node) {
    for (let attempt = 1; attempt <= 3; attempt++) {
      const status = await ctx.rcon(node, 'e2e status');
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
        const status = await ctx.rcon(node, 'e2e status');
        return status.includes(world.folder) && status.includes('Alice');
      },
      30000,
      `Alice to be online on ${node} with ${world.folder} loaded`
    );
  }

  await ensureAliceIsInTheWorld(source);

  // Give the marker item while Alice is *inside the player world*, so it is
  // captured by this world's profile snapshot rather than the lobby's.
  console.log(`  [${TAG}] Giving Alice ${MARKER_COUNT}x ${MARKER_ITEM} inside the world...`);
  await ctx.rcon(source, `give Alice minecraft:${MARKER_ITEM} ${MARKER_COUNT}`);
  await alice.waitForInventoryItem(MARKER_ITEM, MARKER_COUNT, 10000);
  console.log(`  [${TAG}] Marker item confirmed in Alice's inventory before migration.`);

  // ---------------------------------------------------------- MN-19/21 -----
  console.log(`  [${TAG}] /world admin migrate ${world.id} ${target}...`);
  let mark = alice.chatLog.length;
  alice.runCommand(`/world admin migrate ${world.id} ${target}`);

  const handoffMsg = await waitFor(
    async () => {
      const lines = alice.chatLog.slice(mark);
      return lines.find((l) => /countdown/i.test(l)) || null;
    },
    15000,
    'the MN-21 handoff-requested countdown message'
  );
  console.log(`  [${TAG}] handoff requested: "${handoffMsg.trim()}"`);

  // MN-19: the source unloads the world once the handoff completes. The
  // countdown is MigratePayload.DEFAULT_COUNTDOWN_SECONDS plus the commit
  // itself, so this is bounded generously rather than tied to that constant.
  await waitFor(
    async () => {
      const status = await ctx.rcon(source, 'e2e status');
      return !status.includes(world.folder) ? status : null;
    },
    60000,
    `${source} to unload ${world.folder} as part of the handoff`
  );
  console.log(`  [${TAG}] ${source} no longer has ${world.folder} loaded.`);

  const successMsg = await waitFor(
    async () => {
      const lines = alice.chatLog.slice(mark);
      return lines.find((l) => /moved from .* to .*/i.test(l)) || null;
    },
    30000,
    'the MN-19 migrate-success message'
  );
  console.log(`  [${TAG}] migrate reported: "${successMsg.trim()}"`);

  const afterMigrate = await ctx.db.query('SELECT assigned_node FROM player_world WHERE id = $1', [world.id]);
  assert.equal(
    afterMigrate[0].assigned_node,
    target,
    `expected the lease to move to ${target}, DB says ${JSON.stringify(afterMigrate[0])}`
  );
  console.log(`  [${TAG}] lease confirmed on ${target} (MN-19).`);

  // ---------------------------------------------------------- rejoin -------
  // The migrate itself only moves the lease; the world is not loaded on the
  // target until someone actually joins it, which is the cold load this half
  // exercises. Re-issuing the join is idempotent whether or not the handoff
  // itself left Alice's session connected.
  console.log(`  [${TAG}] Alice rejoining on ${target}...`);
  await ensureAliceIsInTheWorld(target);

  console.log(`  [${TAG}] FR-15: confirming the marker item survived the migrate...`);
  await alice.waitForInventoryItem(MARKER_ITEM, MARKER_COUNT, 20000);
  console.log(`  [${TAG}] Marker item intact after migrate + rejoin.`);

  const finalRows = await ctx.db.query('SELECT assigned_node FROM player_world WHERE id = $1', [world.id]);
  assert.equal(finalRows[0].assigned_node, target, `the lease must still be on ${target} after the rejoin`);

  console.log(`  [${TAG}] Scenario 10 completed successfully.`);
}

export default run;

const isDirect = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isDirect) {
  withTestContext(run)
    .then(() => {
      console.log('\x1b[32m10-node-migrate-with-player passed.\x1b[0m');
      process.exit(0);
    })
    .catch((err) => {
      console.error('\x1b[31m10-node-migrate-with-player failed:\x1b[0m', err);
      process.exit(1);
    });
}
