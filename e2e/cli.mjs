#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { config, E2E_ROOT, REPO_ROOT } from './lib/config.mjs';
import { DbClient } from './lib/db-client.mjs';
import { S3Helper } from './lib/s3-client.mjs';
import { sendRcon } from './lib/rcon-client.mjs';
import { runTests } from './lib/test-runner.mjs';

function checkTcpPort(host, port, timeoutMs = 2000) {
  return new Promise((resolve) => {
    const socket = new net.Socket();
    let resolved = false;

    const finish = (result) => {
      if (resolved) return;
      resolved = true;
      try {
        socket.destroy();
      } catch {
        // ignore
      }
      resolve(result);
    };

    socket.setTimeout(timeoutMs);
    socket.on('connect', () => finish(true));
    socket.on('timeout', () => finish(false));
    socket.on('error', () => finish(false));
    socket.connect(port, host);
  });
}

async function executeDockerCompose(args, options = {}) {
  const composeArgs = ['compose', '-f', 'compose.yml', '--env-file', 'versions.env', ...args];
  return new Promise((resolve, reject) => {
    const child = spawn('docker', composeArgs, {
      cwd: E2E_ROOT,
      stdio: options.stdio || 'inherit',
    });

    let stdout = '';
    let stderr = '';
    if (options.stdio === 'pipe') {
      child.stdout?.on('data', (d) => { stdout += d; });
      child.stderr?.on('data', (d) => { stderr += d; });
    }

    child.on('error', reject);
    child.on('close', (code) => {
      if (code === 0 || options.allowNonZero) {
        resolve({ code, stdout, stderr });
      } else {
        reject(new Error(`docker compose ${args.join(' ')} failed with exit code ${code}`));
      }
    });
  });
}

async function executeGradle(tasks = [':backend:shadowJar', ':proxy:shadowJar', ':e2e-harness:jar']) {
  const isWin = process.platform === 'win32';
  const gradlewCmd = isWin ? 'gradlew.bat' : './gradlew';
  const gradlewPath = path.join(REPO_ROOT, gradlewCmd);

  if (!fs.existsSync(gradlewPath)) {
    throw new Error(`Gradle wrapper not found at ${gradlewPath}`);
  }

  console.log(`==> Executing Gradle (${tasks.join(' ')})...`);

  return new Promise((resolve, reject) => {
    const child = isWin
      ? spawn('cmd.exe', ['/c', 'gradlew.bat', '--console=plain', ...tasks], {
          cwd: REPO_ROOT,
          stdio: 'inherit',
        })
      : spawn('./gradlew', ['--console=plain', ...tasks], {
          cwd: REPO_ROOT,
          stdio: 'inherit',
        });

    child.on('error', reject);
    child.on('close', (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`Gradle execution failed with exit code ${code}`));
      }
    });
  });
}

async function downloadPinned(url, sha256, dest) {
  const dir = path.dirname(dest);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }

  if (fs.existsSync(dest)) {
    const fileBuffer = fs.readFileSync(dest);
    const hash = crypto.createHash('sha256').update(fileBuffer).digest('hex');
    if (hash.toLowerCase() === sha256.toLowerCase()) {
      return;
    }
    console.log(`Stale download (checksum mismatch), re-fetching ${path.basename(dest)}`);
    fs.unlinkSync(dest);
  }

  console.log(`Downloading ${path.basename(dest)}...`);
  const tmpDest = `${dest}.part`;
  if (fs.existsSync(tmpDest)) {
    fs.unlinkSync(tmpDest);
  }

  const userAgent = process.env.PAPER_USER_AGENT || 'gzmn-worlds-ci/0.1 (+https://github.com/GZMN/DynamicPlayerWorlds)';
  const res = await fetch(url, {
    headers: {
      'User-Agent': userAgent,
    },
  });

  if (!res.ok) {
    throw new Error(`Failed to download ${url}: ${res.status} ${res.statusText}`);
  }

  const arrayBuffer = await res.arrayBuffer();
  const buffer = Buffer.from(arrayBuffer);
  const hash = crypto.createHash('sha256').update(buffer).digest('hex');
  if (hash.toLowerCase() !== sha256.toLowerCase()) {
    throw new Error(`Checksum mismatch for ${dest}: expected ${sha256}, got ${hash}`);
  }

  fs.writeFileSync(tmpDest, buffer);
  fs.renameSync(tmpDest, dest);
  console.log(`Downloaded and verified ${path.basename(dest)}`);
}

