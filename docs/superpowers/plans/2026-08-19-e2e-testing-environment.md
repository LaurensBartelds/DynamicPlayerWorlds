# DynamicPlayerWorlds Local E2E Testing Environment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a local Docker-based E2E testing environment and Mineflayer multi-bot framework that AI agents can use to test all features of DynamicPlayerWorlds with real player joins, PostgreSQL, and MinIO S3.

**Architecture:** Docker Compose orchestrates PostgreSQL 18.3, MinIO S3, Velocity 4 Proxy, and two Paper 26.2 backend nodes. A cross-platform Node.js Agent CLI (`e2e/cli.mjs`) and test runner drive Mineflayer multi-bot scenarios, query the real PostgreSQL database, inspect MinIO S3 buckets, and issue RCON commands.

**Tech Stack:** Node.js 20+ (ESM), Mineflayer, Docker Compose, PostgreSQL (`pg`), MinIO / AWS S3 SDK (`@aws-sdk/client-s3`), PaperMC, Velocity Proxy, Gradle.

---

### Task 1: Docker Compose & Port Exposure Enhancement

**Files:**
- Modify: `e2e/compose.yml`
- Modify: `e2e/versions.env`

- [ ] **Step 1: Update `e2e/compose.yml` to expose Postgres and MinIO ports to host**

Expose host port `5432` for `postgres`, `9000` (S3 API) and `9001` (Web Console) for `minio` bound to `127.0.0.1`.

```yaml
  postgres:
    image: ${POSTGRES_IMAGE:-postgres:18.3}
    environment:
      POSTGRES_USER: gzmn
      POSTGRES_PASSWORD: ${E2E_POSTGRES_PASSWORD:-e2e-postgres-not-for-prod}
      POSTGRES_DB: gzmn_worlds
    ports:
      - "127.0.0.1:5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U gzmn -d gzmn_worlds"]
      interval: 5s
      timeout: 5s
      retries: 20
      start_period: 10s
    networks: [e2e]

  minio:
    image: ${MINIO_IMAGE:-minio/minio:RELEASE.2025-04-22T22-12-26Z}
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${E2E_MINIO_USER:-gzmn-e2e}
      MINIO_ROOT_PASSWORD: ${E2E_MINIO_PASSWORD:-gzmn-e2e-secret}
    ports:
      - "127.0.0.1:9000:9000"
      - "127.0.0.1:9001:9001"
    networks: [e2e]
```

- [ ] **Step 2: Update `e2e/versions.env` with host connection defaults**

Add default database and S3 variables for host tools:
```env
E2E_DB_HOST=127.0.0.1
E2E_DB_PORT=5432
E2E_DB_USER=gzmn
E2E_DB_PASSWORD=e2e-postgres-not-for-prod
E2E_DB_NAME=gzmn_worlds

E2E_S3_ENDPOINT=http://127.0.0.1:9000
E2E_S3_REGION=us-east-1
E2E_S3_ACCESS_KEY=gzmn-e2e
E2E_S3_SECRET_KEY=gzmn-e2e-secret
E2E_S3_BUCKET=gzmn-worlds
```

- [ ] **Step 3: Validate Compose syntax**

Run: `docker compose -f e2e/compose.yml --env-file e2e/versions.env config`  
Expected: Valid YAML output with ports 5432, 9000, 9001, 25565, 25575, 25576.

- [ ] **Step 4: Commit**

```bash
git add e2e/compose.yml e2e/versions.env
git commit -m "feat(e2e): expose postgres and minio host ports in compose"
```

---

### Task 2: E2E Package Dependencies & Core Client Helpers

**Files:**
- Create: `package.json` (root)
- Modify: `e2e/package.json`
- Create: `e2e/lib/config.mjs`
- Create: `e2e/lib/db-client.mjs`
- Create: `e2e/lib/s3-client.mjs`
- Create: `e2e/lib/rcon-client.mjs`

