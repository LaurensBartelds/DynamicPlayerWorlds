import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withTestContext } from '../lib/test-context.mjs';

/**
 * Scenario 06: S3 Object Storage & Snapshot Persistence
 *
 * - Checks MinIO S3 bucket health.
 * - Creates a player world through Velocity.
 * - Waits for the initial commit to publish a non-empty snapshot manifest.
 * - Asserts data blobs and the manifest exist under worlds/<id>/ in MinIO.
 * - Sanity-checks put/get/delete against a throwaway key still works.
 */
export async function run(ctx) {
  console.log('  [06-s3-persistence] Checking MinIO S3 bucket health...');
  const isHealthy = await ctx.s3.checkHealth();
  assert.ok(isHealthy, 'MinIO S3 bucket healthcheck must return true');
  console.log('  [06-s3-persistence] MinIO S3 healthcheck passed.');

  const worldName = 's3persist';
  let alice = await ctx.spawnBot('Alice');
  console.log(`  [06-s3-persistence] Alice creating /world create ${worldName}...`);
  alice.runCommand(`/world create ${worldName}`);

  let world = null;
  const start = Date.now();
  while (Date.now() - start < 45000) {
    const rows = await ctx.db.query(
      'SELECT id, name, state, manifest_key, storage_bytes FROM player_world WHERE name = $1',
      [worldName]
    );
    if (rows.length) {
      world = rows[0];
      if (world.state === 'READY' && world.manifest_key && Number(world.storage_bytes || 0) > 0) {
        break;
      }
    }
    await new Promise((r) => setTimeout(r, 1000));
  }

  assert.ok(world, `Expected player_world row for '${worldName}'`);
  assert.strictEqual(world.state, 'READY', `World must become READY, got ${world.state}`);
  assert.ok(world.manifest_key, 'Initial commit must set player_world.manifest_key');
  assert.ok(
    Number(world.storage_bytes || 0) > 0,
    `Initial commit must set storage_bytes > 0 (got ${world.storage_bytes}); empty manifests mean DirtyScanner missed Paper 26 paths`
  );
  console.log(
    `  [06-s3-persistence] Commit ready: manifest=${world.manifest_key} bytes=${world.storage_bytes}`
  );

  const prefix = `worlds/${world.id}/`;
  const objects = await ctx.s3.listObjects(prefix);
  console.log(`  [06-s3-persistence] Found ${objects.length} S3 object(s) under ${prefix}`);
  assert.ok(objects.length > 0, `Expected S3 objects under ${prefix}`);

  const keys = objects.map((o) => o.Key || o.key || String(o));
  assert.ok(
    keys.some((k) => k.includes('/manifest/')),
    'Expected a snapshot manifest object in S3'
  );
  assert.ok(
    keys.some((k) => k.includes('/data/')),
    'Expected content-addressed data blobs in S3'
  );

  const manifestBody = await ctx.s3.getObject(world.manifest_key);
  const manifestJson = JSON.parse(
    typeof manifestBody === 'string' ? manifestBody : manifestBody.toString('utf8')
  );
  const entryCount = Object.keys(manifestJson.entries || {}).length;
  assert.ok(entryCount > 0, `Manifest must list dirty world files, got ${entryCount} entries`);
  console.log(`  [06-s3-persistence] Manifest lists ${entryCount} world file(s).`);

  // Keep a lightweight put/get/delete sanity check for the e2e S3 helper itself.
  const testKey = `test-worlds/helper-${Date.now()}.txt`;
  const payload = `gzmn-s3-helper-${Date.now()}`;
  await ctx.s3.putObject(testKey, Buffer.from(payload, 'utf8'));
  const fetched = await ctx.s3.getObject(testKey);
  const fetchedString = typeof fetched === 'string' ? fetched : fetched.toString('utf8');
  assert.strictEqual(fetchedString, payload);
  await ctx.s3.deleteObject(testKey);

  console.log('  [06-s3-persistence] Scenario 06 completed successfully.');
}

export default run;

const isDirect = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isDirect) {
  withTestContext(run)
    .then(() => {
      console.log('\x1b[32m06-s3-persistence passed.\x1b[0m');
      process.exit(0);
    })
    .catch((err) => {
      console.error('\x1b[31m06-s3-persistence failed:\x1b[0m', err);
      process.exit(1);
    });
}