function findLatestJar(pattern, searchDir) {
  if (!fs.existsSync(searchDir)) return null;
  const files = fs.readdirSync(searchDir);
  const matching = files
    .filter((f) => f.endsWith('.jar') && !f.endsWith('-sources.jar') && !f.endsWith('-javadoc.jar'))
    .filter((f) => {
      if (typeof pattern === 'string') return f.includes(pattern);
      if (pattern instanceof RegExp) return pattern.test(f);
      return true;
    });
  if (matching.length === 0) return null;
  return path.join(searchDir, matching[0]);
}

function stagePaperNode(node, motd, downloadsDir, backendJar, harnessJar) {
  const dest = path.join(E2E_ROOT, 'runtime', node);
  if (fs.existsSync(dest)) {
    fs.rmSync(dest, { recursive: true, force: true });
  }
  fs.mkdirSync(path.join(dest, 'plugins'), { recursive: true });
  fs.mkdirSync(path.join(dest, 'config'), { recursive: true });

  const paperMcVersion = process.env.PAPER_MC_VERSION || '26.2';
  const paperBuild = process.env.PAPER_BUILD || '112';
  const paperDownload = path.join(downloadsDir, `paper-${paperMcVersion}-${paperBuild}.jar`);
  fs.copyFileSync(paperDownload, path.join(dest, 'paper.jar'));

  const paperConfigDir = path.join(E2E_ROOT, 'config', 'paper');
  if (fs.existsSync(path.join(paperConfigDir, 'eula.txt'))) {
    fs.copyFileSync(path.join(paperConfigDir, 'eula.txt'), path.join(dest, 'eula.txt'));
  } else {
    fs.writeFileSync(path.join(dest, 'eula.txt'), 'eula=true\n');
  }

  if (fs.existsSync(path.join(paperConfigDir, 'server.properties'))) {
    let serverProps = fs.readFileSync(path.join(paperConfigDir, 'server.properties'), 'utf8');
    if (serverProps.includes('motd=')) {
      serverProps = serverProps.replace(/^motd=.*$/m, `motd=${motd}`);
    } else {
      serverProps += `\nmotd=${motd}\n`;
    }
    fs.writeFileSync(path.join(dest, 'server.properties'), serverProps);
  }

  if (fs.existsSync(path.join(paperConfigDir, 'bukkit.yml'))) {
    fs.copyFileSync(path.join(paperConfigDir, 'bukkit.yml'), path.join(dest, 'bukkit.yml'));
  }
  if (fs.existsSync(path.join(paperConfigDir, 'spigot.yml'))) {
    fs.copyFileSync(path.join(paperConfigDir, 'spigot.yml'), path.join(dest, 'spigot.yml'));
  }
  if (fs.existsSync(path.join(paperConfigDir, 'config', 'paper-global.yml'))) {
    fs.copyFileSync(path.join(paperConfigDir, 'config', 'paper-global.yml'), path.join(dest, 'config', 'paper-global.yml'));
  }
  if (fs.existsSync(path.join(paperConfigDir, 'config', 'paper-world-defaults.yml'))) {
    fs.copyFileSync(path.join(paperConfigDir, 'config', 'paper-world-defaults.yml'), path.join(dest, 'config', 'paper-world-defaults.yml'));
  }

  fs.copyFileSync(backendJar, path.join(dest, 'plugins', path.basename(backendJar)));
  fs.copyFileSync(harnessJar, path.join(dest, 'plugins', path.basename(harnessJar)));

  const gzmnWorldsConfigDir = path.join(dest, 'plugins', 'gzmn-worlds');
  fs.mkdirSync(gzmnWorldsConfigDir, { recursive: true });
  const postgresPassword = process.env.E2E_POSTGRES_PASSWORD || 'e2e-postgres-not-for-prod';
  const gzmnConfig = `node:
  id: ${node}
  address: ${node}:25565
  heartbeat-seconds: 30

database:
  url: jdbc:postgresql://postgres:5432/gzmn_worlds
  user: gzmn
  password: "${postgresPassword}"
  pool-size: 8
  connection-timeout-seconds: 10

storage:
  local-scratch-path: ""
  local-cache-path: cache
  quarantine-path: quarantine
  min-free-space-bytes: 0
  s3:
    enabled: false

metrics:
  bind: 0.0.0.0
  port: 9464
`;
  fs.writeFileSync(path.join(gzmnWorldsConfigDir, 'config.yml'), gzmnConfig);

  fs.writeFileSync(path.join(dest, 'ops.json'), '[]\n');
  fs.writeFileSync(path.join(dest, 'whitelist.json'), '[]\n');
  fs.writeFileSync(path.join(dest, 'banned-players.json'), '[]\n');
  fs.writeFileSync(path.join(dest, 'banned-ips.json'), '[]\n');
  fs.writeFileSync(path.join(dest, 'usercache.json'), '{}\n');

  console.log(`  Staged ${node}`);
}

