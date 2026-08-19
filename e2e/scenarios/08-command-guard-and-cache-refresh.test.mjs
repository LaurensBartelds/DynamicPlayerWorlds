import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withTestContext } from '../lib/test-context.mjs';

/**
 * Scenario 08: FR-22's command allow-list, and FR-9's role cache surviving a
 * control-plane refresh.
 *
 * Both halves are regressions for defects that every unit test passed over,
 * because both were failures of *wiring* rather than of logic:
 *
 *  - R1 / FR-21, FR-22: `CommandGuardListener` implemented the allow-list
 *    completely and was never registered, so vanilla `/list` worked inside a
 *    player world and leaked the names of everyone on the node.
 *
 *  - R4 / FR-9, FR-9e, FR-31a: `INVALIDATE_CACHE` evicted `MembershipCache`
 *    without refilling it. A miss there answers VISITOR rather than "go and
 *    read", so any `/world promote|kick|ban|public|set` against a *loaded*
 *    world demoted the owner in their own world until it unloaded.
 *
 * The second half is why this scenario drives a real block break rather than
 * inspecting a cache: the observable that matters is that the owner can still
 * build, and that is only visible from inside the game.
 */

const DENY_COMMAND = /not available inside a player world/i;


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

/** Chat lines the bot has seen since {@code fromIndex}, as plain strings. */
function chatSince(bot, fromIndex) {
  return bot.chatLog.slice(fromIndex).map((entry) => (typeof entry === 'string' ? entry : String(entry?.text ?? entry)));
}

const TAG = '08';

