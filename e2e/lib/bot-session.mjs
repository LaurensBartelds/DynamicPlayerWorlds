import { createRequire } from 'node:module';
import { config } from './config.mjs';

const require = createRequire(import.meta.url);

// Patch mineflayer version definitions so paper 26.x/adjacent schemas can initialize cleanly
const mfVersion = require('mineflayer/lib/version');
if (mfVersion && mfVersion.testedVersions && !mfVersion.testedVersions.includes('26.1')) {
  mfVersion.testedVersions.push('26.1', '26.2');
  mfVersion.latestSupportedVersion = '26.1';
}

const mineflayer = require('mineflayer');

export class BotSession {
  constructor(usernameOrOptions = {}, maybeOptions = {}) {
    let options = {};
    let username = 'E2EPlayer';

    if (typeof usernameOrOptions === 'string') {
      username = usernameOrOptions;
      options = maybeOptions || {};
    } else if (typeof usernameOrOptions === 'object' && usernameOrOptions !== null) {
      options = usernameOrOptions;
      username = options.username || 'E2EPlayer';
    }

    this.username = username;
    this.host = options.host || config.host || '127.0.0.1';
    this.port = options.port != null ? options.port : (config.proxyPort || 25565);
    this.schemaVersion = options.schemaVersion || options.version || config.mcVersion || '26.1';
    this.protocolVersion = options.protocolVersion != null ? options.protocolVersion : (config.protocolVersion || 776);
    this.options = options;

    this.chatLog = [];
    this.bot = null;
    this.connected = false;
    this._spawned = false;
  }

  _initBot() {
    if (this.bot) return;

    this.bot = mineflayer.createBot({
      host: this.host,
      port: this.port,
      username: this.username,
      auth: 'offline',
      version: this.schemaVersion,
      hideErrors: true,
      ...(this.options.mineflayerOptions || {}),
    });

    if (this.bot._client) {
      const origWrite = this.bot._client.write.bind(this.bot._client);
      this.bot._client.write = (name, params) => {
        if ((name === 'set_protocol' || name === 'handshake') && params && params.protocolVersion != null) {
          params.protocolVersion = this.protocolVersion;
        }
        return origWrite(name, params);
      };
    }

    this.bot.on('message', (jsonMsg) => {
      let text = '';
      if (typeof jsonMsg === 'string') {
        text = jsonMsg;
      } else if (jsonMsg && typeof jsonMsg.toString === 'function') {
        text = jsonMsg.toString();
      } else {
        text = String(jsonMsg);
      }
      this.chatLog.push(text);
    });

    this.bot.on('spawn', () => {
      this._spawned = true;
      this.connected = true;
    });

    this.bot.on('end', () => {
      this.connected = false;
    });
  }

  connect(timeoutMs = 60000) {
    if (!this.bot) {
      this._initBot();
    }

    return new Promise((resolve, reject) => {
      if (this._spawned) {
        return resolve(this);
      }

      let timer = null;

      const onSpawn = () => {
        cleanup();
        this._spawned = true;
        this.connected = true;
        resolve(this);
      };

      const onError = (err) => {
        if (!this._spawned) {
          cleanup();
          reject(err || new Error(`Bot '${this.username}' encountered error before spawn`));
        }
      };

      const onEnd = (reason) => {
        if (!this._spawned) {
          cleanup();
          reject(new Error(`Bot '${this.username}' disconnected before spawn: ${reason || 'unknown'}`));
        }
      };

      const onKicked = (reason) => {
        if (!this._spawned) {
          cleanup();
          const reasonStr = typeof reason === 'object' ? JSON.stringify(reason) : String(reason);
          reject(new Error(`Bot '${this.username}' was kicked before spawn: ${reasonStr}`));
        }
      };

      const cleanup = () => {
        if (timer) {
          clearTimeout(timer);
          timer = null;
        }
        if (this.bot) {
          this.bot.removeListener('spawn', onSpawn);
          this.bot.removeListener('error', onError);
          this.bot.removeListener('end', onEnd);
          this.bot.removeListener('kicked', onKicked);
        }
      };

      if (timeoutMs > 0) {
        timer = setTimeout(() => {
          cleanup();
          reject(new Error(`Bot '${this.username}' timed out after ${timeoutMs}ms waiting for spawn`));
        }, timeoutMs);
      }

      this.bot.once('spawn', onSpawn);
      this.bot.once('error', onError);
      this.bot.once('end', onEnd);
      this.bot.once('kicked', onKicked);
    });
  }

