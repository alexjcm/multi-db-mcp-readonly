package io.ajcm.multidb.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ajcm.multidb.mcp.config.ConnectionConfig;
import io.ajcm.multidb.mcp.config.DbType;
import io.ajcm.multidb.mcp.db.DbConnectionProvider;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Smart tools with intelligent defaults:
 * - Default country: Ecuador
 * - Default DB type: DB2 (over SingleStore)
 * - Default connection: DB2 Ecuador
 */
public class SmartDefaultToolBuilder {
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private SmartDefaultToolBuilder() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static Map<String, Object> stringProperty() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * Gets the default connection provider based on smart rules:
     * 1. DB2 Ecuador (ecuador_db2)
     * 2. Any DB2 from Ecuador
     * 3. Any DB2
     * 4. First available
     */
    private static DbConnectionProvider getDefaultProvider(Map<String, DbConnectionProvider> providers) {
        // Rule 1: Look for DB2 Ecuador
        Optional<DbConnectionProvider> ecuadorDb2 = providers.entrySet().stream()
            .filter(entry -> entry.getKey().equals("ecuador_db2"))
            .map(Map.Entry::getValue)
            .findFirst();
        
        if (ecuadorDb2.isPresent()) {
            return ecuadorDb2.get();
        }
        
        // Rule 2: Look for any DB2 from Ecuador
        Optional<DbConnectionProvider> anyEcuadorDb2 = providers.entrySet().stream()
            .filter(entry -> {
                String key = entry.getKey();
                DbConnectionProvider provider = entry.getValue();
                return key.toLowerCase().contains("ecuador") && 
                       provider.getConfig().type() == DbType.DB2_IBMI;
            })
            .map(Map.Entry::getValue)
            .findFirst();
        
        if (anyEcuadorDb2.isPresent()) {
            return anyEcuadorDb2.get();
        }
        
        // Rule 3: Look for any DB2
        Optional<DbConnectionProvider> anyDb2 = providers.values().stream()
            .filter(provider -> provider.getConfig().type() == DbType.DB2_IBMI)
            .findFirst();
        
        if (anyDb2.isPresent()) {
            return anyDb2.get();
        }
        
        // Rule 4: First available
        return providers.values().iterator().next();
    }
    
    /**
     * Gets the connection provider - either specific or smart default.
     */
    private static DbConnectionProvider getProvider(
            Map<String, DbConnectionProvider> providers, 
            Map<String, Object> arguments) {
        
        String connectionId = (String) arguments.get("connection_id");
        
        if (connectionId != null && !connectionId.trim().isEmpty()) {
            return providers.get(connectionId);
        }
        
        // Use smart default
        return getDefaultProvider(providers);
    }
    
    /**
     * Gets the connection ID for response messages.
     */
    private static String getConnectionId(
            Map<String, DbConnectionProvider> providers, 
            Map<String, Object> arguments,
            DbConnectionProvider provider) {
        
        String connectionId = (String) arguments.get("connection_id");
        
        if (connectionId != null && !connectionId.trim().isEmpty()) {
            return connectionId;
        }
        
        // Find ID of the default provider
        for (Map.Entry<String, DbConnectionProvider> entry : providers.entrySet()) {
            if (entry.getValue() == provider) {
                return entry.getKey();
            }
        }
        
        return "unknown";
    }
    
    /**
     * Gets default connection info for descriptions.
     */
    private static String getDefaultInfo(Map<String, DbConnectionProvider> providers) {
        DbConnectionProvider defaultProvider = getDefaultProvider(providers);
        String defaultId = getConnectionId(providers, Map.of(), defaultProvider);
        ConnectionConfig config = defaultProvider.getConfig();
        
        return String.format("%s (%s %s on %s)", 
            defaultId, 
            config.type().toString(),
            config.database(), 
            config.host());
    }
    