function stageVelocity(downloadsDir, proxyJar) {
  const dest = path.join(E2E_ROOT, 'runtime', 'velocity');
  if (fs.existsSync(dest)) {
    fs.rmSync(dest, { recursive: true, force: true });
  }
  fs.mkdirSync(path.join(dest, 'plugins'), { recursive: true });

  const velVersion = process.env.VELOCITY_VERSION || '4.0.0';
  const velBuild = process.env.VELOCITY_BUILD || '6';
  const velDownload = path.join(downloadsDir, `velocity-${velVersion}-${velBuild}.jar`);
  fs.copyFileSync(velDownload, path.join(dest, 'velocity.jar'));

  const velConfigDir = path.join(E2E_ROOT, 'config', 'velocity');
  if (fs.existsSync(path.join(velConfigDir, 'velocity.toml'))) {
    fs.copyFileSync(path.join(velConfigDir, 'velocity.toml'), path.join(dest, 'velocity.toml'));
  }

  const forwardingSecret = process.env.E2E_FORWARDING_SECRET || 'e2e-forwarding-secret-not-for-prod';
  fs.writeFileSync(path.join(dest, 'forwarding.secret'), forwardingSecret);

  fs.copyFileSync(proxyJar, path.join(dest, 'plugins', path.basename(proxyJar)));

  const proxyConfigDir = path.join(dest, 'plugins', 'gzmn-worlds-proxy');
  fs.mkdirSync(proxyConfigDir, { recursive: true });
  const postgresPassword = process.env.E2E_POSTGRES_PASSWORD || 'e2e-postgres-not-for-prod';
  const proxyConfig = `lobby-server = "lobby"

[database]
url = "jdbc:postgresql://postgres:5432/gzmn_worlds"
user = "gzmn"
password = "${postgresPassword}"
pool-size = 8
connection-timeout-seconds = 10
`;
  fs.writeFileSync(path.join(proxyConfigDir, 'config.toml'), proxyConfig);

  console.log(`  Staged velocity`);
}

