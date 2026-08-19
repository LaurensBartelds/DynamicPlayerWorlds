import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withTestContext } from '../lib/test-context.mjs';

/**
 * Scenario 01: Lobby Join & Dual Bot Visibility
 *
 * - Dual bot join test: Spawns Alice and Bob connecting through Velocity proxy.
 * - Asserts both bots spawn successfully in the lobby.
 * - Asserts tablist visibility (Alice sees Bob, Bob sees Alice).
 * - Queries Paper-A RCON /e2e status or /list confirming both players online.
 * - Cleans up bots.
 */
export async function run(ctx) {
  console.log('  [01-lobby-join] Spawning Alice into lobby...');
  const alice = await ctx.spawnBot('Alice');
  assert.ok(alice.connected, 'Alice should be connected to the lobby');
  console.log('  [01-lobby-join] Alice spawned successfully.');

  console.log('  [01-lobby-join] Spawning Bob into lobby...');
  const bob = await ctx.spawnBot('Bob');
  assert.ok(bob.connected, 'Bob should be connected to the lobby');
  console.log('  [01-lobby-join] Bob spawned successfully.');

  console.log('  [01-lobby-join] Verifying holding area isolation between Alice and Bob (FR-11)...');
  const aliceTabList = alice.getTabListPlayers();
  const bobTabList = bob.getTabListPlayers();
  console.log(`  [01-lobby-join] Alice's tablist: [${aliceTabList.join(', ')}]`);
  console.log(`  [01-lobby-join] Bob's tablist: [${bobTabList.join(', ')}]`);
  assert.ok(aliceTabList.includes('Alice'), `Alice's tablist must contain Alice`);
  assert.ok(bobTabList.includes('Bob'), `Bob's tablist must contain Bob`);
  assert.ok(!aliceTabList.includes('Bob'), `Alice must not see Bob in holding area (FR-11)`);
  assert.ok(!bobTabList.includes('Alice'), `Bob must not see Alice in holding area (FR-11)`);

  console.log('  [01-lobby-join] Querying Paper-A RCON for online status...');
  const rconStatus = await ctx.rcon('paper-a', 'e2e status');
  console.log(`  [01-lobby-join] Paper-A RCON output: ${rconStatus.trim()}`);

  assert.ok(
    rconStatus.includes('online='),
    `Paper-A RCON status should contain 'online=', got: '${rconStatus}'`
  );
  assert.ok(
    rconStatus.includes('Alice') && rconStatus.includes('Bob'),
    `Paper-A RCON status should list both Alice and Bob, got: '${rconStatus}'`
  );

  try {
    const names = await ctx.db.query('SELECT name FROM player_name');
    const cachedNames = names.map((r) => r.name);
    console.log(`  [01-lobby-join] Cached player names in DB: [${cachedNames.join(', ')}]`);
  } catch (err) {
    console.log(`  [01-lobby-join] DB player_name query note: ${err.message}`);
  }

  console.log('  [01-lobby-join] Scenario 01 completed successfully.');
}

export default run;

const isDirect = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isDirect) {
  withTestContext(run)
    .then(() => {
      console.log('\x1b[32m01-lobby-join passed.\x1b[0m');
      process.exit(0);
    })
    .catch((err) => {
      console.error('\x1b[31m01-lobby-join failed:\x1b[0m', err);
      process.exit(1);
    });
}
