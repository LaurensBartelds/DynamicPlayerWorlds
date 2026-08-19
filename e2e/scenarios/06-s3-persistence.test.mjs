import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withTestContext } from '../lib/test-context.mjs';

/**
 * Scenario 06: S3 Object Storage & Snapshot Persistence
 *
 * - Checks MinIO S3 bucket health via ctx.s3.checkHealth().
 * - Uploads a test world archive payload to the bucket via ctx.s3.putObject().
 * - Verifies listObjects() contains the key and matches metadata.
 * - Reads back the object via ctx.s3.getObject() and asserts content matches.
 * - Deletes the object via ctx.s3.deleteObject() and verifies cleanup.
 */
export async function run(ctx) {
  console.log('  [06-s3-persistence] Checking MinIO S3 bucket health...');
  const isHealthy = await ctx.s3.checkHealth();
  assert.ok(isHealthy, 'MinIO S3 bucket healthcheck must return true');
  console.log('  [06-s3-persistence] MinIO S3 healthcheck passed.');

  const timestamp = Date.now();
  const testKey = `test-worlds/snapshot-${timestamp}.tar.gz`;
  const payloadContent = `GZMN-TEST-WORLD-ARCHIVE-SNAPSHOT-PAYLOAD-${timestamp}`;
  const payloadBuffer = Buffer.from(payloadContent, 'utf8');

  // 1. Upload test archive object
  console.log(`  [06-s3-persistence] Uploading snapshot archive to S3 key: '${testKey}'...`);
  await ctx.s3.putObject(testKey, payloadBuffer);
  console.log('  [06-s3-persistence] Upload complete.');

  // 2. List objects and assert key is present
  console.log('  [06-s3-persistence] Listing objects with prefix "test-worlds/"...');
  const objects = await ctx.s3.listObjects('test-worlds/');
  console.log(`  [06-s3-persistence] Found ${objects.length} object(s) with prefix 'test-worlds/'.`);

  const foundObject = objects.find((obj) => obj.Key === testKey);
  assert.ok(foundObject, `Key '${testKey}' must be listed in bucket contents`);
  assert.strictEqual(
    foundObject.Size,
    payloadBuffer.length,
    `Object size should be ${payloadBuffer.length}, got ${foundObject.Size}`
  );

  // 3. Read back object and assert content
  console.log(`  [06-s3-persistence] Fetching object '${testKey}'...`);
  const fetchedData = await ctx.s3.getObject(testKey);
  const fetchedString = typeof fetchedData === 'string' ? fetchedData : fetchedData.toString('utf8');
  assert.strictEqual(
    fetchedString,
    payloadContent,
    `Fetched content '${fetchedString}' must match original '${payloadContent}'`
  );
  console.log('  [06-s3-persistence] Verified content integrity match.');

  // 4. Delete object and assert cleanup
  console.log(`  [06-s3-persistence] Deleting test object '${testKey}'...`);
  await ctx.s3.deleteObject(testKey);

  const remainingObjects = await ctx.s3.listObjects('test-worlds/');
  const stillExists = remainingObjects.some((obj) => obj.Key === testKey);
  assert.ok(!stillExists, `Key '${testKey}' must no longer exist after deletion`);
  console.log('  [06-s3-persistence] Verified cleanup.');

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