async function buildCommand() {
  console.log(`==> Building plugin jars and staging runtime environment...`);
  await executeGradle([':backend:shadowJar', ':proxy:shadowJar', ':e2e-harness:jar']);

  const backendJar = findLatestJar('gzmn-worlds-', path.join(REPO_ROOT, 'backend', 'build', 'libs'));
  const proxyJar = findLatestJar('gzmn-worlds-proxy-', path.join(REPO_ROOT, 'proxy', 'build', 'libs'));
  const harnessJar = findLatestJar('e2e-harness-', path.join(REPO_ROOT, 'e2e', 'harness-plugin', 'build', 'libs'));

  if (!backendJar || !proxyJar || !harnessJar) {
    throw new Error(
      `Missing built jars:\n  backend: ${backendJar}\n  proxy: ${proxyJar}\n  harness: ${harnessJar}`
    );
  }

  console.log(`Backend jar: ${backendJar}`);
  console.log(`Proxy jar:   ${proxyJar}`);
  console.log(`Harness jar: ${harnessJar}`);

  const downloadsDir = path.join(E2E_ROOT, 'downloads');
  if (!fs.existsSync(downloadsDir)) {
    fs.mkdirSync(downloadsDir, { recursive: true });
  }

  const paperUrl = process.env.PAPER_SERVER_URL;
  const paperSha = process.env.PAPER_SERVER_SHA256;
  const paperMcVersion = process.env.PAPER_MC_VERSION || '26.2';
  const paperBuild = process.env.PAPER_BUILD || '112';

  const velUrl = process.env.VELOCITY_SERVER_URL;
  const velSha = process.env.VELOCITY_SERVER_SHA256;
  const velVersion = process.env.VELOCITY_VERSION || '4.0.0';
  const velBuild = process.env.VELOCITY_BUILD || '6';

  if (paperUrl && paperSha) {
    await downloadPinned(paperUrl, paperSha, path.join(downloadsDir, `paper-${paperMcVersion}-${paperBuild}.jar`));
  }
  if (velUrl && velSha) {
    await downloadPinned(velUrl, velSha, path.join(downloadsDir, `velocity-${velVersion}-${velBuild}.jar`));
  }

  console.log(`==> Staging runtime trees in ${path.join(E2E_ROOT, 'runtime')}...`);
  const runtimeDir = path.join(E2E_ROOT, 'runtime');
  if (!fs.existsSync(runtimeDir)) {
    fs.mkdirSync(runtimeDir, { recursive: true });
  }

  stagePaperNode('paper-a', 'gzmn-e2e-lobby', downloadsDir, backendJar, harnessJar);
  stagePaperNode('paper-b', 'gzmn-e2e-node-b', downloadsDir, backendJar, harnessJar);
  stageVelocity(downloadsDir, proxyJar);

  console.log(`\x1b[32m✔ Build and staging complete.\x1b[0m\n`);
}

async function upCommand(options = {}) {
  const timeoutSec = options.timeout || 300;
  console.log(`==> Bringing up E2E Compose stack...`);
  await executeDockerCompose(['up', '-d', '--pull', 'missing']);

  console.log(`==> Waiting for all services to become ready (timeout ${timeoutSec}s)...`);
  const startTime = Date.now();
  const deadline = startTime + timeoutSec * 1000;

  const status = {
    postgres: false,
    minio: false,
    paperA: false,
    paperB: false,
    velocity: false,
  };

  while (Date.now() < deadline) {
    const checks = await Promise.allSettled([
      (async () => {
        if (status.postgres) return true;
        const db = new DbClient();
        try {
          return await db.checkHealth();
        } finally {
          await db.close().catch(() => {});
        }
      })(),
      (async () => {
        if (status.minio) return true;
        const s3 = new S3Helper();
        return await s3.checkHealth();
      })(),
      (async () => {
        if (status.paperA) return true;
        try {
          const res = await sendRcon('paper-a', 'e2e ping', { timeoutMs: 2000 });
          return res.includes('e2e pong');
        } catch {
          return false;
        }
      })(),
      (async () => {
        if (status.paperB) return true;
        try {
          const res = await sendRcon('paper-b', 'e2e ping', { timeoutMs: 2000 });
          return res.includes('e2e pong');
        } catch {
          return false;
        }
      })(),
      (async () => {
        if (status.velocity) return true;
        return await checkTcpPort(config.host, config.proxyPort, 2000);
      })(),
    ]);

    status.postgres = checks[0].status === 'fulfilled' && checks[0].value === true;
    status.minio = checks[1].status === 'fulfilled' && checks[1].value === true;
    status.paperA = checks[2].status === 'fulfilled' && checks[2].value === true;
    status.paperB = checks[3].status === 'fulfilled' && checks[3].value === true;
    status.velocity = checks[4].status === 'fulfilled' && checks[4].value === true;

    const allReady = Object.values(status).every(Boolean);
    const elapsedSec = Math.round((Date.now() - startTime) / 1000);

    const progressStr = [
      `Postgres: ${status.postgres ? '✓' : '…'}`,
      `MinIO: ${status.minio ? '✓' : '…'}`,
      `Paper-A: ${status.paperA ? '✓' : '…'}`,
      `Paper-B: ${status.paperB ? '✓' : '…'}`,
      `Velocity: ${status.velocity ? '✓' : '…'}`,
    ].join(' | ');

    process.stdout.write(`\r  [${elapsedSec}s] ${progressStr}  `);

    if (allReady) {
      console.log(`\n\x1b[32m✔ All services are ready and responsive!\x1b[0m\n`);
      return true;
    }

    await new Promise((r) => setTimeout(r, 2000));
  }

  console.log(`\n\x1b[31m✗ Timed out waiting for services to become ready.\x1b[0m`);
  console.log(`Status at timeout:`, status);
  console.log(`Check logs with: node e2e/cli.mjs logs [service]`);
  throw new Error('E2E stack failed to reach ready state within timeout');
}

