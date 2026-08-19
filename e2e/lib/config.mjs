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
  rconPassword: process.env.E2E_RCON_PASSWORD || 'e2e-rcon-not-for-prod',
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

export default config;
