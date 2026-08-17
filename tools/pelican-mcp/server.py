"""Pelican Panel MCP Server.

Provides Model Context Protocol (MCP) tools for interacting with Pelican Game
Server Panel (Pterodactyl fork) Client APIs, with native multi-server support
for Paper backend and Velocity proxy test servers.
"""

from __future__ import annotations

import base64
import fnmatch
import os
import time
from pathlib import Path
from typing import Any
import requests
from dotenv import load_dotenv

# Load environment variables from .env file if present
load_dotenv()

# Initialize MCP Server (supporting both FastMCP and MCPServer)
try:
    from mcp.server.mcpserver import MCPServer
    mcp = MCPServer("pelican-panel")
except ImportError:
    try:
        from mcp.server.fastmcp import FastMCP
        mcp = FastMCP("pelican-panel")
    except ImportError:
        from fastmcp import FastMCP
        mcp = FastMCP("pelican-panel")


def _get_config(server_target_or_id: str = "") -> tuple[str, str, str]:
    """Retrieve and validate Pelican configuration and resolve server target.

    Supports aliases: 'paper' / 'backend', 'proxy' / 'velocity', or explicit server IDs.
    """
    url = os.getenv("PELICAN_URL", "").strip().rstrip("/")
    api_key = os.getenv("PELICAN_API_KEY", "").strip()

    paper_id = (os.getenv("PAPER_SERVER_ID") or os.getenv("BACKEND_SERVER_ID") or "").strip()
    proxy_id = (os.getenv("PROXY_SERVER_ID") or os.getenv("VELOCITY_SERVER_ID") or "").strip()
    default_id = (os.getenv("DEFAULT_SERVER_ID") or paper_id or proxy_id or "").strip()

    if not url:
        raise ValueError(
            "PELICAN_URL environment variable is not set. "
            "Please set PELICAN_URL (e.g., https://panel.example.com)."
        )
    if not api_key:
        raise ValueError(
            "PELICAN_API_KEY environment variable is not set. "
            "Please generate an API key in your Pelican Account Settings (Account API / Client API)."
        )

    target_clean = (server_target_or_id or "").strip()
    target_lower = target_clean.lower()

    if target_lower in ("paper", "backend"):
        resolved_id = paper_id or default_id
    elif target_lower in ("proxy", "velocity"):
        resolved_id = proxy_id or default_id
    elif target_clean:
        resolved_id = target_clean
    else:
        resolved_id = default_id

    if not resolved_id:
        raise ValueError(
            f"Could not resolve server ID for target '{server_target_or_id}'. "
            "Please set PAPER_SERVER_ID, PROXY_SERVER_ID, or DEFAULT_SERVER_ID in your environment."
        )

    return url, api_key, resolved_id


def _headers(api_key: str) -> dict[str, str]:
    """Return standard headers for Pelican Client API."""
    return {
        "Authorization": f"Bearer {api_key}",
        "Accept": "application/json",
        "Content-Type": "application/json",
    }


