# AI Agent E2E Testing Guide: DynamicPlayerWorlds

This document serves as the authoritative, comprehensive testing manual for AI agents and developers implementing or testing features in the **DynamicPlayerWorlds** repository.

---

## 1. Overview & Testing Philosophy

DynamicPlayerWorlds is a distributed Minecraft server system consisting of a Velocity proxy, multiple Paper backend server nodes, a PostgreSQL database, and an S3-compatible MinIO object store.

To verify features with 100% confidence, we do **not** rely solely on unit tests or mocks. We use a **real, local Docker Compose cluster** paired with **headless Mineflayer player bots** that connect through the Velocity proxy, run `/world` commands, interact with inventories/GUIs, and trigger cross-server lease transfers and database state updates.

### When to Write & Run E2E Tests
- Whenever adding or modifying `/world` subcommands, menus, permissions, or lifecycle states.
- Whenever changing database queries, migrations, or leasing logic.
- Whenever modifying player visibility, spatial hiding, or tablist isolation.
- Whenever modifying player inventory serialisation, snapshot save/restore, or S3 archiving.
- Before completing tasks, to provide undeniable proof of verification.

---

## 2. Environment Architecture & Connection Details

The local test cluster runs 5 Docker Compose services mapped directly to `127.0.0.1` on the host:

```
                      +-----------------------------+
                      |   Mineflayer Multi-Bot      |
                      |  Harness (e2e/cli.mjs)      |
                      +--------------+--------------+
                                     |
              +----------------------+----------------------+
              |                      |                      |
      (Minecraft TCP)           (Host RCON)             (Host RCON)
         Port 25565             Port 25575              Port 25576
              |                      |                      |
              v                      v                      v
      +---------------+      +---------------+      +---------------+
      |   Velocity    |----->|    Paper-A    |      |    Paper-B    |
      | 4.0.0 Proxy   |----->|  (Lobby Node) |      | (Worlds Node) |
      +---------------+      +-------+-------+      +-------+-------+
              |                      |                      |
              |                      |                      |
              +--------------+-------+----------------------+
                             |
                   +---------+---------+
                   |                   |
               (PostgreSQL)         (MinIO S3)
                Port 5432          Ports 9000/9001
                   |                   |
                   v                   v
           +---------------+   +---------------+
           |  PostgreSQL   |   |   MinIO S3    |
           |     18.3      |   | Object Store  |
           +---------------+   +---------------+
```

### Connection Coordinates

| Target | Host Endpoint | Auth / Credentials | Purpose |
| --- | --- | --- | --- |
| **Velocity Proxy** | `127.0.0.1:25565` | Offline Auth | Proxy entrypoint for Mineflayer bots |
| **Paper-A RCON** | `127.0.0.1:25575` | Pass: `e2e-rcon-secret` | Direct RCON management on lobby node |
| **Paper-B RCON** | `127.0.0.1:25576` | Pass: `e2e-rcon-secret` | Direct RCON management on node B |
| **PostgreSQL** | `127.0.0.1:5432` | User: `gzmn`, Pass: `e2e-postgres-not-for-prod`, DB: `gzmn_worlds` | Shared relational database |
| **MinIO S3 API** | `127.0.0.1:9000` | Key: `gzmn-e2e`, Secret: `gzmn-e2e-secret`, Bucket: `gzmn-worlds` | Object storage API for world snapshots |
| **MinIO Console** | `127.0.0.1:9001` | Key: `gzmn-e2e`, Secret: `gzmn-e2e-secret` | Web UI for viewing S3 buckets |

---

## 3. Core Testing Framework APIs

The testing harness lives in `e2e/lib/` and exposes a unified, robust API designed specifically for agentic test execution.

### 3.1 Test Context (`TestContext` & `withTestContext`)

Defined in [`e2e/lib/test-context.mjs`](../../e2e/lib/test-context.mjs), `withTestContext` wraps your test in a managed lifecycle:

```javascript
import { withTestContext } from '../lib/test-context.mjs';

export async function run(ctx) {
  // ctx.spawnBot(username)
  // ctx.db.query(sql, params)
  // ctx.s3
  // ctx.rcon(node, command)
  // ctx.resetState()
}

export default run;
```

#### `TestContext` Methods:
- `ctx.spawnBot(username, [options])`: Spawns a headless Mineflayer bot, connects it to the Velocity proxy, handles protocol handshake spoofing, and resolves once the bot spawns in the world.
- `ctx.rcon(nodeName, command, [options])`: Sends an RCON command to `'paper-a'` (or `'paper-b'`) and returns the response string.
- `ctx.db.query(sql, [params])`: Executes a parameterized SQL query on PostgreSQL and returns an array of row objects.
- `ctx.s3`: Instance of `S3Helper` configured for the `gzmn-worlds` MinIO bucket.
- `ctx.resetState()`: Truncates `player_world`, `world_lease`, `player_world_profile` tables and clears all S3 objects in the test bucket.
- `ctx.cleanup()`: Disconnects all spawned bots and closes database pool connections. (Called automatically by `withTestContext`).

---

### 3.2 Bot Session (`BotSession`)

Defined in [`e2e/lib/bot-session.mjs`](../../e2e/lib/bot-session.mjs), `BotSession` wraps Mineflayer with synchronization helpers and assertion utilities.

#### Core Bot Methods:
- `bot.runCommand(command)`: Sends a chat command (e.g. `bot.runCommand('/world create testworld')`). Automatically prepends `/` if omitted.
- `bot.waitForChat(pattern, [timeoutMs=15000], [startIndex=0])`:
  - Waits for a chat message matching a string or RegExp.
  - Returns the matched message string.
  - Accepts `startIndex` (e.g. `const idx = bot.chatLog.length; ... bot.waitForChat(/.../, 10000, idx)`) to avoid matching prior stale messages.
- `bot.getTabListPlayers()`: Returns an array of usernames currently visible on the bot's tab list (`Object.keys(bot.players)`).
- `bot.assertPlayerVisible(targetUsername, [timeoutMs=10000])`: Asserts that `targetUsername` appears in the bot's tab list within `timeoutMs`.
- `bot.assertPlayerHidden(targetUsername, [timeoutMs=5000])`: Asserts that `targetUsername` is **not** in the bot's tab list (verifying spatial/tablist isolation).
- `bot.getInventoryItems()`: Returns an array of inventory `Item` objects currently held by the bot.
- `bot.waitForInventoryItem(itemName, [count=1], [timeoutMs=10000])`: Waits until the bot receives at least `count` of `itemName` in its inventory.
- `bot.clickWindowSlot(slot, [mouseButton=0], [mode=0])`: Simulates clicking a slot in an open GUI / chest inventory.
- `bot.disconnect()`: Disconnects the bot from the server.

---

### 3.3 Database Client (`DbClient`)

Defined in [`e2e/lib/db-client.mjs`](../../e2e/lib/db-client.mjs):
- `ctx.db.query(sql, [params])`: Runs a parameterized query and returns rows as objects (e.g. `const rows = await ctx.db.query('SELECT * FROM player_world WHERE name = $1', [worldName]);`).
- `ctx.db.checkHealth()`: Checks connectivity (`SELECT 1 AS ok`).
- `ctx.db.truncateTables()`: Cleans all world and lease tables.

---

### 3.4 S3 Storage Helper (`S3Helper`)

Defined in [`e2e/lib/s3-client.mjs`](../../e2e/lib/s3-client.mjs):
- `ctx.s3.checkHealth()`: Validates bucket access (`HeadBucket`).
- `ctx.s3.listObjects(prefix)`: Returns array of S3 objects (`[{ Key, Size, LastModified }]`).
- `ctx.s3.getObject(key)`: Fetches object body and returns it as a string or Buffer.
- `ctx.s3.putObject(key, body)`: Uploads a Buffer/string to S3.
- `ctx.s3.deleteObject(key)`: Deletes an object by key.
- `ctx.s3.clearBucket()`: Deletes all objects in the `gzmn-worlds` bucket.