async function downCommand() {
  console.log(`==> Stopping E2E Compose stack...`);
  await executeDockerCompose(['down', '-v', '--remove-orphans']);
  console.log(`\x1b[32m✔ Compose stack stopped and volumes removed.\x1b[0m\n`);
}

async function deployCommand() {
  console.log(`==> Deploying built jars to runtime directories...`);

  let backendJar = findLatestJar('gzmn-worlds-', path.join(REPO_ROOT, 'backend', 'build', 'libs'));
  let proxyJar = findLatestJar('gzmn-worlds-proxy-', path.join(REPO_ROOT, 'proxy', 'build', 'libs'));
  let harnessJar = findLatestJar('e2e-harness-', path.join(REPO_ROOT, 'e2e', 'harness-plugin', 'build', 'libs'));

  if (!backendJar || !proxyJar || !harnessJar) {
    console.log(`Built jars not found. Running build first...`);
    await buildCommand();
    backendJar = findLatestJar('gzmn-worlds-', path.join(REPO_ROOT, 'backend', 'build', 'libs'));
    proxyJar = findLatestJar('gzmn-worlds-proxy-', path.join(REPO_ROOT, 'proxy', 'build', 'libs'));
    harnessJar = findLatestJar('e2e-harness-', path.join(REPO_ROOT, 'e2e', 'harness-plugin', 'build', 'libs'));
  }

  for (const node of ['paper-a', 'paper-b']) {
    const pluginsDir = path.join(E2E_ROOT, 'runtime', node, 'plugins');
    if (fs.existsSync(pluginsDir)) {
      fs.copyFileSync(backendJar, path.join(pluginsDir, path.basename(backendJar)));
      fs.copyFileSync(harnessJar, path.join(pluginsDir, path.basename(harnessJar)));
      console.log(`  Copied jars to ${node}/plugins/`);
    }
  }

  const velPluginsDir = path.join(E2E_ROOT, 'runtime', 'velocity', 'plugins');
  if (fs.existsSync(velPluginsDir)) {
    fs.copyFileSync(proxyJar, path.join(velPluginsDir, path.basename(proxyJar)));
    console.log(`  Copied jar to velocity/plugins/`);
  }

  console.log(`==> Restarting server containers...`);
  try {
    await executeDockerCompose(['restart', 'paper-a', 'paper-b', 'velocity']);
    console.log(`\x1b[32m✔ Server nodes restarted.\x1b[0m\n`);
  } catch (err) {
    console.warn(`\x1b[33mRestart note (containers may not be running):\x1b[0m ${err.message}`);
  }
}