def _deploy_single(
    url: str,
    api_key: str,
    server_id: str,
    local_file: Path,
    remote_dir: str = "/plugins",
    cleanup_pattern: str = "",
) -> str:
    """Helper to perform plugin deployment to a single server."""
    if not local_file.is_file():
        return f"Local file not found: '{local_file}'"

    deleted_summary = ""

    # Step 1: Clean up old versions if pattern given
    if cleanup_pattern:
        list_resp = requests.get(
            f"{url}/api/client/servers/{server_id}/files/list",
            headers=_headers(api_key),
            params={"directory": remote_dir},
            timeout=15,
        )
        if list_resp.status_code == 200:
            remote_items = list_resp.json().get("data", [])
            matching_files = [
                item["attributes"]["name"]
                for item in remote_items
                if item.get("attributes", {}).get("is_file", True)
                and fnmatch.fnmatch(item["attributes"]["name"], cleanup_pattern)
            ]
            if matching_files:
                del_resp = requests.post(
                    f"{url}/api/client/servers/{server_id}/files/delete",
                    headers=_headers(api_key),
                    json={"root": remote_dir, "files": matching_files},
                    timeout=15,
                )
                if del_resp.status_code in (200, 204):
                    deleted_summary = f"Removed existing files matching '{cleanup_pattern}': {', '.join(matching_files)}.\n"

    # Step 2: Request signed upload URL
    upload_resp = requests.get(
        f"{url}/api/client/servers/{server_id}/files/upload",
        headers=_headers(api_key),
        timeout=10,
    )
    upload_resp.raise_for_status()
    signed_upload_url = upload_resp.json().get("attributes", {}).get("url")
    if not signed_upload_url:
        return f"{deleted_summary}Failed to obtain signed upload URL from Pelican Panel."

    # Step 3: Stream and upload the file
    file_bytes = local_file.read_bytes()
    files = {"files": (local_file.name, file_bytes)}
    target_dir = remote_dir.lstrip("/")

    post_resp = requests.post(
        signed_upload_url,
        params={"directory": target_dir} if target_dir else {},
        files=files,
        timeout=120,
    )

    if post_resp.status_code in (200, 204):
        return (
            f"{deleted_summary}"
            f"Successfully deployed '{local_file.name}' ({round(len(file_bytes)/1024, 2)} KB) "
            f"to '{remote_dir}' on server {server_id}."
        )
    return f"{deleted_summary}Failed to upload '{local_file.name}' ({post_resp.status_code}): {post_resp.text}"


@mcp.tool()
def pelican_get_server_status(server: str = "paper") -> str:
    """Get operational status, resource usage (CPU, RAM, Disk), and metadata for a server.

    Args:
        server: Target server alias ('paper'/'backend', 'proxy'/'velocity') or direct server ID (default: 'paper').
    """
    try:
        url, api_key, s_id = _get_config(server)

        # 1. Fetch server metadata
        details_resp = requests.get(
            f"{url}/api/client/servers/{s_id}",
            headers=_headers(api_key),
            timeout=10,
        )
        details_resp.raise_for_status()
        details = details_resp.json().get("attributes", {})

        # 2. Fetch server live resource utilization
        resources_resp = requests.get(
            f"{url}/api/client/servers/{s_id}/resources",
            headers=_headers(api_key),
            timeout=10,
        )
        resources_resp.raise_for_status()
        res_attrs = resources_resp.json().get("attributes", {})
        res_usage = res_attrs.get("resources", {})

        current_state = res_attrs.get("current_state", "unknown")
        is_suspended = details.get("is_suspended", False)

        mem_bytes = res_usage.get("memory_bytes", 0)
        mem_mb = round(mem_bytes / (1024 * 1024), 2)
        disk_bytes = res_usage.get("disk_bytes", 0)
        disk_mb = round(disk_bytes / (1024 * 1024), 2)
        cpu_abs = res_usage.get("cpu_absolute", 0.0)
        uptime_ms = res_usage.get("uptime", 0)
        uptime_sec = uptime_ms // 1000

        limits = details.get("limits", {})
        max_mem = limits.get("memory", "Unlimited")
        max_disk = limits.get("disk", "Unlimited")
        max_cpu = limits.get("cpu", "Unlimited")

        return (
            f"Server: {details.get('name', 'Unknown')} (ID: {details.get('identifier', s_id)}, Target: '{server}')\n"
            f"State: {current_state.upper()} (Suspended: {is_suspended})\n"
            f"CPU Usage: {cpu_abs}% (Limit: {max_cpu}%)\n"
            f"Memory Usage: {mem_mb} MB / {max_mem} MB\n"
            f"Disk Usage: {disk_mb} MB / {max_disk} MB\n"
            f"Uptime: {uptime_sec} seconds"
        )
    except Exception as e:
        return f"Error retrieving server status for '{server}': {str(e)}"