---

## 4. How to Author a New Test Scenario

All test scenarios reside in `e2e/scenarios/` and follow the naming convention `NN-feature-name.test.mjs`.

### Scenario Boilerplate

Create `e2e/scenarios/08-my-feature.test.mjs`:

```javascript
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withTestContext } from '../lib/test-context.mjs';

/**
 * Scenario 08: My New Feature Test
 *
 * Description of what this scenario proves.
 */
export async function run(ctx) {
  console.log('  [08-my-feature] Spawning Alice...');
  const alice = await ctx.spawnBot('Alice');
  assert.ok(alice.connected, 'Alice must be connected to the cluster');

  // Test logic here...

  console.log('  [08-my-feature] Scenario 08 completed successfully.');
}

export default run;

// Allow direct standalone execution: node e2e/scenarios/08-my-feature.test.mjs
const isDirect = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isDirect) {
  withTestContext(run)
    .then(() => {
      console.log('\x1b[32m08-my-feature passed.\x1b[0m');
      process.exit(0);
    })
    .catch((err) => {
      console.error('\x1b[31m08-my-feature failed:\x1b[0m', err);
      process.exit(1);
    });
}
```

---

## 5. Concrete Test Patterns & Recipes

### Recipe 1: World Creation & Database Verification

```javascript
export async function run(ctx) {
  const alice = await ctx.spawnBot('Alice');
  const worldName = 'survival-island';

  console.log(`Creating world '${worldName}'...`);
  alice.runCommand(`/world create ${worldName}`);

  // 1. Wait for database row in player_world
  let worldRecord = null;
  const start = Date.now();
  while (Date.now() - start < 15000) {
    const rows = await ctx.db.query('SELECT * FROM player_world WHERE name = $1', [worldName]);
    if (rows.length > 0) {
      worldRecord = rows[0];
      break;
    }
    await new Promise((r) => setTimeout(r, 500));
  }

  assert.ok(worldRecord, `Expected player_world row for '${worldName}'`);
  assert.strictEqual(worldRecord.name, worldName);
  assert.ok(['CREATING', 'READY'].includes(worldRecord.state));
  assert.ok(worldRecord.owner_uuid, 'Owner UUID must be populated');

  // 2. Wait for confirmation chat message
  const chat = await alice.waitForChat(/created|creating/i, 10000);
  assert.ok(chat.includes(worldName));
}
```

---

### Recipe 2: Invitations, Membership & Access Control

```javascript
export async function run(ctx) {
  const alice = await ctx.spawnBot('Alice');
  const bob = await ctx.spawnBot('Bob');
  const charlie = await ctx.spawnBot('Charlie');

  // Alice creates world
  alice.runCommand('/world create partyworld');
  await new Promise((r) => setTimeout(r, 2000));

  // Alice invites Bob
  const bobChatIdx = bob.chatLog.length;
  alice.runCommand('/world invite Bob');

  // Bob verifies chat invite
  await bob.waitForChat(/invited you/i, 10000, bobChatIdx);

  // Bob accepts invite
  bob.runCommand('/world accept Alice');
  await bob.waitForChat(/accepted|now a member/i, 10000);

  // Query database to verify membership record
  const members = await ctx.db.query(
    `SELECT m.* FROM player_world_member m
     JOIN player_world w ON w.id = m.world_id
     WHERE w.name = 'partyworld'`
  );
  assert.ok(members.length > 0, 'Bob must be registered in player_world_member');

  // Charlie tries unauthorized join
  const charlieChatIdx = charlie.chatLog.length;
  charlie.runCommand('/world join Alice');
  await charlie.waitForChat(/cannot join|denied|no world/i, 10000, charlieChatIdx);
}
```

---

### Recipe 3: Cross-World Visibility & Tablist Isolation (FR-21 to FR-24)

