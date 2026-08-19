import net from 'node:net';
import { config } from './config.mjs';

const SERVERDATA_AUTH = 3;
const SERVERDATA_EXECCOMMAND = 2;
const SERVERDATA_AUTH_RESPONSE = 2;
const SERVERDATA_RESPONSE_VALUE = 0;

export async function sendRcon(nodeName, command, options = {}) {
  let port = config.rconAPort;
  if (typeof nodeName === 'number') {
    port = nodeName;
  } else if (nodeName === 'paper-b' || nodeName === 'b') {
    port = config.rconBPort;
  } else if (nodeName === 'paper-a' || nodeName === 'a') {
    port = config.rconAPort;
  } else if (typeof nodeName === 'string' && !isNaN(Number(nodeName))) {
    port = Number(nodeName);
  }

  const host = options.host || config.host;
  const password = options.password || config.rconPassword;
  const timeoutMs = options.timeoutMs || 10000;

  return new Promise((resolve, reject) => {
    let resolved = false;
    const finish = (err, result) => {
      if (resolved) return;
      resolved = true;
      try {
        socket.destroy();
      } catch {
        // ignore
      }
      if (err) {
        reject(err);
      } else {
        resolve(result);
      }
    };

    const socket = net.createConnection({ host, port }, () => {
      let authed = false;
      let reqId = 1;
      let buffer = Buffer.alloc(0);

      function sendPacket(id, type, body) {
        const bodyBuf = Buffer.from(body, 'utf8');
        const length = 14 + bodyBuf.length;
        const buf = Buffer.alloc(length);
        buf.writeInt32LE(length - 4, 0);
        buf.writeInt32LE(id, 4);
        buf.writeInt32LE(type, 8);
        bodyBuf.copy(buf, 12);
        buf.writeInt16LE(0, 12 + bodyBuf.length);
        socket.write(buf);
      }

      sendPacket(reqId, SERVERDATA_AUTH, password);

      socket.on('data', (chunk) => {
        buffer = Buffer.concat([buffer, chunk]);

        while (buffer.length >= 4) {
          const length = buffer.readInt32LE(0);
          const totalPacketSize = 4 + length;
          if (buffer.length < totalPacketSize) {
            break;
          }

          const packetData = buffer.subarray(4, totalPacketSize);
          buffer = buffer.subarray(totalPacketSize);

          if (packetData.length < 8) continue;
          const id = packetData.readInt32LE(0);
          const type = packetData.readInt32LE(4);
          const body = packetData.subarray(8, packetData.length >= 10 ? packetData.length - 2 : 8).toString('utf8');

          if (!authed) {
            if (type === SERVERDATA_AUTH_RESPONSE) {
              if (id === -1) {
                return finish(new Error('RCON authentication failed'));
              }
              authed = true;
              reqId++;
              sendPacket(reqId, SERVERDATA_EXECCOMMAND, command);
            }
          } else {
            if (type === SERVERDATA_RESPONSE_VALUE) {
              return finish(null, body);
            }
          }
        }
      });
    });

    socket.setTimeout(timeoutMs, () => {
      finish(new Error(`RCON timeout on ${nodeName}:${port}`));
    });

    socket.on('error', (err) => {
      finish(err);
    });
  });
}

export default sendRcon;