async function resetCommand() {
  console.log(`==> Resetting E2E test state...`);

  // 1. Truncate PostgreSQL tables
  try {
    const db = new DbClient();
    await db.truncateTables();
    await db.close();
    console.log(`  \x1b[32m✓\x1b[0m PostgreSQL tables truncated`);
  } catch (err) {
    console.warn(`  \x1b[33m!\x1b[0m PostgreSQL truncate note: ${err.message}`);
  }

  // 2. Flush MinIO bucket
  try {
    const s3 = new S3Helper();
    await s3.clearBucket();
    console.log(`  \x1b[32m✓\x1b[0m MinIO S3 bucket cleared`);
  } catch (err) {
    console.warn(`  \x1b[33m!\x1b[0m MinIO S3 clear note: ${err.message}`);
  }

  // 3. Clear temporary world directories
  for (const node of ['paper-a', 'paper-b']) {
    const nodeDir = path.join(E2E_ROOT, 'runtime', node);
    if (!fs.existsSync(nodeDir)) continue;

    const entries = fs.readdirSync(nodeDir, { withFileTypes: true });
    for (const entry of entries) {
      if (entry.isDirectory() && entry.name.startsWith('world_')) {
        try {
          fs.rmSync(path.join(nodeDir, entry.name), { recursive: true, force: true });
          console.log(`  \x1b[32m✓\x1b[0m Cleared temporary world directory ${node}/${entry.name}`);
        } catch (e) {
          console.warn(`  \x1b[33m!\x1b[0m Could not delete ${node}/${entry.name}: ${e.message}`);
        }
      }
    }

    const lastJoinFile = path.join(nodeDir, 'plugins', 'e2e-harness', 'last-join.txt');
    if (fs.existsSync(lastJoinFile)) {
      try {
        fs.unlinkSync(lastJoinFile);
      } catch {
        // ignore
      }
    }
  }

  console.log(`\x1b[32m✔ Reset complete.\x1b[0m\n`);
}

async function statusCommand() {
  console.log(`\n\x1b[1mChecking E2E Service Status...\x1b[0m\n`);

  const results = await Promise.allSettled([
    (async () => {
      const db = new DbClient();
      try {
        const ok = await db.checkHealth();
        return {
          service: 'PostgreSQL',
          endpoint: `${config.db.host}:${config.db.port}`,
          status: ok ? '\x1b[32mONLINE\x1b[0m' : '\x1b[31mOFFLINE\x1b[0m',
          notes: ok ? 'Ready (SELECT 1)' : 'Connection failed',
        };
      } catch (err) {
        return {
          service: 'PostgreSQL',
          endpoint: `${config.db.host}:${config.db.port}`,
          status: '\x1b[31mOFFLINE\x1b[0m',
          notes: err.message,
        };
      } finally {
        await db.close().catch(() => {});
      }
    })(),

    (async () => {
      const s3 = new S3Helper();
      try {
        const ok = await s3.checkHealth();
        return {
          service: 'MinIO S3',
          endpoint: config.s3.endpoint,
          status: ok ? '\x1b[32mONLINE\x1b[0m' : '\x1b[31mOFFLINE\x1b[0m',
          notes: ok ? `Bucket '${config.s3.bucket}' ready` : 'Bucket check failed / unreachable',
        };
      } catch (err) {
        return {
          service: 'MinIO S3',
          endpoint: config.s3.endpoint,
          status: '\x1b[31mOFFLINE\x1b[0m',
          notes: err.message,
        };
      }
    })(),

    (async () => {
      try {
        const res = await sendRcon('paper-a', 'e2e ping', { timeoutMs: 2000 });
        const ok = res.includes('e2e pong');
        return {
          service: 'Paper-A RCON',
          endpoint: `${config.host}:${config.rconAPort}`,
          status: ok ? '\x1b[32mONLINE\x1b[0m' : '\x1b[33mUNEXPECTED\x1b[0m',
          notes: res.trim() || 'Connected',
        };
      } catch (err) {
        return {
          service: 'Paper-A RCON',
          endpoint: `${config.host}:${config.rconAPort}`,
          status: '\x1b[31mOFFLINE\x1b[0m',
          notes: err.message,
        };
      }
    })(),

    (async () => {
      try {
        const res = await sendRcon('paper-b', 'e2e ping', { timeoutMs: 2000 });
        const ok = res.includes('e2e pong');
        return {
          service: 'Paper-B RCON',
          endpoint: `${config.host}:${config.rconBPort}`,
          status: ok ? '\x1b[32mONLINE\x1b[0m' : '\x1b[33mUNEXPECTED\x1b[0m',
          notes: res.trim() || 'Connected',
        };
      } catch (err) {
        return {
          service: 'Paper-B RCON',
          endpoint: `${config.host}:${config.rconBPort}`,
          status: '\x1b[31mOFFLINE\x1b[0m',
          notes: err.message,
        };
      }
    })(),

    (async () => {
      try {
        const ok = await checkTcpPort(config.host, config.proxyPort, 2000);
        return {
          service: 'Velocity Proxy',
          endpoint: `${config.host}:${config.proxyPort}`,
          status: ok ? '\x1b[32mONLINE\x1b[0m' : '\x1b[31mOFFLINE\x1b[0m',
          notes: ok ? 'TCP port open' : 'Port closed / unreachable',
        };
      } catch (err) {
        return {
          service: 'Velocity Proxy',
          endpoint: `${config.host}:${config.proxyPort}`,
          status: '\x1b[31mOFFLINE\x1b[0m',
          notes: err.message,
        };
      }
    })(),
  ]);

  const rows = results.map((r) =>
    r.status === 'fulfilled' ? r.value : { service: 'Unknown', endpoint: '-', status: 'ERROR', notes: r.reason?.message }
  );

  console.table(rows);
}

