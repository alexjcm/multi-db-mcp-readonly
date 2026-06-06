package io.ajcm.multidb.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.ajcm.multidb.mcp.config.ConnectionConfig;
import io.ajcm.multidb.mcp.db.DbConnectionProvider;
import java.util.Map;
import java.util.List;

/**
 * Builder for scalable MCP tools using connection_id parameter.
 * 4 generic tools: health, list_tables, describe_table, execute_select
 */
public class ScalableToolBuilder {
    private static final ObjectMapper mapper = new ObjectMapper();
    
    /**
     * Builds a generic health check tool that works with any connection.
     */
    public static McpServerFeatures.SyncToolSpecification buildHealthTool(
            Map<String, DbConnectionProvider> providers) {
        
        String toolName = "health";
        String title = "Health Check — Database Connectivity";
        String description = String.format(
            "Verifies connectivity to any configured database. " +
            "Use connection_id parameter to specify which database to check. " +
            "Available connections: %s",
            String.join(", ", providers.keySet())
        );
        
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
            "object", 
            Map.of(
                "connection_id", new McpSchema.JsonSchema("string", null, null, false, null, null)
            ), 
            List.of("connection_id"), 
            false, 
            null, 
            null
        );
        
        McpSchema.Tool tool = new McpSchema.Tool(
            toolName,
            title,
            description,
            inputSchema,
            null,
            null,
            null
        );
        
