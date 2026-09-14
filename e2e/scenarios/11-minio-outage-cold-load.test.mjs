import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withTestContext } from '../lib/test-context.mjs';

/**
 * Scenario 11: a real object-store outage during a cold load (R12+R13, FR-11, NFR-1).
 *
 * Named in docs/plans/NEXT-STEPS.md as unverified and high-risk: "Stop MinIO,
 * join a cold world, and assert the player is ejected at the holding timeout
 * *and* the world is joinable again immediately once MinIO returns, rather
 * than after the lease expires." This is the core durability/availability
 * promise CONTRIBUTING.md opens with — "holds the only copy of things
 * players care about" — under the one failure mode most likely to happen on
 * a real network: the S3-compatible store is unreachable for a while.
 *
 * A world only takes the cold path (`WorldDownloader` fetching from object
 * storage rather than reusing a clean-unload marker's local files,
 * `WorldLifecycleService` + `CleanUnloadMarker`) when it has never been
 * loaded on the node attempting to load it. Rather than wait out the default
 * 10-minute idle-unload to get there, this scenario forces it the same way
 * scenario 10 does: `/world admin migrate` the world to the other node while
 * MinIO is still up. The target then has a live lease but no local files for
 * this world at all, so the next join against it is unconditionally cold —
 * the same code path a crash-and-quarantine (R16) would also force, just
 * reached deterministically instead of by killing a node.
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

const TAG = '11';
// NetworkPolicy.DEFAULT_HOLDING_TIMEOUT — the outer budget of the whole join
// path (FR-11). Not read from the DB here on purpose: this scenario is
// checking the shipped default actually holds, the same way a production
// network running with no network_setting overrides would experience it.
const HOLDING_TIMEOUT_SECONDS = 90;

export async function run(ctx) {
  const worldName = 'outageworld';

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

  // Force the world onto `target` with no local files there — while MinIO is
  // still up, so this part is just setup, not the thing under test.
  console.log(`  [${TAG}] Forcing the world onto ${target} (no local files there) via admin migrate...`);
  alice.runCommand(`/world admin migrate ${world.id} ${target}`);
  await waitFor(
    async () => {
      const rows = await ctx.db.query('SELECT assigned_node FROM player_world WHERE id = $1', [world.id]);
      return rows[0]?.assigned_node === target ? rows[0] : null;
    },
    60000,
    `the lease to move to ${target}`
  );
  await waitFor(
    async () => {
      const status = await ctx.rcon(source, 'e2e status');
      return !status.includes(world.folder) ? status : null;
    },
    30000,
    `${source} to unload ${world.folder} as part of the handoff`
  );
  console.log(`  [${TAG}] world is leased to ${target} with no prior local copy there.`);

  // -------------------------------------------------------- the outage -----
  console.log(`  [${TAG}] Stopping MinIO...`);
  await ctx.dockerStop('minio');

  try {
    console.log(`  [${TAG}] Alice attempting a cold join while MinIO is down...`);
    if (!alice.connected) {
      alice = await ctx.spawnBot('Alice');
    }
    const markBeforeJoin = alice.chatLog.length;
    alice.runCommand(`/world join Alice ${worldName}`);

    // Bounded generously above HOLDING_TIMEOUT_SECONDS: the cold-load budget
    // (storage.cold-load-budget-seconds) sits strictly inside the holding
    // timeout by ConfigValidator's own invariant, so whichever budget gives
    // up first, the outer one is the latest this can possibly take.
    const ejection = await waitFor(
      async () => {
        const lines = alice.chatLog.slice(markBeforeJoin);
        return lines.find((l) => /too long to load|returning you to the lobby/i.test(l)) || null;
      },
      (HOLDING_TIMEOUT_SECONDS + 45) * 1000,
      `the FR-11 holding-timeout ejection message within ${HOLDING_TIMEOUT_SECONDS}s of budget`
    );
    console.log(`  [${TAG}] Alice was ejected as required: "${ejection.trim()}"`);

    // The world must never have actually finished loading on target while
    // MinIO was down — a cold load that "succeeds" without its data is a far
    // worse failure than a slow, correctly-refused one.
    const statusDuringOutage = await ctx.rcon(target, 'e2e status');
    console.log(`  [${TAG}] ${target} status: ${statusDuringOutage.trim()}`);
    assert.ok(
      !statusDuringOutage.includes(world.folder),
      `${target} must not have ${world.folder} loaded while MinIO is down and the cold load was refused: ${statusDuringOutage}`
    );

    const duringOutageRow = await ctx.db.query('SELECT assigned_node, state FROM player_world WHERE id = $1', [
      world.id,
    ]);
    console.log(`  [${TAG}] DB row after ejection: ${JSON.stringify(duringOutageRow[0])}`);
    assert.equal(
      duringOutageRow[0].assigned_node,
      target,
      'the lease must still belong to target — a failed load must not silently release or reassign it'
    );
  } finally {
    // Always restart MinIO, even if an assertion above threw — an outage
    // scenario must not leave the stack broken for whatever runs after it.
    console.log(`  [${TAG}] Restarting MinIO...`);
    await ctx.dockerStart('minio');
    // Give the healthcheck a moment; minio-init already created the bucket
    // once, so this is just the server accepting connections again.
    await new Promise((r) => setTimeout(r, 5000));
  }

  // ------------------------------------------------------- recovery --------
  console.log(`  [${TAG}] R12+R13: the world must be joinable again immediately, not after the lease expires...`);
  if (!alice.connected) {
    alice = await ctx.spawnBot('Alice');
  }
  await ensureAliceIsInTheWorld(target);
  console.log(`  [${TAG}] Alice is back in ${world.folder} on ${target} now that MinIO has recovered.`);

  const finalRow = await ctx.db.query('SELECT assigned_node, state FROM player_world WHERE id = $1', [world.id]);
  assert.equal(finalRow[0].assigned_node, target, 'the world must remain leased to target after recovery');
  assert.equal(finalRow[0].state, 'READY', `expected READY after a successful cold load, got ${finalRow[0].state}`);

  console.log(`  [${TAG}] Scenario 11 completed successfully.`);
}

export default run;

const isDirect = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isDirect) {
  withTestContext(run)
    .then(() => {
      console.log('\x1b[32m11-minio-outage-cold-load passed.\x1b[0m');
      process.exit(0);
    })
    .catch((err) => {
      console.error('\x1b[31m11-minio-outage-cold-load failed:\x1b[0m', err);
      process.exit(1);
    });
}
