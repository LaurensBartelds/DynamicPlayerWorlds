import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { E2E_ROOT } from './config.mjs';
import { withTestContext } from './test-context.mjs';

const __filename = fileURLToPath(import.meta.url);

function findTestFiles(dir) {
  if (!fs.existsSync(dir)) {
    return [];
  }
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  let files = [];
  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files = files.concat(findTestFiles(fullPath));
    } else if (entry.isFile() && (entry.name.endsWith('.test.mjs') || entry.name.endsWith('.test.js'))) {
      files.push(fullPath);
    }
  }
  return files.sort();
}

export async function runTests(options = {}) {
  const scenariosDir = path.join(E2E_ROOT, 'scenarios');
  if (!fs.existsSync(scenariosDir)) {
    fs.mkdirSync(scenariosDir, { recursive: true });
  }

  let testFiles = findTestFiles(scenariosDir);
  const filter = options.filter || (process.argv[2] && !process.argv[2].startsWith('-') ? process.argv[2] : null);

  if (filter) {
    testFiles = testFiles.filter((f) => {
      const base = path.basename(f);
      const rel = path.relative(scenariosDir, f).replace(/\\/g, '/');
      return base.includes(filter) || rel.includes(filter);
    });
  }

  if (testFiles.length === 0) {
    if (filter) {
      console.log(`\x1b[33mNo test scenarios matched filter: "${filter}" in ${scenariosDir}\x1b[0m`);
    } else {
      console.log(`\x1b[33mNo test scenarios found (*.test.mjs) in ${scenariosDir}\x1b[0m`);
    }
    return true;
  }

  console.log(`\n\x1b[1m\x1b[34m=======================================================\x1b[0m`);
  console.log(`\x1b[1m Running E2E Scenarios (${testFiles.length} scenario${testFiles.length === 1 ? '' : 's'})\x1b[0m`);
  console.log(`\x1b[1m\x1b[34m=======================================================\x1b[0m\n`);

  let passed = 0;
  let failed = 0;
  const suiteStartTime = Date.now();

  for (const filePath of testFiles) {
    const relPath = path.relative(E2E_ROOT, filePath).replace(/\\/g, '/');
    console.log(`\x1b[36m[RUNS]\x1b[0m ${relPath}`);
    const testStartTime = Date.now();

    try {
      const fileUrl = pathToFileURL(filePath).href;
      const mod = await import(`${fileUrl}?t=${Date.now()}`);

      if (typeof mod.default === 'function') {
        if (mod.default.length > 0) {
          await withTestContext(async (ctx) => {
            await mod.default(ctx);
          });
        } else {
          await mod.default();
        }
      } else if (typeof mod.run === 'function') {
        if (mod.run.length > 0) {
          await withTestContext(async (ctx) => {
            await mod.run(ctx);
          });
        } else {
          await mod.run();
        }
      }

      const durationMs = Date.now() - testStartTime;
      console.log(`  \x1b[32m✓ PASSED\x1b[0m \x1b[1m${relPath}\x1b[0m \x1b[90m(${durationMs}ms)\x1b[0m\n`);
      passed++;
    } catch (err) {
      const durationMs = Date.now() - testStartTime;
      console.log(`  \x1b[31m✗ FAILED\x1b[0m \x1b[1m${relPath}\x1b[0m \x1b[90m(${durationMs}ms)\x1b[0m`);
      console.error(`    \x1b[31m${err && err.stack ? err.stack : err}\x1b[0m\n`);
      failed++;
    }
  }

  const totalDuration = ((Date.now() - suiteStartTime) / 1000).toFixed(2);
  console.log(`\x1b[34m-------------------------------------------------------\x1b[0m`);
  console.log(
    `\x1b[1mResults:\x1b[0m ` +
    (passed > 0 ? `\x1b[32m${passed} passed\x1b[0m, ` : `0 passed, `) +
    (failed > 0 ? `\x1b[31m${failed} failed\x1b[0m, ` : `0 failed, `) +
    `${testFiles.length} total \x1b[90m(${totalDuration}s)\x1b[0m`
  );
  console.log(`\x1b[34m-------------------------------------------------------\x1b[0m\n`);

  return failed === 0;
}

const isDirect = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(__filename);
if (isDirect) {
  runTests()
    .then((success) => {
      process.exit(success ? 0 : 1);
    })
    .catch((err) => {
      console.error('\x1b[31mFatal error running test runner:\x1b[0m', err);
      process.exit(1);
    });
}

export default runTests;
