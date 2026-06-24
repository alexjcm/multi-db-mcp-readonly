# Multi-DB MCP Server

A read-only MCP (Model Context Protocol) server for querying multiple database types with unified access. Currently supports **DB2 for i (AS/400)** and **SingleStore** databases.

## Features

- **Multi-Database Support**: Connect to multiple database types simultaneously
- **Health Check**: Verify database connectivity for each connection
- **List Tables**: Discover available tables in specified schemas
- **Describe Table**: Get detailed table structure including columns, primary keys, foreign keys, and database-specific metadata
- **Execute Select**: Run read-only SELECT queries with SQL validation
- **Extended Metadata**: Database-specific information (e.g., SingleStore shard/sort keys)

## Available Tools

For each database connection, the following tools are available:

| Tool | Description | Parameters |
|------|-------------|------------|
| `health_<connection_id>` | Check database connectivity | None |
| `list_tables_<connection_id>` | List tables in schema | `schema` (optional) |
| `describe_table_<connection_id>` | Get table structure | `schema`, `table` |
| `execute_select_<connection_id>` | Run SELECT query | `query` |

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

```bash
# Build
./gradlew clean shadowJar

# Run
java -jar build/libs/multi-db-mcp-readonly-*-all.jar --connections-file /path/to/connections.json
```

### Generate Metadata (First time only)

```bash
# Set GraalVM environment
export JAVA_HOME=$(path_to_graalvm)/Contents/Home
./gradlew clean shadowJar
mkdir -p src/main/resources/META-INF/native-image

# Test all tools in MCP Inspector to auto generate metadata
java -agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image \
     -jar build/libs/multi-db-mcp-readonly-2.0.0-all.jar
```

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

### MCP Inspector

```bash
# Start server
java -jar build/libs/multi-db-mcp-readonly-*-all.jar --connections-file /path/to/connections.json

# In another terminal, use MCP Inspector
npx @modelcontextprotocol/inspector
```

### Claude Desktop

Add to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "multi-db-mcp-readonly": {
      "command": "java",
      "args": ["-jar", "/path/to/multi-db-mcp-readonly-*-all.jar"],
      "env": {
        "CONNECTIONS_FILE": "/path/to/connections.json"
      }
    }
  }
}
```

### Windsurf

Edit `~/.codeium/windsurf/mcp_config.json`:

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

- **MCP SDK**: `io.modelcontextprotocol.sdk:mcp-*`
- **DB2 Driver**: `net.sf.jt400:jt400:21.0.6`
- **SingleStore Driver**: `com.singlestore:singlestore-jdbc-client:1.2.11`

## AI Client Configuration

### MCP Inspector (Testing)

#### Run
```bash
export CONNECTIONS_FILE=/path/to/your/connections.json
npx @modelcontextprotocol/inspector
```

#### Config
Create a new Server (STDIO) and configure:

**GraalVM Native Image:**
- Command: `/path/to/multi-db-mcp-readonly/build/native/nativeCompile/multi-db-mcp-readonly`
- Arguments: `[]`
- Environment Variables:
```json
{
  "CONNECTIONS_FILE": "/path/to/your/connections.json"
}
```

**Java JVM:**
- Command: `java`
- Arguments: `["-jar", "/path/to/multi-db-mcp-readonly/build/libs/multi-db-mcp-readonly-2.0.0-all.jar"]`
- Environment Variables:
```json
{
  "CONNECTIONS_FILE": "/path/to/your/connections.json"
}
```

### Windsurf/Cursor

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

### Claude Desktop

Edit `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "multi-db-mcp-readonly": {
      "command": "/path/to/multi-db-mcp-readonly/build/native/nativeCompile/multi-db-mcp-readonly",
      "args": [],
      "env": {
        "CONNECTIONS_FILE": "/path/to/your/connections.json"
      }
    }
  }
}
```

**Important:** Replace `/path/to/your/connections.json` with the actual path to your database configuration file.

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

## License

MIT License
