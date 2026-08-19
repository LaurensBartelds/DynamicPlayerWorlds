import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withTestContext } from '../lib/test-context.mjs';

/**
 * Scenario 04: Player Visibility & Tablist Isolation
 *
 * - Spawns Alice (who creates and enters a private world).
 * - Spawns Bob in the default lobby.
 * - Asserts Alice does not see Bob in her local tablist/view when separated across worlds.
 * - Asserts Bob does not see Alice in the lobby tablist.
 */
export async function run(ctx) {
  console.log('  [04-visibility-isolation] Spawning Alice...');
  let alice = await ctx.spawnBot('Alice');
  assert.ok(alice.connected, 'Alice should be connected');

  const worldName = 'isolworld';

  console.log(`  [04-visibility-isolation] Alice creating private world '${worldName}'...`);
  alice.runCommand(`/world create ${worldName}`);

  // Wait for world in DB
  let worldRecord = null;
  const startWait = Date.now();
  while (Date.now() - startWait < 15000) {
    const rows = await ctx.db.query('SELECT * FROM player_world WHERE name = $1', [worldName]);
    if (rows.length > 0) {
      worldRecord = rows[0];
      break;
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  assert.ok(worldRecord, `Expected player_world row for '${worldName}'`);

  // Allow brief propagation for world routing
  await new Promise((r) => setTimeout(r, 2000));

  // Reconnect Alice if disconnected during world transfer
  if (!alice.connected) {
    alice = await ctx.spawnBot('Alice');
  }

  console.log('  [04-visibility-isolation] Spawning Bob into lobby...');
  let bob = await ctx.spawnBot('Bob');
  assert.ok(bob.connected, 'Bob should be connected in lobby');

  // Verify tablist isolation between Alice and Bob
  console.log('  [04-visibility-isolation] Checking tablist isolation...');
  await bob.assertPlayerHidden('Alice', 10000);
  await alice.assertPlayerHidden('Bob', 10000);

  const bobTabList = bob.getTabListPlayers();
  const aliceTabList = alice.getTabListPlayers();

  console.log(`  [04-visibility-isolation] Bob's tablist: [${bobTabList.join(', ')}]`);
  console.log(`  [04-visibility-isolation] Alice's tablist: [${aliceTabList.join(', ')}]`);

  assert.ok(
    !bobTabList.includes('Alice'),
    `Bob (in lobby) must not see Alice (in private world). Tablist: [${bobTabList.join(', ')}]`
  );
  assert.ok(
    !aliceTabList.includes('Bob'),
    `Alice (in private world) must not see Bob (in lobby). Tablist: [${aliceTabList.join(', ')}]`
  );

  console.log('  [04-visibility-isolation] Scenario 04 completed successfully.');
}

export default run;

const isDirect = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isDirect) {
  withTestContext(run)
    .then(() => {
      console.log('\x1b[32m04-visibility-isolation passed.\x1b[0m');
      process.exit(0);
    })
    .catch((err) => {
      console.error('\x1b[31m04-visibility-isolation failed:\x1b[0m', err);
      process.exit(1);
    });
}