@mcp.tool()
def pelican_send_power_action(action: str, server: str = "paper") -> str:
    """Send a power action to a server (start, stop, restart, kill).

    Args:
        action: Power signal to send: 'start', 'stop', 'restart', or 'kill'.
        server: Target server alias ('paper'/'backend', 'proxy'/'velocity') or direct server ID (default: 'paper').
    """
    valid_actions = {"start", "stop", "restart", "kill"}
    action_lower = action.strip().lower()
    if action_lower not in valid_actions:
        return f"Invalid action '{action}'. Must be one of: {', '.join(sorted(valid_actions))}."

    try:
        url, api_key, s_id = _get_config(server)
        resp = requests.post(
            f"{url}/api/client/servers/{s_id}/power",
            headers=_headers(api_key),
            json={"signal": action_lower},
            timeout=15,
        )
        if resp.status_code in (204, 200, 202):
            return f"Successfully sent power signal '{action_lower}' to server '{server}' ({s_id})."
        return f"Failed to send power signal ({resp.status_code}): {resp.text}"
    except Exception as e:
        return f"Error sending power action to '{server}': {str(e)}"


@mcp.tool()
def pelican_send_command(command: str, server: str = "paper") -> str:
    """Execute a console command on either Paper backend or Velocity proxy.

    Args:
        command: The exact command string to send to the console (e.g. 'dpw reload', 'velocity dump').
        server: Target server alias ('paper'/'backend', 'proxy'/'velocity') or direct server ID (default: 'paper').
    """
    try:
        url, api_key, s_id = _get_config(server)
        resp = requests.post(
            f"{url}/api/client/servers/{s_id}/command",
            headers=_headers(api_key),
            json={"command": command},
            timeout=10,
        )
        if resp.status_code in (204, 200, 202):
            return f"Successfully sent command to server '{server}' ({s_id}): '{command}'"
        return f"Failed to send command ({resp.status_code}): {resp.text}"
    except Exception as e:
        return f"Error executing command on '{server}': {str(e)}"


@mcp.tool()
def pelican_get_logs(lines: int = 100, server: str = "paper", log_file: str = "/logs/latest.log") -> str:
    """Read recent server log output from Paper or Velocity to check for startup status or errors.

    Args:
        lines: Number of trailing lines to return (default: 100).
        server: Target server alias ('paper'/'backend', 'proxy'/'velocity') or direct server ID (default: 'paper').
        log_file: Path to the log file on the server (default: '/logs/latest.log' for Paper, or '/logs/velocity.log' for Velocity if configured).
    """
    try:
        url, api_key, s_id = _get_config(server)
        resp = requests.get(
            f"{url}/api/client/servers/{s_id}/files/contents",
            headers=_headers(api_key),
            params={"file": log_file},
            timeout=15,
        )
        if resp.status_code == 404:
            return f"Log file '{log_file}' was not found on server '{server}' ({s_id})."
        resp.raise_for_status()

        log_content = resp.text
        all_lines = log_content.splitlines()
        tail = all_lines[-lines:] if lines < len(all_lines) else all_lines
        return f"--- Log Tail ({server} - {s_id}) [{len(tail)} lines] ---\n" + "\n".join(tail)
    except Exception as e:
        return f"Error reading logs on '{server}': {str(e)}"


@mcp.tool()
def pelican_list_files(directory: str = "/", server: str = "paper") -> str:
    """List files and folders in a directory on the target server.

    Args:
        directory: Remote directory path (default: '/').
        server: Target server alias ('paper'/'backend', 'proxy'/'velocity') or direct server ID (default: 'paper').
    """
    try:
        url, api_key, s_id = _get_config(server)
        resp = requests.get(
            f"{url}/api/client/servers/{s_id}/files/list",
            headers=_headers(api_key),
            params={"directory": directory},
            timeout=15,
        )
        resp.raise_for_status()
        items = resp.json().get("data", [])

        if not items:
            return f"Directory '{directory}' on server '{server}' ({s_id}) is empty."

        output = [f"Contents of '{directory}' on server '{server}' ({s_id}):", "-" * 60]
        for item in items:
            attrs = item.get("attributes", {})
            name = attrs.get("name", "unknown")
            is_file = attrs.get("is_file", True)
            size = attrs.get("size", 0)
            kind = "FILE" if is_file else "DIR "
            size_str = f"{size} B" if is_file else "-"
            output.append(f"[{kind}] {name:<35} {size_str}")
        return "\n".join(output)
    except Exception as e:
        return f"Error listing directory '{directory}' on '{server}': {str(e)}"