        return new McpServerFeatures.SyncToolSpecification(
            tool,
            (exchange, request) -> {
                try {
                    String connectionId = (String) request.arguments().get("connection_id");
                    DbConnectionProvider provider = providers.get(connectionId);
                    
                    if (provider == null) {
                        ObjectNode errorNode = mapper.createObjectNode();
                        errorNode.put("success", false);
                        errorNode.put("error", "Connection not found: " + connectionId);
                        errorNode.set("available_connections", mapper.valueToTree(providers.keySet()));
                        errorNode.put("error_type", "CONNECTION_NOT_FOUND");
                        String error = errorNode.toString();
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent(error)))
                                .isError(true)
                                .build();
                    }
                    
                    boolean isHealthy = provider.healthCheck();
                    ConnectionConfig config = provider.getConfig();
                    String result = mapper.createObjectNode()
                            .put("success", true)
                            .put("connection_id", connectionId)
                            .put("status", isHealthy ? "connected" : "disconnected")
                            .put("database_type", config.type().toString())
                            .put("database", config.database())
                            .put("host", config.host())
                            .put("port", config.port())
                            .toString();
                    
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(result)))
                            .isError(false)
                            .build();
                } catch (Exception e) {
                    String error = mapper.createObjectNode()
                            .put("success", false)
                            .put("error", e.getMessage())
                            .put("error_type", "HEALTH_CHECK_ERROR")
                            .toString();
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(error)))
                            .isError(true)
                            .build();
                }
            }
        );
    }
    
    /**
     * Builds a generic list tables tool that works with any connection.
     */
    public static McpServerFeatures.SyncToolSpecification buildListTablesTool(
            Map<String, DbConnectionProvider> providers) {
        
        String toolName = "list_tables";
        String title = "List Tables — Database Schema Discovery";
        String description = String.format(
            "Lists all available tables in any configured database. " +
            "Use connection_id parameter to specify which database to query. " +
            "Optional schema parameter to filter by schema. " +
            "Available connections: %s",
            String.join(", ", providers.keySet())
        );
        
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
            "object", 
            Map.of(
                "connection_id", new McpSchema.JsonSchema("string", null, null, false, null, null),
                "schema", new McpSchema.JsonSchema("string", null, null, false, null, null)
            ), 
            List.of("connection_id"), 
            false, 
            null, 
            null
        );
        
        McpSchema.Tool tool = new McpSchema.Tool(
            toolName,
            title,
            description,
            inputSchema,
            null,
            null,
            null
        );
        
        return new McpServerFeatures.SyncToolSpecification(
            tool,
            (exchange, request) -> {
                try {
                    String connectionId = (String) request.arguments().get("connection_id");
                    String schema = request.arguments().containsKey("schema") ? 
                            (String) request.arguments().get("schema") : null;
                    
                    DbConnectionProvider provider = providers.get(connectionId);
                    
                    if (provider == null) {
                        ObjectNode errorNode = mapper.createObjectNode();
                        errorNode.put("success", false);
                        errorNode.put("error", "Connection not found: " + connectionId);
                        errorNode.set("available_connections", mapper.valueToTree(providers.keySet()));
                        errorNode.put("error_type", "CONNECTION_NOT_FOUND");
                        String error = errorNode.toString();
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent(error)))
                                .isError(true)
                                .build();
                    }
                    
                    var tables = provider.listTables(schema);
                    String result = mapper.createObjectNode()
                            .put("success", true)
                            .put("connection_id", connectionId)
                            .set("data", mapper.valueToTree(tables))
                            .toString();
                    
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(result)))
                            .isError(false)
                            .build();
                } catch (Exception e) {
                    String error = mapper.createObjectNode()
                            .put("success", false)
                            .put("error", e.getMessage())
                            .put("error_type", "QUERY_ERROR")
                            .toString();
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(error)))
                            .isError(true)
                            .build();
                }
            }
        );
    }
    
    /**
     * Builds a generic describe table tool that works with any connection.
     */
    public static McpServerFeatures.SyncToolSpecification buildDescribeTableTool(
            Map<String, DbConnectionProvider> providers) {
        
        String toolName = "describe_table";
        String title = "Describe Table — Table Structure Analysis";
        String description = String.format(
            "Gets detailed table structure including columns, primary keys, foreign keys, " +
            "and extended metadata for any configured database. " +
            "Use connection_id, schema, and table parameters. " +
            "Available connections: %s",
            String.join(", ", providers.keySet())
        );
        
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
            "object", 
            Map.of(
                "connection_id", new McpSchema.JsonSchema("string", null, null, false, null, null),
                "schema", new McpSchema.JsonSchema("string", null, null, false, null, null),
                "table", new McpSchema.JsonSchema("string", null, null, false, null, null)
            ), 
            List.of("connection_id", "schema", "table"), 
            false, 
            null, 
            null
        );
        
        McpSchema.Tool tool = new McpSchema.Tool(
            toolName,
            title,
            description,
            inputSchema,
            null,
            null,
            null
        );
        
        return new McpServerFeatures.SyncToolSpecification(
            tool,
            (exchange, request) -> {
                try {
                    String connectionId = (String) request.arguments().get("connection_id");
                    String schema = (String) request.arguments().get("schema");
                    String table = (String) request.arguments().get("table");
                    
                    DbConnectionProvider provider = providers.get(connectionId);
                    
                    if (provider == null) {
                        ObjectNode errorNode = mapper.createObjectNode();
                        errorNode.put("success", false);
                        errorNode.put("error", "Connection not found: " + connectionId);
                        errorNode.set("available_connections", mapper.valueToTree(providers.keySet()));
                        errorNode.put("error_type", "CONNECTION_NOT_FOUND");
                        String error = errorNode.toString();
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent(error)))
                                .isError(true)
                                .build();
                    }
                    
                    var metadata = provider.describeTable(schema, table);
                    String result = mapper.createObjectNode()
                            .put("success", true)
                            .put("connection_id", connectionId)
                            .set("data", mapper.valueToTree(metadata))
                            .toString();
                    
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(result)))
                            .isError(false)
                            .build();
                } catch (Exception e) {
                    String error = mapper.createObjectNode()
                            .put("success", false)
                            .put("error", e.getMessage())
                            .put("error_type", "QUERY_ERROR")
                            .toString();
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(error)))
                            .isError(true)
                            .build();
                }
            }
        );
    }
    
    /**
     * Builds a generic execute select tool that works with any connection.
     */
    public static McpServerFeatures.SyncToolSpecification buildExecuteSelectTool(
            Map<String, DbConnectionProvider> providers) {
        
        String toolName = "execute_select";
        String title = "Execute SELECT — Read-Only SQL Queries";
        String description = String.format(
            "Executes a read-only SELECT query against any configured database. " +
            "Use connection_id parameter to specify which database to query. " +
            "Only SELECT statements are allowed - INSERT, UPDATE, DELETE, and DDL will be rejected. " +
            "Available connections: %s",
            String.join(", ", providers.keySet())
        );
        
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
            "object", 
            Map.of(
                "connection_id", new McpSchema.JsonSchema("string", null, null, false, null, null),
                "query", new McpSchema.JsonSchema("string", null, null, false, null, null)
            ), 
            List.of("connection_id", "query"), 
            false, 
            null, 
            null
        );
        
        McpSchema.Tool tool = new McpSchema.Tool(
            toolName,
            title,
            description,
            inputSchema,
            null,
            null,
            null
        );
        
        return new McpServerFeatures.SyncToolSpecification(
            tool,
            (exchange, request) -> {
                try {
                    String connectionId = (String) request.arguments().get("connection_id");
                    String query = (String) request.arguments().get("query");
                    
                    DbConnectionProvider provider = providers.get(connectionId);
                    
                    if (provider == null) {
                        ObjectNode errorNode = mapper.createObjectNode();
                        errorNode.put("success", false);
                        errorNode.put("error", "Connection not found: " + connectionId);
                        errorNode.set("available_connections", mapper.valueToTree(providers.keySet()));
                        errorNode.put("error_type", "CONNECTION_NOT_FOUND");
                        String error = errorNode.toString();
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent(error)))
                                .isError(true)
                                .build();
                    }
                    
                    String result = provider.executeSelect(query);
                    ConnectionConfig config = provider.getConfig();
                    String enhancedResult = mapper.createObjectNode()
                            .put("success", true)
                            .put("connection_id", connectionId)
                            .put("database_type", config.type().toString())
                            .put("query", query)
                            .set("data", mapper.readTree(result))
                            .toString();
                    
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(enhancedResult)))
                            .isError(false)
                            .build();
                } catch (Exception e) {
                    String error = mapper.createObjectNode()
                            .put("success", false)
                            .put("error", e.getMessage())
                            .put("error_type", "QUERY_ERROR")
                            .toString();
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(error)))
                            .isError(true)
                            .build();
                }
            }
        );
    }
}
