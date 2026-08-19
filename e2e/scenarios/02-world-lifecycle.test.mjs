import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withTestContext } from '../lib/test-context.mjs';

/**
 * Scenario 02: World Lifecycle Management
 *
 * - Connects Alice.
 * - Runs /world create testworld.
 * - Waits for creation chat response or checks DB for world row in player_world table.
 * - Asserts world record exists with valid UUID, owner, and state.
 * - Tests /world delete testworld with confirmation and asserts proper teardown.
 */
export async function run(ctx) {
  console.log('  [02-world-lifecycle] Spawning Alice...');
  const alice = await ctx.spawnBot('Alice');
  assert.ok(alice.connected, 'Alice should be connected to the proxy');

  const worldName = 'testworld';

  console.log(`  [02-world-lifecycle] Alice executing /world create ${worldName}...`);
  alice.runCommand(`/world create ${worldName}`);

  // Wait for creation chat response or poll DB
  console.log('  [02-world-lifecycle] Waiting for world creation record in PostgreSQL...');
  let worldRecord = null;
  const startTime = Date.now();
  const timeoutMs = 20000;

  while (Date.now() - startTime < timeoutMs) {
    const rows = await ctx.db.query('SELECT * FROM player_world WHERE name = $1', [worldName]);
    if (rows && rows.length > 0) {
      worldRecord = rows[0];
      break;
    }
    await new Promise((r) => setTimeout(r, 500));
  }

  assert.ok(worldRecord, `Expected player_world row for '${worldName}' to exist in DB within ${timeoutMs}ms`);
  console.log(`  [02-world-lifecycle] Found world record in DB: ID=${worldRecord.id}, State=${worldRecord.state}, Node=${worldRecord.assigned_node}`);

  // Assert world record fields
  assert.ok(worldRecord.id, 'World record ID must be present');
  assert.match(
    worldRecord.id,
    /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/,
    `World ID must be a valid UUID: ${worldRecord.id}`
  );
  assert.strictEqual(worldRecord.name, worldName, `World name should be '${worldName}'`);
  assert.ok(
    ['CREATING', 'READY', 'ARCHIVING', 'ARCHIVED'].includes(worldRecord.state),
    `World state '${worldRecord.state}' must be a valid WorldState`
  );
  assert.ok(worldRecord.owner_uuid, 'World owner_uuid must be present');

  // Verify chat confirmation if available
  try {
    const chatMsg = await alice.waitForChat(new RegExp(`creating '${worldName}'|created`, 'i'), 5000);
    console.log(`  [02-world-lifecycle] Received creation chat: ${chatMsg}`);
  } catch {
    console.log('  [02-world-lifecycle] Note: Chat message verified via database row.');
  }

  // Test /world delete
  console.log(`  [02-world-lifecycle] Alice executing /world delete ${worldName}...`);
  const chatIndexBeforeDelete = alice.chatLog.length;
  alice.runCommand(`/world delete ${worldName}`);

  // Wait for confirmation prompt chat
  try {
    const confirmPrompt = await alice.waitForChat(/confirm/i, 10000, chatIndexBeforeDelete);
    console.log(`  [02-world-lifecycle] Received confirmation prompt: ${confirmPrompt}`);
  } catch {
    console.log('  [02-world-lifecycle] Proceeding with confirmation command...');
  }

  console.log(`  [02-world-lifecycle] Alice confirming deletion with /world delete ${worldName} confirm...`);
  const chatIndexBeforeConfirm = alice.chatLog.length;
  alice.runCommand(`/world delete ${worldName} confirm`);

  try {
    const deleteMsg = await alice.waitForChat(/removed|archiving|freed|deleted|slot/i, 10000, chatIndexBeforeConfirm);
    console.log(`  [02-world-lifecycle] Received delete response chat: ${deleteMsg}`);
  } catch {
    console.log('  [02-world-lifecycle] Awaiting database state transition for deletion...');
  }

  // Poll DB to verify world deletion or transition to ARCHIVED / deleted
  let deletedOrArchived = false;
  const deleteStartTime = Date.now();
  while (Date.now() - deleteStartTime < 15000) {
    const rows = await ctx.db.query('SELECT * FROM player_world WHERE name = $1', [worldName]);
    if (rows.length === 0 || rows[0].state === 'ARCHIVED' || rows[0].state === 'ARCHIVING') {
      deletedOrArchived = true;
      console.log(`  [02-world-lifecycle] World state after delete: ${rows.length === 0 ? 'DELETED' : rows[0].state}`);
      break;
    }
    await new Promise((r) => setTimeout(r, 500));
  }

  assert.ok(deletedOrArchived, `World '${worldName}' should be deleted or transitioned to ARCHIVING/ARCHIVED`);
  console.log('  [02-world-lifecycle] Scenario 02 completed successfully.');
}

export default run;

const isDirect = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isDirect) {
  withTestContext(run)
    .then(() => {
      console.log('\x1b[32m02-world-lifecycle passed.\x1b[0m');
      process.exit(0);
    })
    .catch((err) => {
      console.error('\x1b[31m02-world-lifecycle failed:\x1b[0m', err);
      process.exit(1);
    });
}
