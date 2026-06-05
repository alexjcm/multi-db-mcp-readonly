# Plan de Implementación v4: `multi-db-mcp-readonly`
_(renombrado desde `db2-ibmi-mcp-readonly`)_

**Repositorio base:** `https://github.com/alexjcm/db2-ibmi-mcp-readonly`
**Objetivo:** Servidor MCP read-only extensible para múltiples BDs (DB2 for i + SingleStore inicialmente)
**Fecha:** Junio 2026 — v4: inconsistencias y omisiones corregidas

---

## Decisiones de diseño

| # | Decisión | Elección |
|---|---|---|
| 1 | Binario | Un solo binario, multi-BD simultánea |
| 2 | `describe_table` | JSON base común + sección `extended` opcional por BD |
| 3 | Driver SingleStore | `singlestore-jdbc-client:1.2.8` (oficial, basado en MariaDB Connector/J) |
| 4 | Nombre repo/artefacto | `multi-db-mcp-readonly` |
| 5 | Paquete base Java | `io.ajcm.multidb.mcp` |
| 6 | Connection pooling | Sin pool en MVP — una conexión lazy por config |
| 7 | Config inválida al arranque | Fail-fast: el servidor sale con código 1 |

---

## Principio rector: sin sobreingeniería

- No se crean abstracciones hasta que haya al menos 2 implementaciones que las justifiquen.
- Un archivo de configuración (`connections.json`). Sin doble fuente.
- Los tools se registran en `Main.java` con ayuda de `ToolBuilder` — no hace falta un registry separado.
- Sin `ConnectionRegistry` como clase. Un `Map<String, DbConnectionProvider>` en `Main.java` es suficiente.
- Sin connection pool en el MVP. Una `Connection` lazy por `ConnectionConfig`.
- Resources MCP diferidos: `list_tables` y `describe_table` como tools cubren la misma necesidad hoy.

---

## Cómo el agente descubre los tools (protocolo MCP)

Flujo estándar según `modelcontextprotocol.io/docs/learn/server-concepts`:

```
1. initialize     → servidor declara capabilities { tools: { listChanged: true } }
2. tools/list     → servidor devuelve array de tool definitions (name, title, description, inputSchema)
3. Agente lee description de cada tool y decide cuándo invocarlo
4. tools/call     → agente invoca el tool con sus argumentos
5. notifications/tools/list_changed → si el listado cambia, cliente refresca con tools/list
```

El agente **no tiene otra fuente de información** sobre los tools — solo `name`, `title` y `description`.
Por eso las descriptions se generan automáticamente con datos reales de cada `ConnectionConfig`.

### Handshake `initialize`

```json
{
  "protocolVersion": "2025-06-18",
  "capabilities": {
    "tools": { "listChanged": true }
  },
  "serverInfo": {
    "name": "multi-db-mcp-readonly",
    "version": "2.0.0"
  }
}
```

`listChanged: true` se declara desde el inicio para soportar futuras conexiones dinámicas sin costo.

### Ejemplo de `tools/list` con descriptions generadas

```json
{
  "tools": [
    {
      "name": "list_tables_db2_prod",
      "title": "List tables — DB2 for i: PRODLIB",
      "description": "Lists all available tables in the DB2 for i database PRODLIB (host: as400.company.com). Use this tool first to discover what tables exist before querying. Follow up with describe_table_db2_prod to get column structure.",
      "inputSchema": {
        "type": "object",
        "properties": {
          "schema": {
            "type": "string",
            "description": "DB2 schema/library name to filter tables (optional)"
          }
        }
      }
    },
    {
      "name": "execute_select_ss_ventas",
      "title": "Execute SELECT — SingleStore: ventas",
      "description": "Executes a read-only SELECT query against the SingleStore database 'ventas' (host: svc-xxx.singlestore.com). Supports SingleStore SQL syntax including window functions and approximate aggregations. Only SELECT statements are allowed.",
      "inputSchema": {
        "type": "object",
        "properties": {
          "query": {
            "type": "string",
            "description": "SQL SELECT statement to execute"
          }
        },
        "required": ["query"]
      }
    }
  ]
}
```

---

## Estructura de clases

