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

  /**
   * Blocks until both backend nodes report no players, bounded.
   *
   * <p>Returns false on timeout rather than throwing: a slow teardown should
   * show up as the next scenario's failure with its own message, not as an
   * error attributed to the scenario that just passed.
   */
  async waitForNoPlayers(timeoutMs = 20000) {
    const started = Date.now();
    while (Date.now() - started < timeoutMs) {
      try {
        const [a, b] = await Promise.all([sendRcon('paper-a', 'e2e status'), sendRcon('paper-b', 'e2e status')]);
        if (/online=0/.test(a) && /online=0/.test(b)) {
          // The backends have dropped them; give Velocity a moment to release
          // its own session registry, which is what rejects a duplicate login.
          await new Promise((r) => setTimeout(r, 400));
          return true;
        }
      } catch {
        // A node busy enough to refuse RCON is also a reason to keep waiting.
      }
      await new Promise((r) => setTimeout(r, 250));
    }
    return false;
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

    // Wait for the sessions to actually be gone rather than guessing at 500ms.
    // Velocity rejects a second login for a username it still has registered
    // ("You are already connected to this proxy!"), so a scenario that ended
    // slightly slowly used to poison every scenario after it.
    await this.waitForNoPlayers();

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
    await ctx.resetState();
    await testFn(ctx);
  } finally {
    await ctx.cleanup();
  }
}

export default TestContext;