  runCommand(command) {
    if (!this.bot) {
      throw new Error(`Bot '${this.username}' is not initialized`);
    }
    const cmd = command.startsWith('/') ? command : `/${command}`;
    this.bot.chat(cmd);
  }

  async waitForChat(pattern, timeoutMs = 15000, startIndex = 0) {
    const start = Date.now();
    const check = () => {
      const startIdx = typeof startIndex === 'number' && startIndex >= 0 ? startIndex : 0;
      for (let i = startIdx; i < this.chatLog.length; i++) {
        const msg = this.chatLog[i];
        if (pattern instanceof RegExp) {
          if (pattern.test(msg)) return msg;
        } else if (typeof pattern === 'string') {
          if (msg.includes(pattern)) return msg;
        }
      }
      return null;
    };

    const initial = check();
    if (initial !== null) return initial;

    while (Date.now() - start < timeoutMs) {
      await new Promise((resolve) => setTimeout(resolve, 50));
      const match = check();
      if (match !== null) return match;
    }

    throw new Error(
      `Timeout after ${timeoutMs}ms waiting for chat pattern '${pattern}'. Recent chat messages: [${this.chatLog.slice(-5).join(' | ')}]`
    );
  }

  getTabListPlayers() {
    if (!this.bot || !this.bot.players) {
      return [];
    }
    return Object.keys(this.bot.players);
  }

  async assertPlayerVisible(targetUsername, timeoutMs = 10000) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      const players = this.getTabListPlayers();
      if (players.includes(targetUsername)) {
        return true;
      }
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    const current = this.getTabListPlayers();
    throw new Error(
      `Player '${targetUsername}' was not visible in tab list within ${timeoutMs}ms. Current tablist: [${current.join(', ')}]`
    );
  }

  async assertPlayerHidden(targetUsername, timeoutMs = 5000) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      const players = this.getTabListPlayers();
      if (!players.includes(targetUsername)) {
        return true;
      }
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    const current = this.getTabListPlayers();
    if (current.includes(targetUsername)) {
      throw new Error(`Player '${targetUsername}' is still visible in tab list after ${timeoutMs}ms`);
    }
    return true;
  }

  getInventoryItems() {
    if (!this.bot || !this.bot.inventory) {
      return [];
    }
    return (typeof this.bot.inventory.items === 'function' ? this.bot.inventory.items() : []) || [];
  }

  async waitForInventoryItem(itemName, count = 1, timeoutMs = 10000) {
    const start = Date.now();
    const cleanTarget = String(itemName).toLowerCase().replace(/^minecraft:/, '');

    const check = () => {
      const items = this.getInventoryItems();
      let totalCount = 0;
      for (const item of items) {
        const itemCanonical = (item.name || '').toLowerCase().replace(/^minecraft:/, '');
        const itemDisplay = (item.displayName || '').toLowerCase();
        if (itemCanonical === cleanTarget || itemDisplay === cleanTarget || itemCanonical.includes(cleanTarget)) {
          totalCount += (item.count != null ? item.count : 1);
        }
      }
      return totalCount >= count ? items : null;
    };

    const initial = check();
    if (initial) return initial;

    while (Date.now() - start < timeoutMs) {
      await new Promise((resolve) => setTimeout(resolve, 50));
      const res = check();
      if (res) return res;
    }

    const currentItems = this.getInventoryItems().map((i) => `${i.name || 'item'}x${i.count || 1}`).join(', ');
    throw new Error(
      `Timeout after ${timeoutMs}ms waiting for inventory item '${itemName}' (count >= ${count}). Current items: [${currentItems}]`
    );
  }

  async clickWindowSlot(slot, mouseButton = 0, mode = 0) {
    if (!this.bot) {
      throw new Error(`Bot '${this.username}' is not initialized`);
    }
    if (typeof this.bot.clickWindow === 'function') {
      return await this.bot.clickWindow(slot, mouseButton, mode);
    }
    throw new Error('bot.clickWindow is not available');
  }

  disconnect() {
    if (this.bot) {
      try {
        if (typeof this.bot.quit === 'function') {
          this.bot.quit();
        } else if (typeof this.bot.end === 'function') {
          this.bot.end();
        } else if (this.bot._client && typeof this.bot._client.end === 'function') {
          this.bot._client.end();
        }
      } catch {
        // ignore
      }
      this.connected = false;
      this._spawned = false;
    }
  }
}

export default BotSession;