```
src/main/java/io/ajcm/multidb/mcp/
│
├── Main.java                              ← Cableado: lee config → instancia providers
│                                            → registra tools → arranca servidor MCP
│
├── config/
│   └── ConnectionConfig.java              ← Record: id, type, host, port, user,
│                                            password, database, ssl, description?
│
├── db/
│   ├── DbConnectionProvider.java          ← Interfaz: listTables(), describeTable(),
│   │                                        executeSelect(), healthCheck()
│   ├── DbType.java                        ← Enum: DB2_IBMI, SINGLESTORE
│   ├── TableMetadata.java                 ← Record: table, schema, db_type,
│   │                                        columns[], primary_key[], foreign_keys[],
│   │                                        extended? (nullable)
│   ├── Db2ConnectionService.java          ← Implementa DbConnectionProvider para DB2
│   └── SingleStoreConnectionService.java  ← Implementa DbConnectionProvider para SS
│
├── tool/
│   └── ToolBuilder.java                   ← Métodos estáticos: buildHealthTool(),
│                                            buildListTablesTool(), buildDescribeTableTool(),
│                                            buildExecuteSelectTool()
│                                            Genera descriptions desde ConnectionConfig.
│
├── validation/
│   └── SqlReadOnlyGuard.java              ← Sin cambios
│
└── util/
    └── ConfigLoader.java                  ← Lee y valida connections.json
                                             Lanza IllegalArgumentException si config inválida
```

**Nota sobre `ToolBuilder`:** No es un registry ni una fábrica — es simplemente
el lugar donde viven los cuatro métodos `build*Tool()` para no saturar `Main.java`.
Sin estado, sin ciclo de vida, sin interfaces adicionales.

---

## Flujo de arranque

```java
// Main.java — pseudocódigo ilustrativo
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.StdioServerTransport;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.ServerCapabilities;

List<ConnectionConfig> configs = ConfigLoader.load(
    System.getenv().getOrDefault("CONNECTIONS_FILE", "connections.json")
);
// ConfigLoader lanza si algún campo requerido falta → servidor sale con código 1 (fail-fast)

Map<String, DbConnectionProvider> providers = new LinkedHashMap<>();
for (ConnectionConfig cfg : configs) {
    DbConnectionProvider provider = switch (cfg.type()) {
        case DB2_IBMI    -> new Db2ConnectionService(cfg);
        case SINGLESTORE -> new SingleStoreConnectionService(cfg);
    };
    providers.put(cfg.id(), provider);
}

// API MCP SDK actual (v1.1.3)
StdioServerTransport transport = new StdioServerTransport(new ObjectMapper());
McpAsyncServer server = McpServer.async(transport)
    .serverInfo("multi-db-mcp-readonly", "2.0.0")
    .capabilities(ServerCapabilities.builder()
        .tools(true)
        .logging()
        .build())
    .build();

for (var entry : providers.entrySet()) {
    String id = entry.getKey();
    DbConnectionProvider p = entry.getValue();
    ConnectionConfig cfg = configs.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();

    server.addTool(ToolBuilder.buildHealthTool(id, cfg, p));
    server.addTool(ToolBuilder.buildListTablesTool(id, cfg, p));
    server.addTool(ToolBuilder.buildDescribeTableTool(id, cfg, p));
    server.addTool(ToolBuilder.buildExecuteSelectTool(id, cfg, p));
}
```

---

## Contrato de respuesta de tools (`ToolResponse`)

Todos los tools devuelven JSON con el mismo formato. El agente siempre puede leerlo igual:

```json
// Respuesta exitosa
{
  "success": true,
  "data": { ... }
}

// Respuesta de error
{
  "success": false,
  "error": "Connection refused: svc-xxx.singlestore.com:3306",
  "error_type": "CONNECTION_ERROR"
}
```

`error_type` acepta: `CONNECTION_ERROR`, `QUERY_ERROR`, `VALIDATION_ERROR`, `TIMEOUT_ERROR`.
Esto permite al agente distinguir si reintentar o reportar al usuario sin parsear stacktraces.

### Respuesta de `health_{id}`