@mcp.tool()
def pelican_read_file(file_path: str, server: str = "paper") -> str:
    """Read the text content of a file on the server (e.g. config.yml, velocity.toml, server.properties).

    Args:
        file_path: Full path to the remote file (e.g. '/plugins/DynamicPlayerWorlds/config.yml' or '/velocity.toml').
        server: Target server alias ('paper'/'backend', 'proxy'/'velocity') or direct server ID (default: 'paper').
    """
    try:
        url, api_key, s_id = _get_config(server)
        resp = requests.get(
            f"{url}/api/client/servers/{s_id}/files/contents",
            headers=_headers(api_key),
            params={"file": file_path},
            timeout=15,
        )
        if resp.status_code == 404:
            return f"File '{file_path}' was not found on server '{server}' ({s_id})."
        resp.raise_for_status()
        return resp.text
    except Exception as e:
        return f"Error reading file '{file_path}' on '{server}': {str(e)}"


@mcp.tool()
def pelican_write_file(file_path: str, content: str, server: str = "paper") -> str:
    """Write text content to a remote file on the server.

    Args:
        file_path: Path where the file should be written.
        content: The text content to write.
        server: Target server alias ('paper'/'backend', 'proxy'/'velocity') or direct server ID (default: 'paper').
    """
    try:
        url, api_key, s_id = _get_config(server)
        resp = requests.post(
            f"{url}/api/client/servers/{s_id}/files/write",
            headers={
                "Authorization": f"Bearer {api_key}",
                "Accept": "application/json",
                "Content-Type": "text/plain",
            },
            params={"file": file_path},
            data=content.encode("utf-8"),
            timeout=15,
        )
        if resp.status_code in (204, 200):
            return f"Successfully wrote {len(content)} characters to '{file_path}' on server '{server}' ({s_id})."
        return f"Failed to write file ({resp.status_code}): {resp.text}"
    except Exception as e:
        return f"Error writing file '{file_path}' on '{server}': {str(e)}"


@mcp.tool()
def pelican_delete_files(files: list[str], root_dir: str = "/", server: str = "paper") -> str:
    """Delete one or more files or directories from the server.

    Args:
        files: List of file/directory names relative to root_dir.
        root_dir: Root directory path where the files reside (default: '/').
        server: Target server alias ('paper'/'backend', 'proxy'/'velocity') or direct server ID (default: 'paper').
    """
    if not files:
        return "No files specified to delete."

    try:
        url, api_key, s_id = _get_config(server)
        resp = requests.post(
            f"{url}/api/client/servers/{s_id}/files/delete",
            headers=_headers(api_key),
            json={"root": root_dir, "files": files},
            timeout=15,
        )
        if resp.status_code in (204, 200):
            return f"Successfully deleted {len(files)} items from '{root_dir}' on server '{server}' ({s_id}): {', '.join(files)}"
        return f"Failed to delete files ({resp.status_code}): {resp.text}"
    except Exception as e:
        return f"Error deleting files on '{server}': {str(e)}"


@mcp.tool()
def pelican_upload_file(
    remote_dir: str,
    file_name: str,
    file_content_base64: str,
    server: str = "paper",
) -> str:
    """Upload a base64 encoded file to the server.

    Args:
        remote_dir: Target directory on the server (e.g. '/plugins').
        file_name: The destination filename (e.g. 'DynamicPlayerWorlds.jar').
        file_content_base64: The base64-encoded string of the file bytes.
        server: Target server alias ('paper'/'backend', 'proxy'/'velocity') or direct server ID (default: 'paper').
    """
    try:
        url, api_key, s_id = _get_config(server)

        # 1. Request signed upload URL from panel
        upload_resp = requests.get(
            f"{url}/api/client/servers/{s_id}/files/upload",
            headers=_headers(api_key),
            timeout=10,
        )
        upload_resp.raise_for_status()
        signed_upload_url = upload_resp.json().get("attributes", {}).get("url")
        if not signed_upload_url:
            return "Failed to obtain signed upload URL from Pelican Panel."

        # 2. Decode base64 payload and POST multipart to signed upload URL
        file_bytes = base64.b64decode(file_content_base64)
        files = {"files": (file_name, file_bytes)}
        target_dir = remote_dir.lstrip("/")

        post_resp = requests.post(
            signed_upload_url,
            params={"directory": target_dir} if target_dir else {},
            files=files,
            timeout=60,
        )
        if post_resp.status_code in (200, 204):
            return f"Successfully uploaded '{file_name}' ({len(file_bytes)} bytes) to '{remote_dir}' on server '{server}' ({s_id})."
        return f"Upload endpoint returned status {post_resp.status_code}: {post_resp.text}"
    except Exception as e:
        return f"Error uploading file '{file_name}' on '{server}': {str(e)}"