```javascript
export async function run(ctx) {
  const alice = await ctx.spawnBot('Alice');

  // Alice creates and enters a private world
  alice.runCommand('/world create secretworld');
  await new Promise((r) => setTimeout(r, 2000));

  // Bob joins into the default lobby
  const bob = await ctx.spawnBot('Bob');

  // Assert tablist isolation
  await alice.assertPlayerHidden('Bob', 5000);
  await bob.assertPlayerHidden('Alice', 5000);

  const aliceTab = alice.getTabListPlayers();
  const bobTab = bob.getTabListPlayers();

  assert.ok(!aliceTab.includes('Bob'), 'Alice in private world must NOT see Bob in lobby');
  assert.ok(!bobTab.includes('Alice'), 'Bob in lobby must NOT see Alice in private world');
}
```

---

### Recipe 4: Inventory Persistence & State Snapshots

```javascript
export async function run(ctx) {
  const alice = await ctx.spawnBot('Alice');

  // Give Alice 5 diamonds via RCON on Paper-A
  await ctx.rcon('paper-a', 'give Alice diamond 5');

  // Wait for item in bot inventory
  const diamondItems = await alice.waitForInventoryItem('diamond', 5, 10000);
  assert.ok(diamondItems.length > 0, 'Alice must receive 5 diamonds');

  // Create world (triggers inventory save/profile snapshot)
  alice.runCommand('/world create diamondrealm');
  await new Promise((r) => setTimeout(r, 2000));

  // Query player_world_profile table in PostgreSQL
  const profiles = await ctx.db.query('SELECT * FROM player_world_profile');
  assert.ok(profiles.length > 0, 'player_world_profile row must be created');
}
```

---

### Recipe 5: S3 Snapshot Upload & Verification

```javascript
export async function run(ctx) {
  // Check MinIO bucket health
  const ok = await ctx.s3.checkHealth();
  assert.ok(ok, 'MinIO S3 bucket gzmn-worlds must be accessible');

  const snapshotKey = `worlds/world-uuid/manifest.json`;
  const manifestData = JSON.stringify({ version: 1, created: Date.now(), files: [] });

  // Put object
  await ctx.s3.putObject(snapshotKey, manifestData);

  // List objects
  const objects = await ctx.s3.listObjects('worlds/world-uuid/');
  const match = objects.find((o) => o.Key === snapshotKey);
  assert.ok(match, 'Uploaded manifest must exist in S3');

  // Read back
  const content = await ctx.s3.getObject(snapshotKey);
  assert.strictEqual(content, manifestData);

  // Delete object
  await ctx.s3.deleteObject(snapshotKey);
}
```

---

### Recipe 6: Multi-Node Routing & Lease Verification

```javascript
export async function run(ctx) {
  // 1. Verify RCON health on Paper-A and Paper-B
  const pongA = await ctx.rcon('paper-a', 'e2e ping');
  const pongB = await ctx.rcon('paper-b', 'e2e ping');
  assert.ok(pongA.includes('e2e pong'), 'Paper-A RCON must respond');
  assert.ok(pongB.includes('e2e pong'), 'Paper-B RCON must respond');

  // 2. Query registered cluster nodes
  const nodes = await ctx.db.query('SELECT node_id, address, loaded_worlds FROM worlds_node');
  assert.ok(nodes.length >= 2, 'Both paper-a and paper-b must be registered in worlds_node');

  // 3. Inspect active leases
  const leases = await ctx.db.query('SELECT * FROM world_lease WHERE expires_at > now()');
  console.log(`Active leases: ${leases.length}`);
}
```

---

## 6. Agent Workflows & Command Reference

### Full Ephemeral Run
To run the entire suite in a clean, self-contained run (build -> boot stack -> run tests -> tear down):
```bash
node e2e/cli.mjs run
# or
npm run e2e:run
```

To keep the stack alive after testing for debugging:
```bash
E2E_KEEP=1 node e2e/cli.mjs run
```

---