async function logsCommand(service) {
  const args = ['logs', '-f'];
  if (service) {
    args.push(service);
  }
  await executeDockerCompose(args);
}

async function rconCommand(node, ...commandParts) {
  const cmd = commandParts.join(' ').trim();
  if (!node || !cmd) {
    console.error(`Usage: node e2e/cli.mjs rcon <node> <command>`);
    console.error(`Example: node e2e/cli.mjs rcon paper-a "e2e ping"`);
    process.exit(1);
  }

  try {
    const response = await sendRcon(node, cmd);
    console.log(response);
  } catch (err) {
    console.error(`\x1b[31mRCON Error (${node}):\x1b[0m`, err.message);
    process.exit(1);
  }
}

async function sqlCommand(query) {
  if (!query) {
    console.error(`Usage: node e2e/cli.mjs sql "<SQL QUERY>"`);
    console.error(`Example: node e2e/cli.mjs sql "SELECT * FROM player_world"`);
    process.exit(1);
  }

  const db = new DbClient();
  try {
    const res = await db.query(query);
    if (Array.isArray(res)) {
      if (res.length > 0) {
        console.table(res);
      } else {
        console.log(`(0 rows returned)`);
      }
    } else {
      console.log(res);
    }
  } catch (err) {
    console.error(`\x1b[31mSQL Query Error:\x1b[0m`, err.message);
    process.exit(1);
  } finally {
    await db.close().catch(() => {});
  }
}

async function s3Command(subcommand, ...args) {
  const s3 = new S3Helper();
  try {
    if (subcommand === 'ls') {
      const prefix = args[0] || '';
      const objects = await s3.listObjects(prefix);
      if (objects.length === 0) {
        console.log(`Bucket '${config.s3.bucket}' has no objects matching prefix '${prefix}'.`);
      } else {
        const rows = objects.map((o) => ({
          Key: o.Key,
          Size: o.Size,
          LastModified: o.LastModified ? new Date(o.LastModified).toISOString() : '-',
        }));
        console.table(rows);
      }
    } else if (subcommand === 'cat') {
      const key = args[0];
      if (!key) {
        console.error(`Usage: node e2e/cli.mjs s3 cat <key>`);
        process.exit(1);
      }
      const data = await s3.getObject(key);
      console.log(data);
    } else if (subcommand === 'rm') {
      const target = args[0];
      if (!target) {
        console.error(`Usage: node e2e/cli.mjs s3 rm <key|--all>`);
        process.exit(1);
      }
      if (target === '--all') {
        await s3.clearBucket();
        console.log(`\x1b[32m✔ Cleared all objects in bucket '${config.s3.bucket}'.\x1b[0m`);
      } else {
        await s3.deleteObject(target);
        console.log(`\x1b[32m✔ Deleted object '${target}' from bucket '${config.s3.bucket}'.\x1b[0m`);
      }
    } else {
      console.error(`Unknown s3 subcommand: '${subcommand}'. Available: ls, cat, rm`);
      process.exit(1);
    }
  } catch (err) {
    console.error(`\x1b[31mS3 Error:\x1b[0m`, err.message);
    process.exit(1);
  }
}