- [ ] **Step 1: Create `e2e/package.json` with dependencies**

```json
{
  "name": "gzmn-worlds-e2e",
  "version": "0.2.0",
  "private": true,
  "type": "module",
  "scripts": {
    "cli": "node cli.mjs",
    "test": "node lib/test-runner.mjs"
  },
  "engines": {
    "node": ">=20"
  },
  "dependencies": {
    "@aws-sdk/client-s3": "^3.750.0",
    "dotenv": "^16.4.7",
    "mineflayer": "^4.24.0",
    "minecraft-protocol": "1.67.0",
    "pg": "^8.13.3"
  }
}
```

- [ ] **Step 2: Create root `package.json` with npm run shortcuts**

```json
{
  "name": "dynamic-player-worlds",
  "private": true,
  "type": "module",
  "scripts": {
    "e2e": "node e2e/cli.mjs",
    "e2e:up": "node e2e/cli.mjs up",
    "e2e:down": "node e2e/cli.mjs down",
    "e2e:build": "node e2e/cli.mjs build",
    "e2e:deploy": "node e2e/cli.mjs deploy",
    "e2e:reset": "node e2e/cli.mjs reset",
    "e2e:status": "node e2e/cli.mjs status",
    "e2e:test": "node e2e/cli.mjs test",
    "e2e:run": "node e2e/cli.mjs run"
  }
}
```

- [ ] **Step 3: Implement `e2e/lib/config.mjs`**

Loads `e2e/versions.env` using `dotenv` and provides typed configuration constants:
```javascript
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import dotenv from 'dotenv';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
export const E2E_ROOT = path.resolve(__dirname, '..');
export const REPO_ROOT = path.resolve(E2E_ROOT, '..');

dotenv.config({ path: path.join(E2E_ROOT, 'versions.env') });

export const config = {
  host: process.env.E2E_HOST || '127.0.0.1',
  proxyPort: Number(process.env.E2E_PROXY_PORT || 25565),
  rconAPort: Number(process.env.E2E_RCON_A_PORT || 25575),
  rconBPort: Number(process.env.E2E_RCON_B_PORT || 25576),
  rconPassword: process.env.E2E_RCON_PASSWORD || 'e2e-rcon-secret',
  mcVersion: process.env.E2E_BOT_MC_VERSION || '26.1',
  protocolVersion: Number(process.env.E2E_BOT_PROTOCOL_VERSION || 776),
  db: {
    host: process.env.E2E_DB_HOST || '127.0.0.1',
    port: Number(process.env.E2E_DB_PORT || 5432),
    user: process.env.E2E_DB_USER || 'gzmn',
    password: process.env.E2E_DB_PASSWORD || 'e2e-postgres-not-for-prod',
    database: process.env.E2E_DB_NAME || 'gzmn_worlds',
  },
  s3: {
    endpoint: process.env.E2E_S3_ENDPOINT || 'http://127.0.0.1:9000',
    region: process.env.E2E_S3_REGION || 'us-east-1',
    accessKeyId: process.env.E2E_S3_ACCESS_KEY || 'gzmn-e2e',
    secretAccessKey: process.env.E2E_S3_SECRET_KEY || 'gzmn-e2e-secret',
    bucket: process.env.E2E_S3_BUCKET || 'gzmn-worlds',
  },
};
```

- [ ] **Step 4: Implement `e2e/lib/db-client.mjs`**

Provides PostgreSQL connection pool, `query()`, `truncateTables()`, and healthcheck:
```javascript
import pg from 'pg';
import { config } from './config.mjs';

const { Pool } = pg;

export class DbClient {
  constructor() {
    this.pool = new Pool({
      host: config.db.host,
      port: config.db.port,
      user: config.db.user,
      password: config.db.password,
      database: config.db.database,
      connectionTimeoutMillis: 5000,
    });
  }

  async query(text, params = []) {
    return (await this.pool.query(text, params)).rows;
  }

  async checkHealth() {
    const res = await this.query('SELECT 1 AS ok');
    return res.length > 0 && res[0].ok === 1;
  }

  async truncateTables() {
    // Truncate player_world and related tables if they exist
    await this.query(`
      DO $$
      BEGIN
        IF EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'player_world') THEN
          TRUNCATE TABLE player_world CASCADE;
        END IF;
      END $$;
    `);
  }

  async close() {
    await this.pool.end();
  }
}
```

