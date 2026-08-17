#!/usr/bin/env node
/**
 * Offline client for the e2e compose harness (F11).
 *
 * Connects through Velocity to the lobby Paper node and exits 0 once the
 * server places the player in the world (login + position).
 *
 * Protocol note: node-minecraft-protocol / minecraft-data currently ship
 * packet schemas through Minecraft 26.1 (protocol 775), while the repo pins
 * Paper 26.2 (protocol 776). The two lines are adjacent; advertising 776 on
 * the handshake while decoding with 26.1 schemas is enough for a join smoke.
 * When minecraft-data grows a real 26.2 pin, set E2E_MC_VERSION=26.2 and
 * E2E_PROTOCOL_VERSION=776 (or drop the spoof).
 */

import mc from 'minecraft-protocol'

const host = process.env.E2E_HOST || '127.0.0.1'
const port = Number(process.env.E2E_PORT || 25565)
const username = process.env.E2E_USER || 'E2EPlayer'
// Packet-schema base that minecraft-data actually ships.
const schemaVersion = process.env.E2E_MC_VERSION || '26.1'
// Protocol id advertised on the handshake (Paper 26.2 = 776).
const protocolVersion = Number(process.env.E2E_PROTOCOL_VERSION || 776)
const timeoutMs = Number(process.env.E2E_TIMEOUT_MS || 180000)

console.log(
  `e2e bot connecting host=${host} port=${port} user=${username} schema=${schemaVersion} protocol=${protocolVersion} timeoutMs=${timeoutMs}`,
)

let settled = false
/** @type {import('minecraft-protocol').Client | undefined} */
let client

const finish = (code, reason) => {
  if (settled) return
  settled = true
  clearTimeout(timer)
  console.log(reason)
  try {
    client?.end('e2e done')
  } catch {
    // ignore
  }
  setTimeout(() => process.exit(code), 400)
}

const timer = setTimeout(() => {
  finish(4, `e2e bot timed out after ${timeoutMs}ms waiting for spawn`)
}, timeoutMs)

try {
  client = mc.createClient({
    host,
    port,
    username,
    auth: 'offline',
    version: schemaVersion,
    // 26.1 schemas vs 26.2 wire can emit length warnings; still joinable.
    hideErrors: true,
  })
} catch (err) {
  finish(1, `e2e bot failed to create client: ${err && err.stack ? err.stack : err}`)
}

if (!client) {
  finish(1, 'e2e bot client missing')
} else {
  // Advertise the Paper protocol id while keeping 26.1 packet schemas.
  const origWrite = client.write.bind(client)
  client.write = (name, params) => {
    if (
      (name === 'set_protocol' || name === 'handshake') &&
      params &&
      typeof params === 'object' &&
      params.protocolVersion != null
    ) {
      params.protocolVersion = protocolVersion
    }
    return origWrite(name, params)
  }

  client.on('connect', () => {
    console.log('e2e bot tcp connect ok')
  })

  client.on('login', () => {
    console.log('e2e bot login ok')
  })

  // First absolute position from the server means we are in the world.
  client.on('position', () => {
    finish(0, 'e2e bot join ok (position)')
  })

  client.on('disconnect', (packet) => {
    const reason =
      typeof packet === 'string'
        ? packet
        : packet?.reason
          ? JSON.stringify(packet.reason)
          : JSON.stringify(packet)
    finish(2, `e2e bot disconnected: ${reason}`)
  })

  client.on('error', (err) => {
    // Non-fatal decoder noise can surface here under the schema spoof; only
    // fail if we never spawned.
    console.log(`e2e bot error: ${err && err.message ? err.message : err}`)
  })

  client.on('end', (reason) => {
    if (!settled) {
      finish(5, `e2e bot connection ended before spawn: ${reason || 'unknown'}`)
    }
  })
}
