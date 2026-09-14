import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withTestContext } from '../lib/test-context.mjs';

/**
 * Scenario 07: MN-16 — a loaded world's live lease wins placement outright.
 *
 * This used to be a smoke check (RCON ping, a few `SELECT`s wrapped in
 * try/catch that only ever logged) and asserted nothing about routing
 * itself, so it could not have caught the regression it was named for.
 *
 * The regression, found while building milestone 8 (docs/plans/NEXT-STEPS.md):
 * `/world join` scored a node's occupancy and only *then* checked whether the
 * world already held a live lease on some node. With one node the two checks
 * always agreed, so nothing caught it until a second node existed. With two,
 * a world's second member was routed to whichever node was emptier — even
 * when the world was already loaded, ticking, on the other one — and the
 * join then failed with "could not acquire a lease" because the world's real
 * holder still held it.
 *
 * This scenario reproduces the exact shape: Alice's world loads on one node
 * (call it the holder); the other node is strictly emptier (zero worlds vs
 * one) for the entire test. Bob, a member, then joins. MN-16 requires Bob to
 * land on the holder regardless — a live lease is not scored, it wins
 * outright (`Placement.decide`, `PlacementService.select`).
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

const TAG = '07';

export async function run(ctx) {
  const worldName = 'routingworld';

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

  // The create routes Alice through a server switch; the bot session may be
  // replaced underneath us.
  await new Promise((r) => setTimeout(r, 4000));
  if (!alice.connected) {
    console.log(`  [${TAG}] Alice was disconnected by the transfer; reconnecting...`);
    alice = await ctx.spawnBot('Alice');
  }

  const holder = await waitFor(
    async () => {
      const rows = await ctx.db.query('SELECT assigned_node FROM player_world WHERE id = $1', [world.id]);
      return rows[0]?.assigned_node || null;
    },
    30000,
    'the world to hold a lease on some node'
  );
  const other = holder === 'paper-a' ? 'paper-b' : 'paper-a';
  console.log(`  [${TAG}] world is leased to ${holder}; ${other} must stay uninvolved throughout`);

  async function ensureAliceIsInTheWorld() {
    for (let attempt = 1; attempt <= 3; attempt++) {
      const status = await ctx.rcon(holder, 'e2e status');
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
        const status = await ctx.rcon(holder, 'e2e status');
        return status.includes(world.folder) && status.includes('Alice');
      },
      30000,
      `Alice to be online on ${holder} with ${world.folder} loaded`
    );
  }

  await ensureAliceIsInTheWorld();

  // Sanity check on the premise: the other node genuinely has nothing loaded,
  // so it really is the "emptier" node MN-16's regression would have routed
  // Bob to.
  const otherStatusBefore = await ctx.rcon(other, 'e2e status');
  console.log(`  [${TAG}] ${other} before Bob joins: ${otherStatusBefore.trim()}`);
  assert.ok(
    !otherStatusBefore.includes(world.folder),
    `test premise violated: ${other} already has ${world.folder} loaded`
  );

  // Make Bob a member so he is entitled to join at all (FR-9h) — this
  // scenario is about *where* a legitimate join lands, not about membership.
  console.log(`  [${TAG}] Spawning Bob and making him a member...`);
  let bob = await ctx.spawnBot('Bob');
  assert.ok(bob.connected, 'Bob should be connected');

  alice.runCommand('/world invite Bob');
  await waitFor(
    async () => {
      const rows = await ctx.db.query('SELECT * FROM player_world_invite WHERE world_id = $1', [world.id]);
      return rows.length > 0 ? rows : null;
    },
    15000,
    'the invite row for Bob'
  );

  bob.runCommand('/world accept Alice');
  await waitFor(
    async () => {
      const rows = await ctx.db.query('SELECT * FROM player_world_member WHERE world_id = $1', [world.id]);
      return rows.length > 0 ? rows : null;
    },
    15000,
    'the player_world_member row for Bob'
  );
  console.log(`  [${TAG}] Bob is a member.`);

  // ------------------------------------------------------------ MN-16 -----
  console.log(`  [${TAG}] MN-16: Bob joining — must land on ${holder}, not the emptier ${other}...`);
  bob.runCommand(`/world join Alice ${worldName}`);

  await waitFor(
    async () => {
      if (!bob.connected) return false;
      const status = await ctx.rcon(holder, 'e2e status');
      return status.includes(world.folder) && status.includes('Bob') ? status : null;
    },
    30000,
    `Bob to land on ${holder}, the node already holding the lease (MN-16)`
  );

  const otherStatusAfter = await ctx.rcon(other, 'e2e status');
  console.log(`  [${TAG}] ${other} after Bob joins: ${otherStatusAfter.trim()}`);
  assert.ok(
    !otherStatusAfter.includes('Bob'),
    `MN-16 regression: Bob was routed to ${other} (the emptier node) instead of ${holder} ` +
      `(the node already holding the world's live lease). This is the exact defect milestone 8 found: ` +
      `placement scored a node's occupancy before checking whether the world already had a live lease ` +
      `elsewhere. ${other} status: ${otherStatusAfter}`
  );

  const afterRows = await ctx.db.query('SELECT assigned_node FROM player_world WHERE id = $1', [world.id]);
  assert.equal(
    afterRows[0].assigned_node,
    holder,
    `the lease must not move when a second member joins — still ${holder}, was ${JSON.stringify(afterRows[0])}`
  );

  console.log(`  [${TAG}] Scenario 07 completed successfully.`);
}

export default run;

const isDirect = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isDirect) {
  withTestContext(run)
    .then(() => {
      console.log('\x1b[32m07-multi-node-routing passed.\x1b[0m');
      process.exit(0);
    })
    .catch((err) => {
      console.error('\x1b[31m07-multi-node-routing failed:\x1b[0m', err);
      process.exit(1);
    });
}
