import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withTestContext } from '../lib/test-context.mjs';

/**
 * Scenario 03: Membership & Invitations
 *
 * - Connects Alice (world owner), Bob (friend), and Charlie (uninvited).
 * - Alice creates a private world and invites Bob (/world invite Bob).
 * - Asserts Bob receives invite chat notification / invitation record exists in database.
 * - Bob accepts invite (/world accept Alice).
 * - Asserts membership record is created in player_world_member.
 * - Charlie tries to join Alice's world (/world join Alice) without permission and is denied.
 */
export async function run(ctx) {
  console.log('  [03-membership-invites] Spawning Alice, Bob, and Charlie...');
  let alice = await ctx.spawnBot('Alice');
  let bob = await ctx.spawnBot('Bob');
  let charlie = await ctx.spawnBot('Charlie');

  assert.ok(alice.connected, 'Alice should be connected');
  assert.ok(bob.connected, 'Bob should be connected');
  assert.ok(charlie.connected, 'Charlie should be connected');

  const worldName = 'aliceworld';

  // 1. Alice creates world
  console.log(`  [03-membership-invites] Alice creating world '${worldName}'...`);
  alice.runCommand(`/world create ${worldName}`);

  let worldRecord = null;
  const startWait = Date.now();
  while (Date.now() - startWait < 15000) {
    const rows = await ctx.db.query('SELECT * FROM player_world WHERE name = $1', [worldName]);
    if (rows.length > 0 && rows[0].state === 'READY') {
      worldRecord = rows[0];
      break;
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  assert.ok(worldRecord, `World '${worldName}' must exist in player_world and be READY`);
  const worldId = worldRecord.id;

  // Reconnect Alice if disconnected during world creation transfer
  if (!alice.connected) {
    alice = await ctx.spawnBot('Alice');
  }

  // 2. Alice invites Bob
  console.log(`  [03-membership-invites] Alice inviting Bob to '${worldName}'...`);
  const bobChatIndex = bob.chatLog.length;
  alice.runCommand('/world invite Bob');

  // Verify Bob received invite chat or DB record
  try {
    const inviteMsg = await bob.waitForChat(/invited you|invite/i, 10000, bobChatIndex);
    console.log(`  [03-membership-invites] Bob received chat: ${inviteMsg}`);
  } catch {
    console.log('  [03-membership-invites] Note: Verifying invite through PostgreSQL...');
  }

  let invites = [];
  const invitePollStart = Date.now();
  while (Date.now() - invitePollStart < 8000) {
    invites = await ctx.db.query('SELECT * FROM player_world_invite WHERE world_id = $1', [worldId]);
    if (invites.length > 0) break;
    await new Promise((r) => setTimeout(r, 400));
  }
  assert.ok(invites.length > 0, `player_world_invite row for world ${worldId} should exist`);
  console.log(`  [03-membership-invites] Found ${invites.length} invite row(s) in DB.`);

  // 3. Bob accepts invite
  console.log('  [03-membership-invites] Bob accepting invite from Alice...');
  const bobAcceptChatIndex = bob.chatLog.length;
  bob.runCommand('/world accept Alice');

  try {
    const acceptMsg = await bob.waitForChat(/now a|accepted|already/i, 10000, bobAcceptChatIndex);
    console.log(`  [03-membership-invites] Bob received accept response: ${acceptMsg}`);
  } catch {
    console.log('  [03-membership-invites] Note: Verifying acceptance through PostgreSQL...');
  }

  // Poll player_world_member table for Bob
  let memberRows = [];
  const memberWaitStart = Date.now();
  while (Date.now() - memberWaitStart < 10000) {
    memberRows = await ctx.db.query('SELECT * FROM player_world_member WHERE world_id = $1', [worldId]);
    if (memberRows.length > 0) break;
    await new Promise((r) => setTimeout(r, 500));
  }

  assert.ok(memberRows.length > 0, `Expected Bob to be registered in player_world_member for world ${worldId}`);
  console.log(`  [03-membership-invites] Verified membership in DB: role=${memberRows[0].role}`);

  // 4. Charlie tries to join Alice's world without permission
  console.log("  [03-membership-invites] Charlie attempting unauthorized join (/world join Alice)...");
  const charlieChatIndex = charlie.chatLog.length;
  charlie.runCommand('/world join Alice');

  try {
    const deniedMsg = await charlie.waitForChat(/no world you can join|denied|error|cannot join/i, 10000, charlieChatIndex);
    console.log(`  [03-membership-invites] Charlie received expected denial: ${deniedMsg}`);
  } catch {
    console.log('  [03-membership-invites] Checking database to ensure Charlie has no membership...');
  }

  // Ensure Charlie was not added to player_world_member
  const charlieMember = await ctx.db.query(
    `SELECT m.* FROM player_world_member m
     JOIN player_name n ON n.uuid = m.uuid
     WHERE m.world_id = $1 AND LOWER(n.name) = 'charlie'`,
    [worldId]
  );
  assert.strictEqual(charlieMember.length, 0, 'Charlie must not be in player_world_member');

  console.log('  [03-membership-invites] Scenario 03 completed successfully.');
}

export default run;

const isDirect = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isDirect) {
  withTestContext(run)
    .then(() => {
      console.log('\x1b[32m03-membership-invites passed.\x1b[0m');
      process.exit(0);
    })
    .catch((err) => {
      console.error('\x1b[31m03-membership-invites failed:\x1b[0m', err);
      process.exit(1);
    });
}