- [ ] **Step 5: Implement `e2e/lib/s3-client.mjs`**

Provides MinIO client for bucket checks, object listing, reading, and clearing:
```javascript
import { S3Client, ListObjectsV2Command, GetObjectCommand, PutObjectCommand, DeleteObjectsCommand, HeadBucketCommand } from '@aws-sdk/client-s3';
import { config } from './config.mjs';

export class S3Helper {
  constructor() {
    this.client = new S3Client({
      endpoint: config.s3.endpoint,
      region: config.s3.region,
      credentials: {
        accessKeyId: config.s3.accessKeyId,
        secretAccessKey: config.s3.secretAccessKey,
      },
      forcePathStyle: true,
    });
    this.bucket = config.s3.bucket;
  }

  async checkHealth() {
    await this.client.send(new HeadBucketCommand({ Bucket: this.bucket }));
    return true;
  }

  async listObjects(prefix = '') {
    const res = await this.client.send(new ListObjectsV2Command({
      Bucket: this.bucket,
      Prefix: prefix,
    }));
    return res.Contents || [];
  }

  async clearBucket() {
    const objects = await this.listObjects();
    if (objects.length === 0) return;
    await this.client.send(new DeleteObjectsCommand({
      Bucket: this.bucket,
      Delete: { Objects: objects.map(o => ({ Key: o.Key })) },
    }));
  }
}
```

- [ ] **Step 6: Implement `e2e/lib/rcon-client.mjs`**

Node.js RCON client using native TCP sockets to communicate with Paper-A and Paper-B:
```javascript
import net from 'node:net';
import { config } from './config.mjs';

const SERVERDATA_AUTH = 3;
const SERVERDATA_EXECCOMMAND = 2;
const SERVERDATA_AUTH_RESPONSE = 2;
const SERVERDATA_RESPONSE_VALUE = 0;

export async function sendRcon(nodeName, command) {
  const port = nodeName === 'paper-b' ? config.rconBPort : config.rconAPort;
  const host = config.host;
  const password = config.rconPassword;

  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port }, () => {
      let authed = false;
      let reqId = 1;

      function sendPacket(id, type, body) {
        const bodyBuf = Buffer.from(body, 'utf8');
        const length = 14 + bodyBuf.length;
        const buf = Buffer.alloc(length);
        buf.writeInt32LE(length - 4, 0);
        buf.writeInt32LE(id, 4);
        buf.writeInt32LE(type, 8);
        bodyBuf.copy(buf, 12);
        buf.writeInt16LE(0, 12 + bodyBuf.length);
        socket.write(buf);
      }

      sendPacket(reqId, SERVERDATA_AUTH, password);

      socket.on('data', (data) => {
        if (data.length < 12) return;
        const type = data.readInt32LE(8);
        if (!authed) {
          if (type === SERVERDATA_AUTH_RESPONSE) {
            const id = data.readInt32LE(4);
            if (id === -1) {
              socket.destroy();
              return reject(new Error('RCON authentication failed'));
            }
            authed = true;
            reqId++;
            sendPacket(reqId, SERVERDATA_EXECCOMMAND, command);
          }
        } else {
          const body = data.subarray(12, data.length - 2).toString('utf8');
          socket.destroy();
          resolve(body);
        }
      });
    });

    socket.setTimeout(10000, () => {
      socket.destroy();
      reject(new Error(`RCON timeout on ${nodeName}:${port}`));
    });

    socket.on('error', reject);
  });
}
```

