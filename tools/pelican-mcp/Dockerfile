# Lightweight Python container for Pelican MCP Server
FROM python:3.11-slim

# Standard OCI and MCP Metadata Labels for Docker MCP Toolkit
LABEL org.opencontainers.image.title="pelican-panel"
LABEL org.opencontainers.image.description="Pelican Game Server Panel API tools for server control, deployments, logs, and console commands"
LABEL org.opencontainers.image.vendor="DynamicPlayerWorlds"
LABEL mcp.server="true"

# Prevent python from buffering stdout/stderr (crucial for MCP STDIO transport)
ENV PYTHONUNBUFFERED=1

WORKDIR /app

# Install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy server code
COPY server.py .

# Run MCP server over stdio
ENTRYPOINT ["python", "server.py"]