```json
{
  "success": true,
  "data": {
    "status": "ok",
    "db_type": "SINGLESTORE",
    "host": "svc-xxx.singlestore.com",
    "database": "ventas",
    "latency_ms": 12
  }
}
```

---

## Tools por conexión

Para cada `ConnectionConfig` se crean exactamente **4 tools**:

| Tool | `name` | Cuándo lo usa el agente |
|---|---|---|
| Health | `health_{id}` | Verificar conectividad antes de queries críticas |
| List tables | `list_tables_{id}` | Primer paso: descubrir qué tablas existen |
| Describe table | `describe_table_{id}` | Obtener estructura antes de escribir SQL |
| Execute select | `execute_select_{id}` | Ejecutar la query |

**Flujo natural del agente — documentado en la description de `list_tables_{id}`:**
```
list_tables_{id}  →  describe_table_{id}  →  execute_select_{id}
```

---

## Salida de `describe_table`: base + extended

```json
{
  "success": true,
  "data": {
    "table": "pedidos",
    "schema": "ventas",
    "db_type": "SINGLESTORE",
    "columns": [
      { "name": "id",         "type": "INT",           "nullable": false, "key": "PRIMARY" },
      { "name": "cliente_id", "type": "BIGINT",         "nullable": false, "key": "" },
      { "name": "fecha",      "type": "DATETIME",       "nullable": true,  "key": "" },
      { "name": "total",      "type": "DECIMAL(10,2)",  "nullable": false, "key": "" }
    ],
    "primary_key": ["id"],
    "foreign_keys": [],
    "extended": {
      "shard_key": ["cliente_id"],
      "sort_key": ["fecha"],
      "storage_type": "columnstore"
    }
  }
}
```

- `columns` y `primary_key` → idénticos para todas las BDs
- `extended` → ausente si la BD no tiene metadata propia; DB2 podría incluir library/journal info aquí
- El `extended` es crítico para NL2SQL: el agente genera queries más eficientes conociendo el shard key

### Queries de metadata

**`list_tables`:**

| BD | Query |
|---|---|
| DB2 for i | `SELECT TABLE_NAME FROM QSYS2.SYSTABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'T' ORDER BY TABLE_NAME` |
| SingleStore | `SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME` |

**`describe_table` en SingleStore:**
```sql
-- Columnas base
SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH,
       IS_NULLABLE, COLUMN_DEFAULT, COLUMN_KEY
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
ORDER BY ORDINAL_POSITION;

-- Constraints
SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE
FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?;

-- Bloque extended: shard/sort key
SHOW CREATE TABLE `{schema}`.`{table}`;
-- Se parsea el DDL para extraer SHARD KEY(...) y SORT KEY(...)
```

---

## Configuración

**Un único mecanismo: `connections.json`.**
La ruta se pasa via la ENV var `CONNECTIONS_FILE`. Si no se define, se busca `connections.json`
en el directorio de trabajo. El `.env.example` del repo original se reemplaza por `connections.json.example`.

```json
{
  "connections": [
    {
      "id": "db2_prod",
      "type": "DB2_IBMI",
      "host": "as400.company.com",
      "port": 446,
      "user": "USRPRD",
      "password": "secret",
      "database": "PRODLIB",
      "ssl": true,
      "description": "DB2 for i production — IBM AS/400 PRODLIB library"
    },
    {
      "id": "ss_ventas",
      "type": "SINGLESTORE",
      "host": "svc-xxx.singlestore.com",
      "port": 3306,
      "user": "admin",
      "password": "secret",
      "database": "ventas",
      "ssl": true,
      "description": "SingleStore production — sales and orders database"
    }
  ],
  "query_timeout_sec": 30,
  "login_timeout_sec": 5
}
```

El campo `description` es opcional. Si se omite, se genera automáticamente:
`"{type} database '{database}' on {host}"`.

**Validación al arranque (fail-fast):** `ConfigLoader` verifica que cada conexión tenga
`id`, `type` válido, `host`, `user`, `password` y `database`. Si falta alguno,
imprime el error y el proceso sale con código 1. El servidor nunca arranca parcialmente.

