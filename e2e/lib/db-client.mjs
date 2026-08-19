import pg from 'pg';
import { config } from './config.mjs';

const { Pool } = pg;

export class DbClient {
  constructor(options = {}) {
    this.pool = new Pool({
      host: options.host || config.db.host,
      port: options.port || config.db.port,
      user: options.user || config.db.user,
      password: options.password || config.db.password,
      database: options.database || config.db.database,
      connectionTimeoutMillis: options.connectionTimeoutMillis || 5000,
    });
  }

  async query(text, params = []) {
    const res = await this.pool.query(text, params);
    return res.rows;
  }

  async checkHealth() {
    try {
      const res = await this.query('SELECT 1 AS ok');
      return res.length > 0 && res[0].ok === 1;
    } catch {
      return false;
    }
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

export default DbClient;