    /**
     * Builds a smart health check tool with intelligent defaults.
     */
    public static McpServerFeatures.SyncToolSpecification buildHealthTool(
            Map<String, DbConnectionProvider> providers) {
        
        String defaultInfo = getDefaultInfo(providers);
        String toolName = "health";
        String title = "Health Check — Database Connectivity";
        String description = String.format(
            "Verifies connectivity to any configured database. " +
            "Use connection_id parameter to specify which database to check. " +
            "If omitted, uses default: %s. " +
            "Default rules: Ecuador > DB2 > First available. " +
            "Available connections: %s",
            defaultInfo,
            String.join(", ", providers.keySet())
        );
        
        Map<String, Object> inputSchema = objectSchema(
            Map.of("connection_id", stringProperty()),
            List.of()
        );

        McpSchema.Tool tool = McpSchema.Tool.builder(toolName, inputSchema)
            .title(title)
            .description(description)
            .build();

        return new McpServerFeatures.SyncToolSpecification(
            tool,
            (exchange, request) -> {
                try {
                    DbConnectionProvider provider = getProvider(providers, request.arguments());
                    String connectionId = getConnectionId(providers, request.arguments(), provider);

                    if (provider == null) {
                        ObjectNode errorNode = mapper.createObjectNode();
                        errorNode.put("success", false);
                        errorNode.put("error", "Connection not found: " + request.arguments().get("connection_id"));
                        errorNode.set("available_connections", mapper.valueToTree(providers.keySet()));
                        errorNode.put("error_type", "CONNECTION_NOT_FOUND");
                        String error = errorNode.toString();
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(McpSchema.TextContent.builder(error).build()))
                                .isError(true)
                                .build();
                    }

                    boolean isHealthy = provider.healthCheck();
                    ConnectionConfig config = provider.getConfig();
                    boolean isDefault = provider == getDefaultProvider(providers);
                    
                                        
                    ObjectNode result = mapper.createObjectNode()
                            .put("success", isHealthy)
                            .put("connection_id", connectionId)
                            .put("is_default", isDefault)
                            .put("status", isHealthy ? "connected" : "disconnected")
                            .put("database_type", config.type().toString())
                            .put("database", config.database())
                            .put("host", config.host())
                            .put("port", config.port());
                    
                    // Add error details for observability when connection fails
                    if (!isHealthy) {
                        String lastError = provider.getLastError();
                        String lastErrorType = provider.getLastErrorType();
                        String lastSqlState = provider.getLastSqlState();
                        int lastErrorCode = provider.getLastErrorCode();
                        
                        if (lastError != null) {
                            result.put("error_message", lastError);
                        }
                        if (lastErrorType != null) {
                            result.put("error_type", lastErrorType);
                        }
                        if (lastSqlState != null) {
                            result.put("sql_state", lastSqlState);
                        }
                        if (lastErrorCode != 0) {
                            result.put("error_code", lastErrorCode);
                        }
                    }
                    
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder(result.toString()).build()))
                            .isError(false)
                            .build();
                } catch (Exception e) {
                    String error = mapper.createObjectNode()
                            .put("success", false)
                            .put("error", e.getMessage())
                            .put("error_type", "HEALTH_CHECK_ERROR")
                            .toString();
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder(error).build()))
                            .isError(true)
                            .build();
                }
            }
        );
    }
    
    /**
     * Builds a smart list tables tool with intelligent defaults.
     */
    public static McpServerFeatures.SyncToolSpecification buildListTablesTool(
            Map<String, DbConnectionProvider> providers) {
        
        String defaultInfo = getDefaultInfo(providers);
        String toolName = "list_tables";
        String title = "List Tables — Database Schema Discovery";
        String description = String.format(
            "Lists all available tables in any configured database. " +
            "Use connection_id parameter to specify which database to query. " +
            "If omitted, uses default: %s. " +
            "Optional schema parameter to filter by schema. " +
            "Available connections: %s",
            defaultInfo,
            String.join(", ", providers.keySet())
        );
        
        Map<String, Object> inputSchema = objectSchema(
            Map.of(
                "connection_id", stringProperty(),
                "schema", stringProperty()
            ),
            List.of()
        );

        McpSchema.Tool tool = McpSchema.Tool.builder(toolName, inputSchema)
            .title(title)
            .description(description)
            .build();

        return new McpServerFeatures.SyncToolSpecification(
            tool,
            (exchange, request) -> {
                try {
                    DbConnectionProvider provider = getProvider(providers, request.arguments());
                    String connectionId = getConnectionId(providers, request.arguments(), provider);
                    String schema = request.arguments().containsKey("schema") ?
                            (String) request.arguments().get("schema") : null;
                    
                    if (provider == null) {
                        ObjectNode errorNode = mapper.createObjectNode();
                        errorNode.put("success", false);
                        errorNode.put("error", "Connection not found: " + request.arguments().get("connection_id"));
                        errorNode.set("available_connections", mapper.valueToTree(providers.keySet()));
                        errorNode.put("error_type", "CONNECTION_NOT_FOUND");
                        String error = errorNode.toString();
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(McpSchema.TextContent.builder(error).build()))
                                .isError(true)
                                .build();
                    }
                    
                    var tables = provider.listTables(schema);
                    boolean isDefault = provider == getDefaultProvider(providers);
                    
                    String result = mapper.createObjectNode()
                            .put("success", true)
                            .put("connection_id", connectionId)
                            .put("is_default", isDefault)
                            .set("data", mapper.valueToTree(tables))
                            .toString();
                    
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder(result).build()))
                            .isError(false)
                            .build();
                } catch (Exception e) {
                    String error = mapper.createObjectNode()
                            .put("success", false)
                            .put("error", e.getMessage())
                            .put("error_type", "QUERY_ERROR")
                            .toString();
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder(error).build()))
                            .isError(true)
                            .build();
                }
            }
        );
    }
    
    /**
     * Builds a smart describe table tool with intelligent defaults.
     */
    public static McpServerFeatures.SyncToolSpecification buildDescribeTableTool(
            Map<String, DbConnectionProvider> providers) {
        
        String defaultInfo = getDefaultInfo(providers);
        String toolName = "describe_table";
        String title = "Describe Table — Table Structure Analysis";
        String description = String.format(
            "Gets detailed table structure including columns, primary keys, foreign keys, " +
            "and extended metadata for any configured database. " +
            "Use connection_id, schema, and table parameters. " +
            "If connection_id omitted, uses default: %s. " +
            "Available connections: %s",
            defaultInfo,
            String.join(", ", providers.keySet())
        );
        
        Map<String, Object> inputSchema = objectSchema(
            Map.of(
                "connection_id", stringProperty(),
                "schema", stringProperty(),
                "table", stringProperty()
            ),
            List.of("schema", "table")
        );

        McpSchema.Tool tool = McpSchema.Tool.builder(toolName, inputSchema)
            .title(title)
            .description(description)
            .build();

        return new McpServerFeatures.SyncToolSpecification(
            tool,
            (exchange, request) -> {
                try {
                    DbConnectionProvider provider = getProvider(providers, request.arguments());
                    String connectionId = getConnectionId(providers, request.arguments(), provider);
                    String schema = (String) request.arguments().get("schema");
                    String table = (String) request.arguments().get("table");
                    
                    if (provider == null) {
                        ObjectNode errorNode = mapper.createObjectNode();
                        errorNode.put("success", false);
                        errorNode.put("error", "Connection not found: " + request.arguments().get("connection_id"));
                        errorNode.set("available_connections", mapper.valueToTree(providers.keySet()));
                        errorNode.put("error_type", "CONNECTION_NOT_FOUND");
                        String error = errorNode.toString();
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(McpSchema.TextContent.builder(error).build()))
                                .isError(true)
                                .build();
                    }

                    var metadata = provider.describeTable(schema, table);
                    boolean isDefault = provider == getDefaultProvider(providers);
                    
                    String result = mapper.createObjectNode()
                            .put("success", true)
                            .put("connection_id", connectionId)
                            .put("is_default", isDefault)
                            .set("data", mapper.valueToTree(metadata))
                            .toString();
                    
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder(result).build()))
                            .isError(false)
                            .build();
                } catch (Exception e) {
                    String error = mapper.createObjectNode()
                            .put("success", false)
                            .put("error", e.getMessage())
                            .put("error_type", "QUERY_ERROR")
                            .toString();
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder(error).build()))
                            .isError(true)
                            .build();
                }
            }
        );
    }
    
    /**
     * Builds a smart execute select tool with intelligent defaults.
     */
    public static McpServerFeatures.SyncToolSpecification buildExecuteSelectTool(
            Map<String, DbConnectionProvider> providers) {
        
        String defaultInfo = getDefaultInfo(providers);
        String toolName = "execute_select";
        String title = "Execute SELECT — Read-Only SQL Queries";
        String description = String.format(
            "Executes a read-only SELECT query against any configured database. " +
            "Use connection_id parameter to specify which database to query. " +
            "If connection_id omitted, uses default: %s. " +
            "Only SELECT statements are allowed. " +
            "Available connections: %s",
            defaultInfo,
            String.join(", ", providers.keySet())
        );
        
        Map<String, Object> inputSchema = objectSchema(
            Map.of(
                "connection_id", stringProperty(),
                "query", stringProperty()
            ),
            List.of("query")
        );

        McpSchema.Tool tool = McpSchema.Tool.builder(toolName, inputSchema)
            .title(title)
            .description(description)
            .build();

        return new McpServerFeatures.SyncToolSpecification(
            tool,
            (exchange, request) -> {
                try {
                    DbConnectionProvider provider = getProvider(providers, request.arguments());
                    String connectionId = getConnectionId(providers, request.arguments(), provider);
                    String query = (String) request.arguments().get("query");
                    
                    if (provider == null) {
                        ObjectNode errorNode = mapper.createObjectNode();
                        errorNode.put("success", false);
                        errorNode.put("error", "Connection not found: " + request.arguments().get("connection_id"));
                        errorNode.set("available_connections", mapper.valueToTree(providers.keySet()));
                        errorNode.put("error_type", "CONNECTION_NOT_FOUND");
                        String error = errorNode.toString();
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(McpSchema.TextContent.builder(error).build()))
                                .isError(true)
                                .build();
                    }

                    String result = provider.executeSelect(query);
                    ConnectionConfig config = provider.getConfig();
                    boolean isDefault = provider == getDefaultProvider(providers);
                    
                    String enhancedResult = mapper.createObjectNode()
                            .put("success", true)
                            .put("connection_id", connectionId)
                            .put("is_default", isDefault)
                            .put("database_type", config.type().toString())
                            .put("query", query)
                            .set("data", mapper.readTree(result))
                            .toString();
                    
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder(enhancedResult).build()))
                            .isError(false)
                            .build();
                } catch (Exception e) {
                    String error = mapper.createObjectNode()
                            .put("success", false)
                            .put("error", e.getMessage())
                            .put("error_type", "QUERY_ERROR")
                            .toString();
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder(error).build()))
                            .isError(true)
                            .build();
                }
            }
        );
    }
}