### Fast Iteration Loop (Recommended for Agent Tasks)

When implementing code changes in Java (`:backend`, `:proxy`, or `:core`):

1. **Boot the cluster once:**
   ```bash
   node e2e/cli.mjs up
   ```

2. **Edit your Java source code files.**

3. **Hot-deploy compiled jars to running server nodes:**
   ```bash
   node e2e/cli.mjs deploy
   ```
   *(This runs Gradle `:backend:shadowJar` + `:proxy:shadowJar`, copies jars to `runtime/paper-a/plugins/`, `runtime/paper-b/plugins/`, and `runtime/velocity/plugins/`, and restarts the server containers in seconds!)*

4. **Reset database & storage state:**
   ```bash
   node e2e/cli.mjs reset
   ```

5. **Run only the specific scenario you are testing:**
   ```bash
   node e2e/cli.mjs test 02-world-lifecycle
   ```

6. **Inspect DB / RCON / S3 directly if something fails:**
   ```bash
   node e2e/cli.mjs sql "SELECT * FROM player_world"
   node e2e/cli.mjs rcon paper-a "e2e status"
   node e2e/cli.mjs s3 ls
   node e2e/cli.mjs logs paper-a
   ```

7. **Run the full test suite when finished:**
   ```bash
   node e2e/cli.mjs test
   ```

---

## 7. Troubleshooting Guide

### Problem 1: Port Collision on Host Ports (5432, 9000, 25565, etc.)
- **Symptom:** Docker compose fails with `Bind for 127.0.0.1:5432 failed: port is already allocated`.
- **Cause:** A local PostgreSQL, MinIO, or Minecraft server is already running on the host machine.
- **Fix:**
  - Check running processes: `Get-NetTCPConnection -LocalPort 5432` (PowerShell) or `lsof -i :5432` (Linux/macOS).
  - Stop the host service or configure alternate ports in `e2e/versions.env` (e.g. `E2E_DB_PORT=5433`).

### Problem 2: Container Readiness Timeout during `up`
- **Symptom:** `✗ Timed out waiting for services to become ready`.
- **Cause:** A container crashed on startup (e.g., jar compilation error or memory limit).
- **Fix:**
  - Check service logs:
    ```bash
    node e2e/cli.mjs logs paper-a
    node e2e/cli.mjs logs velocity
    ```
  - Re-run `node e2e/cli.mjs deploy` or `node e2e/cli.mjs build`.

### Problem 3: Mineflayer Protocol Version Mismatch (776 vs 775)
- **Symptom:** `Kicked from server: Outdated client! Please use 1.21.x / 26.2`.
- **Cause:** Upstream `minecraft-data` packet schemas have not caught up to Paper 26.2.
- **Fix:** `BotSession` includes automatic handshake spoofing for protocol `776`. If testing against a newer Paper build, ensure `E2E_BOT_PROTOCOL_VERSION` in `e2e/versions.env` matches the server protocol version.

### Problem 4: Velocity Forwarding Secret Mismatch
- **Symptom:** Bot disconnects with `Unable to authenticate — did you connect through the proxy?`.
- **Cause:** `forwarding.secret` in `e2e/runtime/velocity/` differs from `paper-global.yml` `velocity.secret`.
- **Fix:** Run `node e2e/cli.mjs build` to re-stage the synchronized runtime config files.

### Problem 5: Stale Database State or Locks
- **Symptom:** Test fails because world name already exists or lease is locked.
- **Fix:** Run `node e2e/cli.mjs reset` to truncate tables and clear S3 snapshots.

---

## 8. Summary Checklist for Submitting Code

Before claiming any task or feature complete:
- [ ] Run `node e2e/cli.mjs deploy`
- [ ] Run `node e2e/cli.mjs reset`
- [ ] Run `node e2e/cli.mjs test` and confirm all scenarios pass
- [ ] Inspect database records with `node e2e/cli.mjs sql "..."` to verify schema integrity
- [ ] Verify test results are included in your completion report
