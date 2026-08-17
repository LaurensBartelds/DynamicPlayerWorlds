---
name: pelican-testing
description: Use when testing Minecraft plugins or server setups on live Pelican Panel test servers, including building, hot-replacing jars on Paper and Velocity, restarting servers, checking logs, and executing console test commands.
---

# Pelican Panel Testing Workflow (Paper Backend & Velocity Proxy)

## Overview
This skill guides agents through end-to-end testing of Minecraft network plugins across both **Paper backend** and **Velocity proxy** test servers managed by Pelican Panel.

Agents use the **Pelican MCP tools** (or `tools/pelican-mcp/server.py`) to automate the complete build &rarr; deploy &rarr; restart &rarr; verify cycle.

---

## Server Target Aliases
All tools accept a `server` parameter, which can be:
- `"paper"` (or `"backend"`): Targets `PAPER_SERVER_ID`
- `"proxy"` (or `"velocity"`): Targets `PROXY_SERVER_ID`
- Or any explicit server identifier / UUID.

---

## The Automated Testing Cycle

```
[1. Build: ./gradlew build]
            │
            ▼
[2. Deploy Network: pelican_deploy_network]
     ├── Deploy backend-all.jar to Paper Server (/plugins)
     ├── Deploy proxy-all.jar to Velocity Server (/plugins)
     └── Send restart signal to both servers
            │
            ▼
[3. Inspect Startup Logs]
     ├── pelican_get_logs(server="paper")
     └── pelican_get_logs(server="proxy")
            │
            ▼
[4. Execute Console Test Commands]
     ├── pelican_send_command(server="paper", command="dpw status")
     └── pelican_send_command(server="proxy", command="velocity plugins")
```

---

## Step-by-Step Instructions

### Step 1: Build the Target Jars
Run the local Gradle build command to produce the shadowed/assembled jars:
```powershell
./gradlew build
```
Verify artifacts exist:
- `backend/build/libs/backend-all.jar`
- `proxy/build/libs/proxy-all.jar`

---

### Step 2: Deploy & Hot-Replace (Both or Individual)

#### Deploy Both Servers in One Action:
```python
pelican_deploy_network(
    backend_jar_path="backend/build/libs/backend-all.jar",
    proxy_jar_path="proxy/build/libs/proxy-all.jar",
    restart=True
)
```

#### Or Deploy to a Specific Server:
```python
# Paper Backend only
pelican_deploy_plugin(
    server="paper",
    local_jar_path="backend/build/libs/backend-all.jar",
    cleanup_pattern="*backend*.jar"
)

# Velocity Proxy only
pelican_deploy_plugin(
    server="proxy",
    local_jar_path="proxy/build/libs/proxy-all.jar",
    cleanup_pattern="*proxy*.jar"
)
```

---

### Step 3: Check Server Status
```python
pelican_get_server_status(server="paper")
pelican_get_server_status(server="proxy")
```

---

### Step 4: Verify Clean Startup in Logs
Inspect logs on both nodes to ensure clean enablement without stack traces:
```python
# Paper logs
pelican_get_logs(server="paper", lines=100)

# Velocity logs
pelican_get_logs(server="proxy", lines=100)
```

---

### Step 5: Execute Console Commands
```python
pelican_send_command(server="paper", command="dpw status")
pelican_send_command(server="proxy", command="dpw-proxy status")
```

---

## Configuration Reference
Environment variables or secrets configured in Docker MCP / `.env`:
- `PELICAN_URL`: Base URL of the panel (e.g. `https://panel.example.com`)
- `PELICAN_API_KEY`: Client API Key
- `PAPER_SERVER_ID`: Server ID or UUID for Paper Backend
- `PROXY_SERVER_ID`: Server ID or UUID for Velocity Proxy
