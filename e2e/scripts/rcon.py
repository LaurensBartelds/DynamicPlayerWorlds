#!/usr/bin/env python3
"""Minimal Source RCON client for the e2e harness (no third-party deps)."""

from __future__ import annotations

import argparse
import socket
import struct
import sys

SERVERDATA_AUTH = 3
SERVERDATA_AUTH_RESPONSE = 2
SERVERDATA_EXECCOMMAND = 2
SERVERDATA_RESPONSE_VALUE = 0


def _send(sock: socket.socket, req_id: int, req_type: int, body: str) -> None:
    payload = struct.pack("<ii", req_id, req_type) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(payload)) + payload)


def _recv_packet(sock: socket.socket) -> tuple[int, int, str]:
    length_raw = _recv_exact(sock, 4)
    (length,) = struct.unpack("<i", length_raw)
    data = _recv_exact(sock, length)
    req_id, req_type = struct.unpack("<ii", data[:8])
    body = data[8:-2].decode("utf-8", errors="replace")
    return req_id, req_type, body


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("rcon connection closed")
        buf.extend(chunk)
    return bytes(buf)


def rcon(host: str, port: int, password: str, command: str, timeout: float = 10.0) -> str:
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        _send(sock, 1, SERVERDATA_AUTH, password)
        # Some servers send an empty RESPONSE_VALUE before AUTH_RESPONSE.
        while True:
            req_id, req_type, _body = _recv_packet(sock)
            if req_type == SERVERDATA_AUTH_RESPONSE:
                if req_id == -1:
                    raise PermissionError("rcon auth failed")
                break
        _send(sock, 2, SERVERDATA_EXECCOMMAND, command)
        # Read until we get the command response (id=2). A trailing empty
        # RESPONSE_VALUE may follow; we only need the first body.
        bodies: list[str] = []
        while True:
            req_id, req_type, body = _recv_packet(sock)
            if req_id == 2 and req_type == SERVERDATA_RESPONSE_VALUE:
                bodies.append(body)
                # Request a second empty packet to flush multi-packet replies.
                _send(sock, 3, SERVERDATA_RESPONSE_VALUE, "")
                # Drain until id=3 terminator pattern or timeout.
                try:
                    while True:
                        rid, rtype, b = _recv_packet(sock)
                        if rid == 3:
                            break
                        if rid == 2 and rtype == SERVERDATA_RESPONSE_VALUE and b:
                            bodies.append(b)
                except (TimeoutError, socket.timeout):
                    pass
                break
        return "".join(bodies).strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", required=True)
    parser.add_argument("command")
    args = parser.parse_args()
    try:
        out = rcon(args.host, args.port, args.password, args.command)
    except Exception as exc:  # noqa: BLE001 — CLI surface
        print(f"rcon error: {exc}", file=sys.stderr)
        return 1
    if out:
        print(out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