@mcp.tool()
def pelican_deploy_plugin(
    local_jar_path: str,
    server: str = "paper",
    remote_dir: str = "/plugins",
    cleanup_pattern: str = "",
) -> str:
    """Deploy a locally compiled plugin .jar file to Paper or Velocity with automatic cleanup of older versions.

    Args:
        local_jar_path: Path to the local .jar file on the host machine.
        server: Target server alias ('paper'/'backend', 'proxy'/'velocity') or direct server ID (default: 'paper').
        remote_dir: Target directory on the server (default: '/plugins').
        cleanup_pattern: Optional glob pattern (e.g. 'DynamicPlayerWorlds*.jar' or '*proxy*.jar') to remove older builds prior to uploading.
    """
    local_file = Path(local_jar_path)
    try:
        url, api_key, s_id = _get_config(server)
        return _deploy_single(url, api_key, s_id, local_file, remote_dir, cleanup_pattern)
    except Exception as e:
        return f"Error during plugin deployment to '{server}': {str(e)}"


@mcp.tool()
def pelican_deploy_network(
    backend_jar_path: str = "backend/build/libs/backend-all.jar",
    proxy_jar_path: str = "proxy/build/libs/proxy-all.jar",
    restart: bool = True,
) -> str:
    """Deploy both Paper backend and Velocity proxy plugins in one go, with optional automatic restarts.

    Args:
        backend_jar_path: Path to the built Paper plugin jar (or empty to skip backend).
        proxy_jar_path: Path to the built Velocity proxy plugin jar (or empty to skip proxy).
        restart: If true, sends a restart power action to both servers after successful upload.
    """
    results: list[str] = []

    # 1. Deploy Paper backend if specified
    if backend_jar_path:
        try:
            url, api_key, paper_id = _get_config("paper")
            res = _deploy_single(
                url, api_key, paper_id, Path(backend_jar_path),
                remote_dir="/plugins",
                cleanup_pattern="*backend*.jar" if "backend" in backend_jar_path else "DynamicPlayerWorlds*.jar"
            )
            results.append(f"[Paper Backend]: {res}")
            if restart:
                requests.post(f"{url}/api/client/servers/{paper_id}/power", headers=_headers(api_key), json={"signal": "restart"}, timeout=10)
                results.append("[Paper Backend]: Sent restart signal.")
        except Exception as e:
            results.append(f"[Paper Backend Error]: {str(e)}")

    # 2. Deploy Velocity proxy if specified
    if proxy_jar_path:
        try:
            url, api_key, proxy_id = _get_config("proxy")
            res = _deploy_single(
                url, api_key, proxy_id, Path(proxy_jar_path),
                remote_dir="/plugins",
                cleanup_pattern="*proxy*.jar" if "proxy" in proxy_jar_path else "DynamicPlayerWorlds*.jar"
            )
            results.append(f"[Velocity Proxy]: {res}")
            if restart:
                requests.post(f"{url}/api/client/servers/{proxy_id}/power", headers=_headers(api_key), json={"signal": "restart"}, timeout=10)
                results.append("[Velocity Proxy]: Sent restart signal.")
        except Exception as e:
            results.append(f"[Velocity Proxy Error]: {str(e)}")

    return "\n".join(results)


if __name__ == "__main__":
    mcp.run(transport="stdio")