- [ ] **Step 7: Install dependencies in `e2e/`**

Run: `npm --prefix e2e install`  
Expected: Clean install of `@aws-sdk/client-s3`, `mineflayer`, `minecraft-protocol`, `pg`, `dotenv`.

- [ ] **Step 8: Commit**

```bash
git add package.json e2e/package.json e2e/package-lock.json e2e/lib/
git commit -m "feat(e2e): add DB, S3, RCON client helpers and package setup"
```

---

### Task 3: Mineflayer Multi-Bot Engine & Test Context

**Files:**
- Create: `e2e/lib/bot-session.mjs`
- Create: `e2e/lib/test-context.mjs`
- Create: `e2e/lib/test-runner.mjs`

- [ ] **Step 1: Implement `e2e/lib/bot-session.mjs`**

Wrapper around `mineflayer.createBot` supporting handshake protocol spoofing (`776`), promise-based chat listeners, tab list introspection, inventory queries, window slot clicking, and command execution:
```javascript
import mineflayer from 'mineflayer';
import { config } from './config.mjs';

export class BotSession {
  constructor(username, options = {}) {
    this.username = username;
    this.host = options.host || config.host;
    this.port = options.port || config.proxyPort;
    this.schemaVersion = options.schemaVersion || config.mcVersion;
    this.protocolVersion = options.protocolVersion || config.protocolVersion;
    this.chatLog = [];
    this.bot = null;
  }

  async connect(timeoutMs = 60000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(`Bot ${this.username} timed out after ${timeoutMs}ms waiting for spawn`));
      }, timeoutMs);

      this.bot = mineflayer.createBot({
        host: this.host,
        port: this.port,
        username: this.username,
        auth: 'offline',
        version: this.schemaVersion,
        hideErrors: true,
      });

      // Hook packet writer for protocol spoofing (Paper 26.2 = 776 with 26.1 packet schemas)
      const origWrite = this.bot._client.write.bind(this.bot._client);
      this.bot._client.write = (name, params) => {
        if ((name === 'set_protocol' || name === 'handshake') && params && params.protocolVersion != null) {
          params.protocolVersion = this.protocolVersion;
        }
        return origWrite(name, params);
      };

      this.bot.on('message', (jsonMsg) => {
        const text = jsonMsg.toString();
        this.chatLog.push(text);
      });

      this.bot.once('spawn', () => {
        clearTimeout(timer);
        resolve(this);
      });

      this.bot.once('error', (err) => {
        clearTimeout(timer);
        reject(err);
      });
    });
  }

  async runCommand(command) {
    const formatted = command.startsWith('/') ? command : `/${command}`;
    this.bot.chat(formatted);
  }

  async waitForChat(pattern, timeoutMs = 15000) {
    const regex = pattern instanceof RegExp ? pattern : new RegExp(pattern);
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      for (const msg of this.chatLog) {
        if (regex.test(msg)) return msg;
      }
      await new Promise(r => setTimeout(r, 200));
    }
    throw new Error(`Bot ${this.username} timed out waiting for chat matching ${pattern}`);
  }

  getTabListPlayers() {
    return Object.keys(this.bot.players || {});
  }

  async assertPlayerVisible(targetUsername, timeoutMs = 10000) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      if (this.getTabListPlayers().includes(targetUsername)) return true;
      await new Promise(r => setTimeout(r, 200));
    }
    throw new Error(`Expected player ${targetUsername} to be visible on ${this.username}'s tab list`);
  }

  async assertPlayerHidden(targetUsername, timeoutMs = 5000) {
    await new Promise(r => setTimeout(r, 2000));
    if (this.getTabListPlayers().includes(targetUsername)) {
      throw new Error(`Expected player ${targetUsername} to be hidden from ${this.username}'s tab list`);
    }
    return true;
  }

  getInventoryItems() {
    return this.bot.inventory?.items() || [];
  }

  async disconnect() {
    try {
      this.bot?.end();
    } catch {
      // ignore
    }
  }
}
```

- [ ] **Step 2: Implement `e2e/lib/test-context.mjs`**

Test fixture supporting multi-bot spawning, automatic cleanup, database inspection, S3 inspection, RCON commands, and state resets:
```javascript
import { BotSession } from './bot-session.mjs';
import { DbClient } from './db-client.mjs';
import { S3Helper } from './s3-client.mjs';
import { sendRcon } from './rcon-client.mjs';