async function runWorkflow(filter) {
  console.log(`\x1b[1m\x1b[34m=======================================================\x1b[0m`);
  console.log(`\x1b[1m Starting Ephemeral E2E Test Run\x1b[0m`);
  console.log(`\x1b[1m\x1b[34m=======================================================\x1b[0m\n`);

  let testPassed = false;
  try {
    await buildCommand();
    await upCommand();
    testPassed = await runTests({ filter });
  } finally {
    if (process.env.E2E_KEEP === '1') {
      console.log(`\x1b[33mE2E_KEEP=1 — leaving compose stack running.\x1b[0m`);
    } else {
      console.log(`==> Tearing down compose stack...`);
      await downCommand().catch((err) => {
        console.error(`Error during teardown:`, err.message);
      });
    }
  }

  process.exit(testPassed ? 0 : 1);
}

function helpCommand() {
  console.log(`
\x1b[1m\x1b[36mDynamicPlayerWorlds E2E CLI\x1b[0m

\x1b[1mUSAGE:\x1b[0m
  node e2e/cli.mjs <command> [arguments...]

\x1b[1mCOMMANDS:\x1b[0m
  \x1b[1mup\x1b[0m                 Start docker compose services and wait for full readiness
  \x1b[1mdown\x1b[0m               Stop docker compose services and remove volumes
  \x1b[1mbuild\x1b[0m              Build Gradle jars, download pinned servers, and stage runtime
  \x1b[1mdeploy\x1b[0m             Hot-copy built plugin jars to runtime and restart server nodes
  \x1b[1mreset\x1b[0m              Reset state (truncate DB tables, flush S3 bucket, clear temp worlds)
  \x1b[1mstatus\x1b[0m             Check health of Postgres, MinIO, Paper nodes, and Velocity
  \x1b[1mlogs\x1b[0m [service]     Tail logs for a service (paper-a, paper-b, velocity, postgres, minio)
  \x1b[1mrcon\x1b[0m <node> <cmd>  Execute an RCON command against a Paper node (paper-a or paper-b)
  \x1b[1msql\x1b[0m <query>        Execute an SQL query against PostgreSQL and display results
  \x1b[1ms3\x1b[0m <ls|cat|rm> ... Interact with MinIO S3 bucket (ls, cat, rm)
  \x1b[1mtest\x1b[0m [filter]      Run E2E test scenarios matching filter (or all)
  \x1b[1mrun\x1b[0m [filter]       Ephemeral lifecycle: build -> up -> test -> down
  \x1b[1mhelp\x1b[0m, \x1b[1m--help\x1b[0m, \x1b[1m-h\x1b[0m   Show this help message

\x1b[1mEXAMPLES:\x1b[0m
  node e2e/cli.mjs up
  node e2e/cli.mjs status
  node e2e/cli.mjs rcon paper-a "e2e ping"
  node e2e/cli.mjs sql "SELECT * FROM player_world"
  node e2e/cli.mjs s3 ls
  node e2e/cli.mjs s3 cat sample.txt
  node e2e/cli.mjs s3 rm --all
  node e2e/cli.mjs test 01-lobby
  node e2e/cli.mjs run
`);
}

async function main() {
  const args = process.argv.slice(2);
  const command = args[0];

  if (!command || command === '--help' || command === '-h' || command === 'help') {
    helpCommand();
    process.exit(0);
  }

  switch (command) {
    case 'up':
      await upCommand();
      break;
    case 'down':
      await downCommand();
      break;
    case 'build':
      await buildCommand();
      break;
    case 'deploy':
      await deployCommand();
      break;
    case 'reset':
      await resetCommand();
      break;
    case 'status':
      await statusCommand();
      break;
    case 'logs':
      await logsCommand(args[1]);
      break;
    case 'rcon':
      await rconCommand(args[1], ...args.slice(2));
      break;
    case 'sql':
      await sqlCommand(args.slice(1).join(' '));
      break;
    case 's3':
      await s3Command(args[1], ...args.slice(2));
      break;
    case 'test': {
      const ok = await runTests({ filter: args[1] });
      process.exit(ok ? 0 : 1);
      break;
    }
    case 'run':
      await runWorkflow(args[1]);
      break;
    default:
      console.error(`\x1b[31mUnknown command: ${command}\x1b[0m`);
      helpCommand();
      process.exit(1);
  }
}

main().catch((err) => {
  console.error(`\n\x1b[31mFatal error:\x1b[0m`, err.message || err);
  process.exit(1);
});
