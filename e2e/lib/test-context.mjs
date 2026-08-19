import { BotSession } from './bot-session.mjs';
import { DbClient } from './db-client.mjs';
import { S3Helper } from './s3-client.mjs';
import { sendRcon } from './rcon-client.mjs';

export class TestContext {
  constructor(options = {}) {
    this.options = options;
    this.bots = [];
    this.db = new DbClient(options.db);
    this.s3 = new S3Helper(options.s3);
  }

  async spawnBot(username, options = {}) {
    const timeoutMs = options.timeoutMs != null ? options.timeoutMs : 60000;
    const bot = new BotSession(username, { ...this.options.bot, ...options });
    await bot.connect(timeoutMs);
    this.bots.push(bot);
    return bot;
  }

  async rcon(nodeName, command, options = {}) {
    return await sendRcon(nodeName, command, options);
  }

  async resetState() {
    await this.db.truncateTables();
    await this.s3.clearBucket();
  }

  async cleanup() {
    for (const bot of this.bots) {
      try {
        bot.disconnect();
      } catch {
        // ignore errors during bot disconnect
      }
    }
    this.bots = [];

    try {
      await this.db.close();
    } catch {
      // ignore errors closing db pool
    }
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

export default TestContext;
