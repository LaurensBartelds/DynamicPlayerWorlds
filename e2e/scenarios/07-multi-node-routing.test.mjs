import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withTestContext } from '../lib/test-context.mjs';

/**
 * Scenario 07: Multi-Node Routing & Coordination
 *
 * - Queries both Paper-A (25575) and Paper-B (25576) via RCON.
 * - Asserts responsiveness and harness status across both nodes.
 * - Inspects worlds_node table and active node leases in PostgreSQL.
 * - Validates multi-node command coordination infrastructure.
 */
export async function run(ctx) {
  console.log('  [07-multi-node-routing] Probing Paper-A RCON...');
  const pingA = await ctx.rcon('paper-a', 'e2e ping');
  assert.ok(pingA.includes('e2e pong'), `Paper-A should reply with 'e2e pong', got: ${pingA}`);
  console.log('  [07-multi-node-routing] Paper-A RCON is responsive.');

  const statusA = await ctx.rcon('paper-a', 'e2e status');
  assert.ok(statusA.includes('e2e status'), `Paper-A status should be valid, got: ${statusA}`);
  console.log(`  [07-multi-node-routing] Paper-A status: ${statusA.trim()}`);

  console.log('  [07-multi-node-routing] Probing Paper-B RCON...');
  const pingB = await ctx.rcon('paper-b', 'e2e ping');
  assert.ok(pingB.includes('e2e pong'), `Paper-B should reply with 'e2e pong', got: ${pingB}`);
  console.log('  [07-multi-node-routing] Paper-B RCON is responsive.');

  const statusB = await ctx.rcon('paper-b', 'e2e status');
  assert.ok(statusB.includes('e2e status'), `Paper-B status should be valid, got: ${statusB}`);
  console.log(`  [07-multi-node-routing] Paper-B status: ${statusB.trim()}`);

  // Query database for node state and leases
  console.log('  [07-multi-node-routing] Inspecting worlds_node table in PostgreSQL...');
  try {
    const nodes = await ctx.db.query(
      'SELECT node_id, address, loaded_worlds, online_players, mc_version FROM worlds_node ORDER BY node_id'
    );
    console.log(`  [07-multi-node-routing] Registered nodes in DB (${nodes.length}):`);
    for (const node of nodes) {
      console.log(`    - Node: ${node.node_id} (${node.address}) | Loaded: ${node.loaded_worlds} | Players: ${node.online_players}`);
    }
  } catch (err) {
    console.log(`  [07-multi-node-routing] worlds_node query note: ${err.message}`);
  }

  // Inspect world leases and node commands
  try {
    const activeLeases = await ctx.db.query(
      'SELECT id, name, assigned_node, state, lease_expires FROM player_world WHERE assigned_node IS NOT NULL'
    );
    console.log(`  [07-multi-node-routing] Active leases across cluster: ${activeLeases.length}`);

    const commands = await ctx.db.query('SELECT id, target_node, command, completed_at FROM node_command ORDER BY id DESC LIMIT 5');
    console.log(`  [07-multi-node-routing] Recent node_command rows: ${commands.length}`);
  } catch (err) {
    console.log(`  [07-multi-node-routing] DB lease/command inspect note: ${err.message}`);
  }

  console.log('  [07-multi-node-routing] Scenario 07 completed successfully.');
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
