# db2-ibmi-mcp-readonly

Connects AI assistants to IBM DB2 for i for read‑only querying and metadata inspection via MCP.
Supports multiple database profiles via environment variables.
Provides tools for SELECT-only queries and table description (with optional PK/FK metadata).

## Available Tools
- health
- list_tables
- describe_table
- execute_select

## Native Mode (GraalVM - Recommended)
Prerequisites: GraalVM 25

### Generate Metadata (First time)
export JAVA_HOME=/Users/ajcm/Library/Java/JavaVirtualMachines/graalvm-jdk-25.0.2+10.1/Contents/Home
./gradlew clean shadowJar
mkdir -p src/main/resources/META-INF/native-image

Test all tools in MCP Inspector to auto generate metadata:
- Command:
/Users/ajcm/Library/Java/JavaVirtualMachines/graalvm-jdk-25.0.2+10.1/Contents/Home/bin/java
- Arguments:
-agentlib:native-image-agent=config-merge-dir=/Users/ajcm/my-mcps/db2-ibmi-mcp-readonly/src/main/resources/META-INF/native-image -jar /Users/ajcm/my-mcps/db2-ibmi-mcp-readonly/build/libs/multi-db-mcp-readonly-2.0.0-all.jar

### Build
export JAVA_HOME=/Users/ajcm/Library/Java/JavaVirtualMachines/graalvm-jdk-25.0.2+10.1/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
./gradlew clean nativeCompile

### Run for validation
./build/native/nativeCompile/multi-db-mcp-readonly


## Java Mode (Alternative)
Prerequisites: Java 25

### Build
Fat JAR for MCP Inspector or MCP Client:
./gradlew clean shadowJar

### Run for validation
java -jar build/libs/db2-ibmi-mcp-readonly-1.0.1-all.jar


## Usage with AI Clients

### Configuration Environment Variables
This server supports two DB profiles. DB2_CONN_IDS is required. Define one or two IDs and set DB2_CONN_<ID>_* variables for each.

- DB2_CONN_IDS=ECUADOR,PANAMA
- DB2_CONN_DEFAULT_ID=ECUADOR
- DB2_CONN_ECUADOR_IBM_I_HOST=YOUR_HOST
- DB2_CONN_ECUADOR_IBM_I_USER=YOUR_USER
- DB2_CONN_ECUADOR_IBM_I_PASSWORD=YOUR_PASSWORD
- DB2_CONN_ECUADOR_IBM_I_SCHEMA=YOUR_SCHEMA
- DB2_CONN_PANAMA_IBM_I_HOST=YOUR_HOST
- DB2_CONN_PANAMA_IBM_I_USER=YOUR_USER
- DB2_CONN_PANAMA_IBM_I_PASSWORD=YOUR_PASSWORD
- DB2_CONN_PANAMA_IBM_I_SCHEMA=YOUR_SCHEMA
- DB2_SSL=false
- DB2_PORT=446

### MCP Inspector (Testing)

#### Run
STDIO
1. The Inspector runs directly through npx without installation (Node 22+):
```bash
export DB2_CONN_IDS=ECUADOR,PANAMA
export DB2_CONN_DEFAULT_ID=ECUADOR

export DB2_CONN_ECUADOR_IBM_I_HOST=YOUR_HOST
export DB2_CONN_ECUADOR_IBM_I_USER=YOUR_USER
export DB2_CONN_ECUADOR_IBM_I_PASSWORD=YOUR_PASSWORD
export DB2_CONN_ECUADOR_IBM_I_SCHEMA=YOUR_SCHEMA

export DB2_CONN_PANAMA_IBM_I_HOST=YOUR_HOST
export DB2_CONN_PANAMA_IBM_I_USER=YOUR_USER
export DB2_CONN_PANAMA_IBM_I_PASSWORD=YOUR_PASSWORD
export DB2_CONN_PANAMA_IBM_I_SCHEMA=YOUR_SCHEMA

npx @modelcontextprotocol/inspector
```

#### Config
2. Create a new Server (STDIO) and configure:
Example for GraalVM:
- Command:
/Users/ajcm/my-mcps/db2-ibmi-mcp-readonly/build/native/nativeCompile/db2-ibmi-mcp-readonly

Example for Java:
- Command:
/Users/ajcm/Library/Java/JavaVirtualMachines/graalvm-jdk-25.0.2+10.1/Contents/Home/bin/java
- Arguments:
-jar /Users/ajcm/my-mcps/db2-ibmi-mcp-readonly/build/libs/db2-ibmi-mcp-readonly-1.0.1-all.jar


### Windsurf
Edit mcp_config.json:

Example for GraalVM:
```json
{
  "mcpServers": {
    "db2-ibmi-mcp-readonly": {
      "command": "/Users/ajcm/my-mcps/db2-ibmi-mcp-readonly/build/native/nativeCompile/db2-ibmi-mcp-readonly",
      "args": [],
      "env": {
        "DB2_CONN_IDS": "ECUADOR,PANAMA",
        "DB2_CONN_DEFAULT_ID": "ECUADOR",
        "DB2_CONN_ECUADOR_IBM_I_HOST": "YOUR_HOST",
        "DB2_CONN_ECUADOR_IBM_I_USER": "YOUR_USER",
        "DB2_CONN_ECUADOR_IBM_I_PASSWORD": "YOUR_PASSWORD",
        "DB2_CONN_ECUADOR_IBM_I_SCHEMA": "YOUR_SCHEMA",
        "DB2_CONN_PANAMA_IBM_I_HOST": "YOUR_HOST",
        "DB2_CONN_PANAMA_IBM_I_USER": "YOUR_USER",
        "DB2_CONN_PANAMA_IBM_I_PASSWORD": "YOUR_PASSWORD",
        "DB2_CONN_PANAMA_IBM_I_SCHEMA": "YOUR_SCHEMA"
      }
    }
  }
}
```

Example for Java:
```json
{
  "mcpServers": {
    "db2-ibmi-mcp-readonly": {
      "args": [
        "-jar",
        "/Users/ajcm/my-mcps/db2-ibmi-mcp-readonly/build/libs/db2-ibmi-mcp-readonly-1.0.1-all.jar"
      ],
      "command": "java",
      "env": {
        "DB2_CONN_IDS": "ECUADOR,PANAMA",
        "DB2_CONN_DEFAULT_ID": "ECUADOR",
        "DB2_CONN_ECUADOR_IBM_I_HOST": "YOUR_HOST",
        "DB2_CONN_ECUADOR_IBM_I_USER": "YOUR_USER",
        "DB2_CONN_ECUADOR_IBM_I_PASSWORD": "YOUR_PASSWORD",
        "DB2_CONN_ECUADOR_IBM_I_SCHEMA": "YOUR_SCHEMA",
        "DB2_CONN_PANAMA_IBM_I_HOST": "YOUR_HOST",
        "DB2_CONN_PANAMA_IBM_I_USER": "YOUR_USER",
        "DB2_CONN_PANAMA_IBM_I_PASSWORD": "YOUR_PASSWORD",
        "DB2_CONN_PANAMA_IBM_I_SCHEMA": "YOUR_SCHEMA",
        "JAVA_HOME": "/path/to/your/java/home"
      }
    }
  }
}
```
