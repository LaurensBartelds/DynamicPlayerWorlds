# Pelican Panel MCP Server

A Model Context Protocol (MCP) server providing AI agents with tools to interact directly with **Pelican Game Server Panel** (Pterodactyl fork).

This enables agents to autonomously build plugins, deploy them to test servers, hot-replace old builds, restart servers, execute console commands, and verify logs.

---

## Features & Tools

| MCP Tool | Description |
| :--- | :--- |
| `pelican_get_server_status(server_id)` | Returns server state (`RUNNING`, `OFFLINE`, etc.), CPU %, RAM usage, and uptime. |
| `pelican_send_power_action(action, server_id)` | Sends power signals: `start`, `stop`, `restart`, `kill`. |
| `pelican_send_command(command, server_id)` | Executes console commands (e.g. `dpw status`, `say test`). |
| `pelican_get_logs(lines, server_id, log_file)` | Reads recent server logs (`/logs/latest.log`) to inspect startup exceptions or output. |
| `pelican_list_files(directory, server_id)` | Lists files/directories with sizes and file types. |
| `pelican_read_file(file_path, server_id)` | Reads contents of configuration files (e.g. `config.yml`, `server.properties`). |
| `pelican_write_file(file_path, content, server_id)` | Writes/edits text content in a remote file. |
| `pelican_delete_files(files, root_dir, server_id)` | Deletes specified files or folders from the server. |
| `pelican_upload_file(remote_dir, file_name, file_content_base64, server_id)` | Uploads base64-encoded files via Pelican's signed upload URL. |
| `pelican_deploy_plugin(local_jar_path, remote_dir, cleanup_pattern, server_id)` | High-level deployer: automatically removes older versions matching `cleanup_pattern` (e.g. `DynamicPlayerWorlds*.jar`) and uploads the fresh build. |

---

## Setup & Configuration

### 1. Requirements
* A Pelican Panel account with Client API access.
* Go to **Account Settings > API Credentials** and generate an API Key.
* Copy `.env.example` to `.env` and fill in your details:
  ```ini
  PELICAN_URL=https://panel.yourdomain.com
  PELICAN_API_KEY=pelican_your_api_key_here
  DEFAULT_SERVER_ID=abc12345
  ```

---

## Option A: Running with Docker Desktop

### 1. Build the Docker Image
From the `tools/pelican-mcp` directory:
```bash
docker build -t pelican-mcp-server:latest .
```

### 2. Register in Docker Desktop MCP Toolkit
You can import the `catalog.yaml` into Docker Desktop:
```bash
docker mcp catalog create custom-pelican-catalog
docker mcp catalog add custom-pelican-catalog pelican-panel ./catalog.yaml
```
Or in Docker Desktop:
1. Open **Docker Desktop** > **MCP Toolkit**.
2. Add `pelican-panel` to your active profile and enter your `PELICAN_URL` and `PELICAN_API_KEY` in the environment settings.

---

## Option B: Running Locally with Python STDIO

If running outside Docker:
```bash
pip install -r requirements.txt
python server.py
```

### Antigravity / Claude Desktop / Cursor Configuration

Add this to your MCP configuration file:

```json
{
  "mcpServers": {
    "pelican-panel": {
      "command": "python",
      "args": [
        "c:/Users/Laurens Bartelds/Documents/Github/DynamicPlayerWorlds/tools/pelican-mcp/server.py"
      ],
      "env": {
        "PELICAN_URL": "https://panel.yourdomain.com",
        "PELICAN_API_KEY": "pelican_your_api_key_here",
        "DEFAULT_SERVER_ID": "abc12345"
      }
    }
  }
}
```
*(Or if using Docker run directly: replace command with `docker` and args with `["run", "-i", "--rm", "-e", "PELICAN_URL=...", "-e", "PELICAN_API_KEY=...", "pelican-mcp-server:latest"]`)*