**Configuración en Claude Desktop / Windsurf:**
```json
{
  "mcpServers": {
    "multi-db": {
      "command": "/path/to/multi-db-mcp-readonly",
      "env": {
        "CONNECTIONS_FILE": "/path/to/connections.json"
      }
    }
  }
}
```

---

## Resources MCP: diferido

La especificación distingue Tools (acciones) de Resources (contexto pasivo). El schema
de la BD encajaría como Resource (`schema://{id}/tables`), pero los tools `list_tables`
y `describe_table` cubren la misma necesidad en el MVP sin complejidad adicional.

Se implementa en una fase posterior cuando haya evidencia de que el agente se beneficia
del contexto de schema previo a la invocación de tools.

---

## Fases de implementación

### FASE 1 — Refactor + foundation
**~1 día**

- Fijar paquete base: `io.ajcm.multidb.mcp` en `settings.gradle` y todos los archivos
- Crear `ConnectionConfig` (record), `DbType` (enum), `TableMetadata` (record)
- Crear `DbConnectionProvider` (interfaz con 4 métodos)
- Crear `ToolResponse` (record: success, data, error, error_type)
- Refactorizar `Db2ConnectionService` para implementar la interfaz
- Crear `ConfigLoader` con validación fail-fast
- Crear `ToolBuilder` con los 4 métodos `build*Tool()` generando descriptions automáticas
- Reescribir `Main.java` con el flujo de arranque descrito arriba
- Declarar `capabilities: { tools: { listChanged: true } }` en `initialize`
- **Validar:** `tools/list` en MCP Inspector muestra los 4 tools de DB2 con descriptions correctas y formato `ToolResponse`

### FASE 2 — Implementación SingleStore
**~1.5 días**

- Agregar `singlestore-jdbc-client:1.2.8` en `build.gradle`
- Implementar `SingleStoreConnectionService` con queries `information_schema`
- Implementar bloque `extended` (shard/sort key vía `SHOW CREATE TABLE` + parsing)
- Reemplazar `.env.example` por `connections.json.example`
- **Validar:** 4 tools de SS en MCP Inspector, incluyendo `extended` en `describe_table`

### FASE 3 — GraalVM Native Image
**~0.5 días**

- Tracing agent con ambas conexiones activas para capturar reflexión de ambos drivers
- Generar/actualizar `reachability-metadata.json` (formato unificado GraalVM JDK 23+)
- Compilar nativo y validar los 8 tools (4 DB2 + 4 SS)

### FASE 4 — Documentación y tests
**~0.5 días**

- `README.md`: instrucciones de configuración + sección "Cómo agregar una nueva BD"
- `AGENTS.md`: actualizar estructura de paquetes y contrato de `DbConnectionProvider`
- Tests: `ConfigLoaderTest` (validación fail-fast), `Db2ConnectionServiceTest`,
  `SingleStoreConnectionServiceTest`, `ToolBuilderTest` (descriptions generadas)

**Total estimado: 3.5 días**

---

## Cómo agregar una BD en el futuro

1. Agregar valor al enum `DbType`
2. Agregar driver JDBC en `build.gradle`
3. Crear `XyzConnectionService implements DbConnectionProvider`
4. Agregar caso al `switch` en `Main.java`
5. Actualizar `connections.json.example` y `README.md`

Los tools aparecen automáticamente en `tools/list`. `ToolBuilder` y `ToolResponse`
no necesitan cambios.

---

## Proyectos de referencia consultados

| Proyecto | Aporte |
|---|---|
| **FreePeak/db-mcp-server** | Patrón naming `{action}_{id}` para tools |
| **OpenLink/mcp-jdbc-server** | Java + JDBC + MCP SDK, estructura base |
| **SchemaCrawler AI** | Metadata rica en JSON para NL2SQL, sección `extended` |
| **AWS Aurora DSQL MCP** | Flujo agente: schema → query; fail-fast en config |
| **memsql/S2-JDBC-Connector** | Driver oficial SS, URL `jdbc:singlestore://` |
| **modelcontextprotocol.io/docs** | Estándar tools/list, descriptions, listChanged, Resources vs Tools |

---

*Plan v4 — `multi-db-mcp-readonly` — Junio 2026*