export class TestContext {
  constructor() {
    this.bots = [];
    this.db = new DbClient();
    this.s3 = new S3Helper();
  }

  async spawnBot(username, options = {}) {
    const bot = new BotSession(username, options);
    await bot.connect();
    this.bots.push(bot);
    return bot;
  }

  async rcon(nodeName, command) {
    return sendRcon(nodeName, command);
  }

  async resetState() {
    await this.db.truncateTables();
    await this.s3.clearBucket();
  }

  async cleanup() {
    for (const bot of this.bots) {
      await bot.disconnect();
    }
    await this.db.close();
  }
}

export async function withTestContext(testFn) {
  const ctx = new TestContext();
  try {
    await testFn(ctx);
  } finally {
    await ctx.cleanup();
  }
}
```

- [ ] **Step 3: Implement `e2e/lib/test-runner.mjs`**

Scenario discovery and runner with TAP/console reporting:
```javascript
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SCENARIOS_DIR = path.resolve(__dirname, '../scenarios');

export async function runScenarios(filter = '') {
  const files = fs.readdirSync(SCENARIOS_DIR)
    .filter(f => f.endsWith('.test.mjs'))
    .filter(f => !filter || f.includes(filter))
    .sort();

  console.log(`\n==> Running ${files.length} E2E Test Scenarios\n`);
  let passed = 0;
  let failed = 0;

  for (const file of files) {
    const scenarioPath = path.join(SCENARIOS_DIR, file);
    const scenarioName = file.replace('.test.mjs', '');
    process.stdout.write(`• Running scenario: ${scenarioName} ... `);
    const start = Date.now();
    try {
      const module = await import(`file://${scenarioPath}`);
      if (typeof module.default === 'function') {
        await module.default();
      }
      const duration = ((Date.now() - start) / 1000).toFixed(2);
      console.log(`\x1b[32mPASSED\x1b[0m (${duration}s)`);
      passed++;
    } catch (err) {
      const duration = ((Date.now() - start) / 1000).toFixed(2);
      console.log(`\x1b[31mFAILED\x1b[0m (${duration}s)`);
      console.error(err);
      failed++;
    }
  }

  console.log(`\n==> Summary: ${passed} passed, ${failed} failed\n`);
  if (failed > 0) process.exit(1);
}

