# Multi-DB MCP Server

A read-only MCP (Model Context Protocol) server for querying multiple database types with unified access. Currently supports **DB2 for i (AS/400)** and **SingleStore** databases.

## Contents

- [Quick Start](#quick-start)
- [Available Tools](#available-tools)
  - [Response Format](#response-format)
- [Configuration](#configuration)
- [Building and Running](#building-and-running)
- [Usage with AI Clients](#usage-with-ai-clients)
- [Database-Specific Features](#database-specific-features)
- [Security](#security)
- [Troubleshooting](#troubleshooting)
- [Development](#development)
- [License](#license)

## Quick Start

```bash
# 1. Copy the example config and fill in your real connection details
cp connections.json.example connections.json

# 2. Build the runnable JAR
./gradlew clean shadowJar

# 3. Run the server (picks up ./connections.json automatically)
java -jar build/libs/multi-db-mcp-readonly-*-all.jar
```

See [Configuration](#configuration) for all connection fields, and [Usage with AI Clients](#usage-with-ai-clients) to wire this into Claude Desktop, Codex, Windsurf, or MCP Inspector.

## Available Tools

The server exposes 4 generic tools that work across every configured connection:

| Tool | Description | Parameters |
|------|-------------|------------|
| `health` | Check database connectivity | `connection_id` (optional) |
| `list_tables` | List tables in schema | `connection_id` (optional), `schema` (optional) |
| `describe_table` | Get table structure | `connection_id` (optional), `schema`, `table` |
| `execute_select` | Run read-only SELECT query | `connection_id` (optional), `query` |

Each tool accepts an optional `connection_id` to target a specific entry from `connections.json`. When it's omitted, the server falls back to a smart default, in this order:

1. The connection with id `ecuador_db2`, if configured
2. Any DB2 for i connection whose id contains "ecuador"
3. Any DB2 for i connection
4. The first connection defined in `connections.json`

### Response Format

Every tool returns its payload as a JSON string inside the MCP `content` field. On success:

```json
{
  "success": true,
  "connection_id": "ecuador_db2",
  "is_default": true,
  "data": { "...": "tool-specific payload, e.g. rows for execute_select, columns for describe_table" }
}
```

On failure (the MCP result also has `isError: true`):

```json
{
  "success": false,
  "error": "Connection not found: unknown_id",
  "error_type": "CONNECTION_NOT_FOUND"
}
```

`error_type` is one of `CONNECTION_NOT_FOUND`, `QUERY_ERROR`, or `HEALTH_CHECK_ERROR`. **Exception:** if a required parameter is missing entirely (e.g. `query` for `execute_select`, or `schema`/`table` for `describe_table`), the MCP SDK rejects the call before it reaches the server logic — you'll get a plain-text validation message instead of this JSON shape, still with `isError: true`.

## Configuration

Configure connections via `connections.json`:

```json
{
  "connections": [
    {
      "id": "db2_prod",
      "type": "DB2_IBMI",
      "host": "as400.company.com",
      "port": 8471,
      "user": "DB2USER",
      "password": "password",
      "database": "PRODLIB",
      "ssl": true,
      "description": "Production DB2 for i database"
    },
    {
      "id": "ss_analytics",
      "type": "SINGLESTORE",
      "host": "svc-xxx.singlestore.com",
      "port": 3306,
      "user": "admin",
      "password": "password",
      "database": "analytics",
      "ssl": true,
      "description": "SingleStore analytics database"
    }
  ],
  "query_timeout_sec": 30,
  "login_timeout_sec": 5
}
```

> **Tip:** the smart-default rules in [Available Tools](#available-tools) look for an id of `ecuador_db2`, or any DB2 for i id containing "ecuador". Name a connection that way if you want it auto-selected when `connection_id` is omitted — otherwise the first connection in the list is used.

### Environment Variables

```bash
# Optional path to connections file
CONNECTIONS_FILE=/path/to/connections.json
```

### Configuration Resolution Order

At startup, the server resolves the configuration file in this order:

1. `--connections-file /absolute/path/to/connections.json`
2. `CONNECTIONS_FILE`
3. `connections.json` next to the running native binary or JAR
4. `connections.json` in the current working directory

If none of those locations exist, startup fails with an actionable error message.

`query_timeout_sec` and `login_timeout_sec` are currently reserved for future use and are ignored by the server.

## Building and Running

### Prerequisites

- Java 25 (GraalVM recommended for native image)
- Gradle

### JVM Mode

Same two commands as [Quick Start](#quick-start); pass `--connections-file /path/to/connections.json` explicitly if it isn't in the current directory:

```bash
./gradlew clean shadowJar
java -jar build/libs/multi-db-mcp-readonly-*-all.jar --connections-file /path/to/connections.json
```

### Generate Metadata

Whenever the MCP SDK version or its dependencies change, regenerate the GraalVM reflection metadata under `src/main/resources/META-INF/native-image/`:

```bash
# Set GraalVM environment
export JAVA_HOME=$(path_to_graalvm)/Contents/Home
./gradlew clean shadowJar

# Point the tracing agent at the metadata directory
java -agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image \
     -jar build/libs/multi-db-mcp-readonly-*-all.jar
```

While that process is running, exercise **every tool against every configured database type** (DB2 for i and SingleStore) via MCP Inspector or another MCP client — the agent only records what it actually observes being used, so skipping an engine or a tool leaves its reflection needs uncaptured. When you're done, stop the process gracefully (e.g. `Ctrl+C`, or disconnect normally from the client) — killing it with `kill -9` can prevent the agent from flushing the final metadata to disk.

### Native Image Mode

```bash
# Build native binary (requires GraalVM)
export JAVA_HOME=$(path_to_graalvm)/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
./gradlew clean nativeCompile

# Run (much faster startup)
./build/native/nativeCompile/multi-db-mcp-readonly --connections-file /path/to/connections.json
```

## Usage with AI Clients

Every client below can run the server in **JVM mode** (`java -jar ...`) or **Native Image mode** (the compiled binary) — swap the command/arguments accordingly. Replace `/path/to/...` with the real paths on your machine.

### MCP Inspector

```bash
export CONNECTIONS_FILE=/path/to/your/connections.json
npx @modelcontextprotocol/inspector
```

Create a new Server (STDIO) and configure:

**JVM:**
- Command: `java`
- Arguments: `-jar /path/to/multi-db-mcp-readonly/build/libs/multi-db-mcp-readonly-*-all.jar`

**Native Image:**
- Command: `/path/to/multi-db-mcp-readonly/build/native/nativeCompile/multi-db-mcp-readonly`
- Arguments: *(none)*

Environment Variables (either mode, if not already exported in the shell that launches Inspector):
```json
{
  "CONNECTIONS_FILE": "/path/to/your/connections.json"
}
```

### Claude Desktop

Edit `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "multi-db-mcp-readonly": {
      "command": "java",
      "args": ["-jar", "/path/to/multi-db-mcp-readonly-*-all.jar"],
      "env": {
        "CONNECTIONS_FILE": "/path/to/your/connections.json"
      }
    }
  }
}
```

For Native Image mode, set `command` to `/path/to/multi-db-mcp-readonly/build/native/nativeCompile/multi-db-mcp-readonly` and `args` to `[]`, keeping the same `env` block.

### Windsurf / Cursor

For Windsurf, edit `~/.codeium/windsurf/mcp_config.json`. For Cursor, edit your MCP configuration file:

```json
{
  "mcpServers": {
    "multi-db-mcp-readonly": {
      "command": "/path/to/multi-db-mcp-readonly/build/native/nativeCompile/multi-db-mcp-readonly",
      "args": ["--connections-file", "/path/to/your/connections.json"]
    }
  }
}
```

### Codex CLI

#### Method 1: CLI Command (Recommended)
```bash
codex mcp add multi-db-mcp --env CONNECTIONS_FILE=/path/to/your/connections.json -- /path/to/multi-db-mcp-readonly/build/native/nativeCompile/multi-db-mcp-readonly
```

#### Method 2: config.toml
Edit `~/.codex/config.toml`:

```toml
[mcp_servers.multi-db-mcp]
command = "/path/to/multi-db-mcp-readonly/build/native/nativeCompile/multi-db-mcp-readonly"
args = []
[mcp_servers.multi-db-mcp.env]
CONNECTIONS_FILE = "/path/to/your/connections.json"
```

#### Verification
Start Codex and run `/mcp` to see your active MCP servers. Test with:
- "Check the health of all database connections"
- "List tables in the Ecuador database"
- "Describe the CLIENTES table"

## Database-Specific Features

### DB2 for i (AS/400)

- **Schema Support**: QSYS2 system catalog queries
- **Connection Pooling**: JT400 driver integration
- **SSL Support**: AS/400 SSL connections

### SingleStore

- **Extended Metadata**: Shard key and sort key information
- **DDL Parsing**: Automatic extraction of performance-critical keys
- **Information Schema**: Standard MySQL-compatible metadata queries

## Security

- **Read-only enforcement**: All SQL queries are validated to allow only SELECT statements
- **Connection security**: Supports SSL/TLS connections for both database types
- **Input validation**: All tool inputs are validated against JSON schemas
- **Fail-fast startup**: Server exits with error code 1 if configuration is invalid
- **SQL Injection Protection**: Parameterized queries and input sanitization

## Troubleshooting

**`Error: A JNI error has occurred ... UnsupportedClassVersionError`**
The `java` on your `PATH` is older than the Java 25 this project targets. Point your client (or `JAVA_HOME`) at a Java 25+ runtime explicitly — on macOS, list installed JDKs with `/usr/libexec/java_home -V` and use the full path to the `java` binary from a matching one.

**`CONNECTIONS_FILE not set and no default configuration file was found`**
None of the 4 locations in [Configuration Resolution Order](#configuration-resolution-order) had a valid file. Pass `--connections-file /absolute/path/to/connections.json`, export `CONNECTIONS_FILE`, or place a `connections.json` next to the JAR/binary or in your current working directory.

**`describe_table` / `execute_select` fail with a `SQL0xxx` error from DB2 for i**
These come straight from the AS/400 SQL engine (e.g. `SQL0206` = column not found, `SQL0204` = object not found) — they usually mean the `schema`/`table` name is wrong, or the caller lacks privileges on that object. Run `list_tables` first to confirm the exact name and library.

## Development

### How to Add a New Database Type

1. **Add enum value** to `DbType.java`
2. **Implement `DbConnectionProvider`** interface
3. **Add dependency** to `build.gradle`
4. **Update `Main.java`** switch statement
5. **Add tests** for the new implementation

See `SingleStoreConnectionService.java` as a reference implementation.

### Testing

```bash
# Run tests
./gradlew test
```

### Dependencies

- **MCP SDK**: `io.modelcontextprotocol.sdk:mcp-core` + `io.modelcontextprotocol.sdk:mcp-json-jackson2` (versions managed via `mcp-bom`)
- **DB2 Driver**: `net.sf.jt400:jt400:21.0.6`
- **SingleStore Driver**: `com.singlestore:singlestore-jdbc-client:1.2.11`

## License

MIT License
