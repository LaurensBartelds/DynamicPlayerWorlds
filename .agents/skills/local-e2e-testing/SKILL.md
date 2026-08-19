---
name: local-e2e-testing
description: Use when running local end-to-end integration tests for DynamicPlayerWorlds features, testing with multi-bot simulated Minecraft players, verifying PostgreSQL database migrations, testing MinIO S3 world persistence, or inspecting multi-node Paper and Velocity proxy routing.
---

# Local E2E Multi-Bot Testing Workflow

## Overview
This repository provides a local, dockerized end-to-end testing environment with multi-bot simulation (`mineflayer`), real PostgreSQL 18.3, MinIO S3 object storage, Velocity proxy, and dual Paper nodes (`paper-a`, `paper-b`).

AI agents should use this environment to verify features against live servers with real player joins, chat commands, tablist visibility, inventory synchronization, and database/S3 persistence.

---

## Quick Reference Commands

Execute from the repository root:

| Command | Shortcut | Description |
|---|---|---|
| `npm run e2e:up` | `node e2e/cli.mjs up` | Starts Docker Compose stack & waits until all services pass health checks |
| `npm run e2e:down` | `node e2e/cli.mjs down` | Stops stack and purges test volumes |
| `npm run e2e:build` | `node e2e/cli.mjs build` | Builds Gradle jars & stages runtime plugin directories |
| `npm run e2e:deploy` | `node e2e/cli.mjs deploy` | Hot-copies newly built plugin jars to runtime servers |
| `npm run e2e:reset` | `node e2e/cli.mjs reset` | Truncates PostgreSQL tables & purges MinIO S3 bucket |
| `npm run e2e:status` | `node e2e/cli.mjs status` | Prints health table for Postgres, S3, Paper-A/B RCON, Velocity |
| `npm run e2e:test [filter]` | `node e2e/cli.mjs test [filter]` | Runs test scenarios sequentially (e.g., `npm run e2e:test 01-lobby`) |
| `npm run e2e:run [filter]` | `node e2e/cli.mjs run [filter]` | Ephemeral CI cycle: `build` &rarr; `up` &rarr; `test` &rarr; `down` |

### Direct Inspection Utilities
```bash
# Query PostgreSQL database directly
node e2e/cli.mjs sql "SELECT id, name, owner_uuid, state, assigned_node FROM player_world"

# Inspect MinIO S3 object storage
node e2e/cli.mjs s3 ls
node e2e/cli.mjs s3 cat <key>

# Send RCON command to Paper nodes
node e2e/cli.mjs rcon paper-a "e2e status"
node e2e/cli.mjs rcon paper-a "give Alice diamond 3"
node e2e/cli.mjs rcon paper-b "e2e status"

# Tail live container logs
node e2e/cli.mjs logs velocity
node e2e/cli.mjs logs paper-a
node e2e/cli.mjs logs postgres
```

---

## Agent Fast Iteration Workflow

When modifying code or implementing new features:

```
[1. Start Environment (once)]
    npm run e2e:up
         │
         ▼
[2. Code Modification]
    Edit Java source in backend/, proxy/, or core/
         │
         ▼
[3. Build & Deploy]
    npm run e2e:deploy   (builds jars & hot-deploys to runtime)
         │
         ▼
[4. Run Target Test]
    npm run e2e:test <scenario-name>
         │
         ▼
[5. Inspect DB / State (if failing)]
    node e2e/cli.mjs sql "SELECT * FROM player_world"
    node e2e/cli.mjs logs velocity
         │
         ▼
[6. Teardown When Finished]
    npm run e2e:down
```

---

## Authoring New Test Scenarios

All scenario files live in `e2e/scenarios/<number>-<name>.test.mjs` and export a default or `run(ctx)` async function:

```javascript
import assert from 'node:assert/strict';
import { withTestContext } from '../lib/test-context.mjs';

/**
 * Scenario: Description of feature being tested
 */
export async function run(ctx) {
  // 1. Spawn bots through Velocity proxy
  const alice = await ctx.spawnBot('Alice');
  const bob = await ctx.spawnBot('Bob');

  // 2. Execute player chat / proxy commands
  alice.runCommand('/world create myworld');

  // 3. Wait for chat notifications
  const chatMsg = await alice.waitForChat(/creating 'myworld'/i, 5000);
  console.log(`Received chat: ${chatMsg}`);

  // 4. Verify PostgreSQL database state
  const rows = await ctx.db.query(
    'SELECT * FROM player_world WHERE name = $1',
    ['myworld']
  );
  assert.strictEqual(rows.length, 1, 'World record must exist in DB');
  assert.strictEqual(rows[0].state, 'CREATING');

  // 5. Verify tablist / visibility isolation
  await bob.assertPlayerHidden('Alice');

  // 6. Test RCON interactions on backend nodes
  const status = await ctx.rcon('paper-a', 'e2e status');
  assert.ok(status.includes('online='));

  // 7. Verify S3 storage objects
  const objects = await ctx.s3.listObjects('myworld/');
  console.log(`Found ${objects.length} S3 snapshot objects.`);
}

export default run;
```

---

## Core Helper APIs

### `TestContext` (`ctx`)
- `ctx.spawnBot(username, options)`: Spawns a Mineflayer bot connected via Velocity. Returns `BotSession`.
- `ctx.db.query(sql, params)`: Executes a SQL query against PostgreSQL and returns array of row objects.
- `ctx.s3.listObjects(prefix)`: Lists objects matching prefix in MinIO bucket.
- `ctx.s3.getObject(key)`: Fetches object body Buffer from MinIO bucket.
- `ctx.s3.putObject(key, body)`: Uploads object Buffer / string to MinIO bucket.
- `ctx.rcon(nodeName, command)`: Sends an RCON command to `'paper-a'` or `'paper-b'`.
- `ctx.resetState()`: Truncates database tables and flushes S3 bucket.

### `BotSession` (`bot`)
- `bot.runCommand(cmd)`: Sends a command packet (`chat_command`).
- `bot.waitForChat(pattern, timeoutMs, startIndex)`: Polls chat log for regex/string match.
- `bot.getTabListPlayers()`: Returns array of player usernames visible in the bot's tablist.
- `bot.assertPlayerVisible(username, timeoutMs)`: Asserts player is present in tablist within timeout.
- `bot.assertPlayerHidden(username, timeoutMs)`: Asserts player is NOT present in tablist.
- `bot.getInventoryItems()`: Returns list of items currently in bot inventory.
- `bot.waitForInventoryItem(itemName, count, timeoutMs)`: Waits until specified item count is received.
- `bot.disconnect()`: Safely disconnects the bot.

---

## Common Pitfalls & Best Practices

1. **Database State Isolation:** `withTestContext` automatically calls `ctx.resetState()` before each scenario to guarantee clean state.
2. **Server Transfers:** When `/world create` or `/world join` routes a player to a backend Paper node, Velocity may migrate or refresh the client session. If a bot's socket ends during transfer, `if (!bot.connected) bot = await ctx.spawnBot(name);` re-establishes the session cleanly.
3. **Offline UUIDs:** In offline test mode, player UUIDs are deterministic v3 MD5 hashes (`OfflinePlayer:<username>`). `BotSession` maps UUIDs to usernames automatically.
4. **Port Conflicts:** Ensure local host ports `5432` (Postgres), `9000/9001` (MinIO), `25565` (Velocity), and `25575/25576` (RCON) are not occupied by other background processes.