export async function run(ctx) {
  const worldName = 'guardworld';

  console.log('  [08] Spawning Alice...');
  let alice = await ctx.spawnBot('Alice');
  assert.ok(alice.connected, 'Alice should be connected');

  console.log(`  [08] Alice creating '${worldName}'...`);
  alice.runCommand(`/world create ${worldName}`);

  const world = await waitFor(
    async () => {
      const rows = await ctx.db.query('SELECT * FROM player_world WHERE name = $1', [worldName]);
      return rows.length > 0 ? rows[0] : null;
    },
    30000,
    `player_world row for '${worldName}'`
  );
  console.log(`  [08] world ${world.id} folder=${world.folder} state=${world.state}`);

  // The create routes Alice to the holding node and then into the world, which
  // is a server switch: the session may be replaced underneath us.
  await new Promise((r) => setTimeout(r, 4000));
  if (!alice.connected) {
    console.log('  [08] Alice was disconnected by the transfer; reconnecting...');
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
  console.log(`  [08] world is leased to ${node}`);


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

  // Confirm Alice really is inside the player world before asserting anything
  // about behaviour that only applies there.
  await ensureAliceIsInTheWorld(node);

  // ---------------------------------------------------------------- R1 -----
  console.log('  [08] FR-22: /list must be refused inside a player world...');
  let mark = alice.chatLog.length;
  alice.runCommand('/list');

  const refusal = await waitFor(
    async () => chatSince(alice, mark).find((line) => DENY_COMMAND.test(line)),
    15000,
    'the FR-22 refusal for /list'
  );
  console.log(`  [08] refused: "${refusal.trim()}"`);

  const listOutput = chatSince(alice, mark).filter((line) => /players online|There are \d+/i.test(line));
  assert.equal(
    listOutput.length,
    0,
    `/list leaked a player roster inside a player world (FR-22): ${JSON.stringify(listOutput)}`
  );

  // The allow-list must not lock a player into the world it applies to: /world
  // and /pworld are ALWAYS_ALLOWED because they are the exit.
  console.log('  [08] FR-22: /world must still be reachable (it is the exit)...');
  mark = alice.chatLog.length;
  alice.runCommand('/world members');
  await new Promise((r) => setTimeout(r, 3000));
  const worldDenied = chatSince(alice, mark).some((line) => DENY_COMMAND.test(line));
  assert.ok(!worldDenied, '/world must never be denied by the allow-list — it is how a player leaves');

  // ---------------------------------------------------------------- R4 -----
  // Alice owns this world, so FR-9 says she builds. Creative makes the break
  // instant and independent of tools; BlockBreakEvent still fires, which is
  // what RoleEnforcementListener acts on.
  await ctx.rcon(node, 'gamemode creative Alice');
  await new Promise((r) => setTimeout(r, 1000));

  // Everything here is asserted through RCON rather than through the bot.
  //
  // Two traps, both of which made an earlier version of this scenario pass
  // against a build that still had the defect:
  //   - RoleEnforcementListener.deny() sends an ACTION BAR, not chat, so the
  //     refusal never reaches bot.chatLog and a chat assertion can never fire.
  //   - bot.blockAt() reads mineflayer's own predicted world state. In creative
  //     the client breaks the block locally whether or not the server agreed, so
  //     the block reads as 'air' even when BlockBreakEvent was cancelled.
  // Only the server knows whether the break happened.
  // `execute as Alice at Alice` is what puts the command in *her* world. A bare
  // setblock or `execute if block` runs in the server's default world, where
  // the coordinates are not even loaded.
  async function targetBlockIs(material) {
    const reply = await ctx.rcon(node, `execute as Alice at Alice if block ~1 ~ ~ minecraft:${material}`);
    return { matched: /Test passed/i.test(reply), reply: String(reply).trim() };
  }

  async function ownerCanBreakABlock(label) {
    // The block is placed one to the +X side at foot level, never the one she is
    // standing on: breaking that makes her fall, and then `~1 ~ ~` resolves to a
    // different block afterwards and the assertion compares two unrelated
    // positions.
    await ctx.rcon(node, 'execute as Alice at Alice run setblock ~1 ~ ~ minecraft:dirt replace');
    await new Promise((r) => setTimeout(r, 1000));

    // Positive control: without this, a setblock that silently failed would make
    // the assertion below pass for the wrong reason.
    const placed = await targetBlockIs('dirt');
    console.log(`  [08] ${label}: target before dig -> ${JSON.stringify(placed)}`);
    assert.ok(placed.matched, `${label}: setblock did not place dirt beside Alice: "${placed.reply}"`);

    // Same block, in mineflayer's coordinates. `~1` floors to floor(x)+1, which
    // is what this computes, so both sides address the same block.
    const pos = alice.bot.entity.position;
    const bx = Math.floor(pos.x) + 1;
    const by = Math.floor(pos.y);
    const bz = Math.floor(pos.z);

    const { Vec3 } = await import('vec3');
    const target = alice.bot.blockAt(new Vec3(bx, by, bz));
    assert.ok(target, `${label}: mineflayer cannot see a block at ${bx},${by},${bz}`);

    let digError = null;
    try {
      await alice.bot.dig(target);
    } catch (err) {
      digError = err;
    }
    await new Promise((r) => setTimeout(r, 2000));

    const stillDirt = await targetBlockIs('dirt');
    console.log(
      `  [08] ${label}: after dig -> ${JSON.stringify(stillDirt)}${digError ? ` (dig warned: ${digError.message})` : ''}`
    );
    assert.ok(
      !stillDirt.matched,
      `${label}: the OWNER's block break was cancelled server-side (FR-9/FR-31a). ` +
        `This is the R4 regression: the membership cache was evicted and never refilled, so ` +
        `MembershipCache.effectiveRole answered VISITOR for the world's own owner. ` +
        `Server still reports dirt at ${bx},${by},${bz}: "${stillDirt.reply}"`
    );
  }

  console.log('  [08] FR-9: owner can break a block before any cache traffic...');
  await ownerCanBreakABlock('before INVALIDATE_CACHE');

  // The trigger. /world set is a proxy command that commits to the database and
  // then tells the holding node over the control plane (§6, CP-6). Before R4
  // that command is what demoted the owner.
  console.log('  [08] Sending /world set pvp on (fires INVALIDATE_CACHE at the node)...');
  mark = alice.chatLog.length;
  alice.runCommand('/world set pvp on');
  await waitFor(
    async () => chatSince(alice, mark).some((line) => /set pvp/i.test(line)),
    15000,
    'the proxy to confirm /world set pvp on'
  );

  // Give the control-plane row time to be claimed and handled (CP-3's poll is
  // 5s; NOTIFY normally beats it).
  await new Promise((r) => setTimeout(r, 8000));

  const commands = await ctx.db.query(
    "SELECT command, result, completed_at FROM node_command WHERE command = 'INVALIDATE_CACHE' ORDER BY id DESC LIMIT 5"
  );
  console.log(`  [08] INVALIDATE_CACHE rows: ${JSON.stringify(commands)}`);
  assert.ok(commands.length > 0, 'expected /world set to enqueue an INVALIDATE_CACHE command (CP-6)');

  console.log('  [08] FR-9/R4: owner must STILL be able to break a block afterwards...');
  await ownerCanBreakABlock('after INVALIDATE_CACHE');

  console.log('  [08] Scenario 08 completed successfully.');
}

export default run;

const isDirect = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isDirect) {
  withTestContext(run)
    .then(() => {
      console.log('\x1b[32m08-command-guard-and-cache-refresh passed.\x1b[0m');
      process.exit(0);
    })
    .catch((err) => {
      console.error('\x1b[31m08-command-guard-and-cache-refresh failed:\x1b[0m', err);
      process.exit(1);
    });
}