if (process.argv[1] === __filename) {
  runScenarios(process.argv[2] || '');
}
```

- [ ] **Step 4: Commit**

```bash
git add e2e/lib/
git commit -m "feat(e2e): implement Mineflayer multi-bot session, context, and test runner"
```

---

### Task 4: Cross-Platform Agent CLI (`e2e/cli.mjs`)

**Files:**
- Create: `e2e/cli.mjs`

- [ ] **Step 1: Implement `e2e/cli.mjs`**

Provides CLI commands (`up`, `down`, `build`, `deploy`, `reset`, `status`, `logs`, `rcon`, `sql`, `s3`, `test`, `run`):
- Cross-platform Gradle execution (`gradlew.bat` on Windows, `./gradlew` on Unix)
- Docker compose lifecycle management
- Readiness polling for Postgres, MinIO, Velocity, Paper-A, Paper-B
- Interactive RCON and SQL execution for quick agent inspection

- [ ] **Step 2: Verify CLI help and status**

Run: `node e2e/cli.mjs --help`  
Expected: Displays list of available commands and usage guide.

- [ ] **Step 3: Commit**

```bash
git add e2e/cli.mjs
git commit -m "feat(e2e): add cross-platform agent CLI"
```

---

### Task 5: Core Test Scenarios Implementation

**Files:**
- Create: `e2e/scenarios/01-lobby-join.test.mjs`
- Create: `e2e/scenarios/02-world-lifecycle.test.mjs`
- Create: `e2e/scenarios/03-membership-invites.test.mjs`
- Create: `e2e/scenarios/04-visibility-isolation.test.mjs`
- Create: `e2e/scenarios/05-inventory-isolation.test.mjs`
- Create: `e2e/scenarios/06-s3-persistence.test.mjs`
- Create: `e2e/scenarios/07-multi-node-routing.test.mjs`

- [ ] **Step 1: Implement `e2e/scenarios/01-lobby-join.test.mjs`**

Verifies two bots (Alice & Bob) connecting through Velocity to Paper-A lobby, checking position packets, tab list visibility, and RCON join status.

- [ ] **Step 2: Implement `e2e/scenarios/02-world-lifecycle.test.mjs`**

Verifies `/world create`, DB record in `player_world`, lease acquisition in `world_lease`, and `/world delete`.

- [ ] **Step 3: Implement `e2e/scenarios/03-membership-invites.test.mjs`**

Verifies `/world invite`, invite acceptance, membership row creation in `player_world_member`, and rejecting uninvited players.

- [ ] **Step 4: Implement `e2e/scenarios/04-visibility-isolation.test.mjs`**

Verifies tab list and spatial hide/show between private worlds and lobby (`FR-21` to `FR-24`).

- [ ] **Step 5: Implement `e2e/scenarios/05-inventory-isolation.test.mjs`**

Verifies player inventory separation between lobby and private worlds.

- [ ] **Step 6: Implement `e2e/scenarios/06-s3-persistence.test.mjs`**

Verifies S3 object creation in MinIO bucket `gzmn-worlds` upon world save/unload.

- [ ] **Step 7: Implement `e2e/scenarios/07-multi-node-routing.test.mjs`**

Verifies lease handoff and routing from Paper-A to Paper-B.

- [ ] **Step 8: Commit**

```bash
git add e2e/scenarios/
git commit -m "feat(e2e): implement core feature test scenarios 01 through 07"
```

---

### Task 6: Documentation & Agent Guide

**Files:**
- Modify: `e2e/README.md`
- Create: `docs/plans/01-e2e-agent-testing-guide.md`

- [ ] **Step 1: Update `e2e/README.md` with complete architecture and CLI reference**
- [ ] **Step 2: Create `docs/plans/01-e2e-agent-testing-guide.md` for AI agent instructions**
- [ ] **Step 3: Commit**

```bash
git add e2e/README.md docs/plans/01-e2e-agent-testing-guide.md
git commit -m "docs(e2e): update e2e readme and add agent testing guide"
```

---

### Task 7: Full Stack Verification & Validation

- [ ] **Step 1: Build jars via CLI**

Run: `node e2e/cli.mjs build`  
Expected: Gradle builds `:backend`, `:proxy`, and `:e2e-harness` jars successfully and stages runtime.

- [ ] **Step 2: Start stack via CLI**

Run: `node e2e/cli.mjs up`  
Expected: Docker containers boot, healthchecks pass, RCON responds with `e2e pong`.

- [ ] **Step 3: Execute full test suite**

Run: `node e2e/cli.mjs test`  
Expected: Scenarios run and pass with Mineflayer bots.

- [ ] **Step 4: Verify SQL and S3 CLI helpers**

Run: `node e2e/cli.mjs sql "SELECT 1 AS ok"` and `node e2e/cli.mjs s3 ls`  
Expected: Clean formatted output.

- [ ] **Step 5: Commit any final refinements**

```bash
git add -A
git commit -m "chore(e2e): verify full local e2e testing environment"
```
