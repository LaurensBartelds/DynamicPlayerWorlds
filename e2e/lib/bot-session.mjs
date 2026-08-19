import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { config } from './config.mjs';

const require = createRequire(import.meta.url);

export function getOfflineUuid(username) {
  const hash = crypto.createHash('md5').update(`OfflinePlayer:${username}`, 'utf8').digest();
  hash[6] = (hash[6] & 0x0f) | 0x30;
  hash[8] = (hash[8] & 0x3f) | 0x80;
  const hex = hash.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

// Patch mineflayer version definitions so paper 26.x/adjacent schemas can initialize cleanly
const mfVersion = require('mineflayer/lib/version');
if (mfVersion && mfVersion.testedVersions && !mfVersion.testedVersions.includes('26.1')) {
  mfVersion.testedVersions.push('26.1', '26.2');
  mfVersion.latestSupportedVersion = '26.1';
}

const mineflayer = require('mineflayer');

export class BotSession {
  static uuidRegistry = new Map();

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
    this.tablistPlayers = new Set([this.username]);

    BotSession.uuidRegistry.set(getOfflineUuid(this.username), this.username);
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

      this.bot._client.prependListener('update_time', (packet) => {
        if (packet) {
          if (typeof packet.age === 'bigint') {
            const high = Number(packet.age >> 32n);
            const low = Number(packet.age & 0xFFFFFFFFn);
            packet.age = [high, low];
          } else if (!Array.isArray(packet.age)) {
            packet.age = [0, 0];
          }
          if (typeof packet.time === 'bigint') {
            const high = Number(packet.time >> 32n);
            const low = Number(packet.time & 0xFFFFFFFFn);
            packet.time = [high, low];
          } else if (!Array.isArray(packet.time)) {
            const ticks = (packet.clockUpdates && packet.clockUpdates[0] && packet.clockUpdates[0].totalTicks) || 0;
            packet.time = [0, Number(ticks)];
          }
        }
      });

      this.bot._client.on('player_info', (packet) => {
        if (packet && Array.isArray(packet.data)) {
          for (const item of packet.data) {
            let name = (item.player && item.player.name) || null;
            if (!name && item.uuid) {
              name = BotSession.uuidRegistry.get(item.uuid);
            }
            if (name) {
              this.tablistPlayers.add(name);
              if (this.bot.players) {
                this.bot.players[name] = { username: name, uuid: item.uuid };
              }
            }
          }
        }
      });

      this.bot._client.on('player_remove', (packet) => {
        if (packet && Array.isArray(packet.players)) {
          for (const uuid of packet.players) {
            const name = BotSession.uuidRegistry.get(uuid);
            if (name) {
              this.tablistPlayers.delete(name);
              if (this.bot.players) {
                delete this.bot.players[name];
              }
            }
          }
        }
      });
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
      this.tablistPlayers.add(this.username);
    });

    this.bot.on('end', () => {
      this.connected = false;
    });
  }

  async connect(timeoutMs = 60000, maxRetries = 3) {
    for (let attempt = 1; attempt <= maxRetries; attempt++) {
      if (!this.bot) {
        this._initBot();
      }

      try {
        await new Promise((resolve, reject) => {
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

        return this;
      } catch (err) {
        const isTransient =
          err.message?.includes('already connected') ||
          err.message?.includes('duplicate_login') ||
          err.message?.includes('disconnected before spawn') ||
          // Velocity's wording when a previous session for this username has not
          // been released yet. It arrives as a kick during login.
          err.message?.includes('already connected to this proxy') ||
          err.message?.includes('timed out');
        if (attempt < maxRetries && isTransient) {
          this.disconnect();
          await new Promise((r) => setTimeout(r, 600 * attempt));
          continue;
        }
        throw err;
      }
    }
    return this;
  }

  runCommand(command) {
    if (!this.bot) {
      throw new Error(`Bot '${this.username}' is not initialized`);
    }
    const cleanCmd = command.startsWith('/') ? command.slice(1) : command;
    if (this.bot._client) {
      try {
        this.bot._client.write('chat_command', { command: cleanCmd });
        return;
      } catch {
        // Fallback to bot.chat
      }
    }
    this.bot.chat(`/${cleanCmd}`);
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
    const list = new Set(this.tablistPlayers);
    if (this.bot && this.bot.players) {
      for (const name of Object.keys(this.bot.players)) {
        list.add(name);
      }
    }
    return Array.from(list);
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
      // Dropped, not kept. connect() only builds a new mineflayer bot when this
      // is null, and a disconnected bot never emits 'spawn' again — so holding
      // on to it made every retry wait out the full spawn timeout against a dead
      // socket instead of reconnecting. That turned one duplicate-session
      // rejection into a cascade of 60s failures across the whole suite.
      this.bot = null;
    }
  }
}

export default BotSession;
