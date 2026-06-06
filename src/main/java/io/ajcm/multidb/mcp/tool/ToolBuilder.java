package io.ajcm.multidb.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.ajcm.multidb.mcp.config.ConnectionConfig;
import io.ajcm.multidb.mcp.db.DbConnectionProvider;
import java.util.Map;
import java.util.List;

/**
 * Builder for MCP tools with auto-generated descriptions based on connection config.
 * No registry or complex factory - just static methods for clean separation from Main.java.
 */
public class ToolBuilder {
    private static final ObjectMapper mapper = new ObjectMapper();
    
    /**
     * Builds a health check tool for a specific database connection.
     */
    public static McpServerFeatures.SyncToolSpecification buildHealthTool(
            String connectionId, 
            ConnectionConfig config, 
            DbConnectionProvider provider) {
        
        String toolName = "health_" + connectionId;
        String title = String.format("Health Check — %s: %s", config.type().getDisplayName(), connectionId);
        String description = String.format(
            "Verifies connectivity to the %s database '%s' on %s. " +
            "Use this tool before running critical queries to ensure the database is accessible.",
            config.type().getDisplayName(), config.database(), config.host()
        );
        
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
            "object", 
            Map.of(), 
            List.of(), 
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
                    boolean healthy = provider.healthCheck();
                    String result = mapper.createObjectNode()
                            .put("success", true)
                            .set("data", mapper.createObjectNode()
                                    .put("status", healthy ? "ok" : "error")
                                    .put("db_type", config.type().name())
                                    .put("host", config.host())
                                    .put("database", config.database())
                                    .put("connection_id", connectionId))
                            .toString();
                    
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(result)))
                            .isError(false)
                            .build();
                } catch (Exception e) {
                    String error = mapper.createObjectNode()
                            .put("success", false)
                            .put("error", e.getMessage())
                            .put("error_type", "CONNECTION_ERROR")
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
     * Builds a list tables tool for a specific database connection.
     */
    public static McpServerFeatures.SyncToolSpecification buildListTablesTool(
            String connectionId, 
            ConnectionConfig config, 
            DbConnectionProvider provider) {
        
        String toolName = "list_tables_" + connectionId;
        String title = String.format("List Tables — %s: %s", config.type().getDisplayName(), connectionId);
        String description = String.format(
            "Lists all available tables in the %s database '%s' on %s. " +
            "Use this tool first to discover what tables exist before querying. " +
            "Follow up with describe_table_%s to get column structure.",
            config.type().getDisplayName(), config.database(), config.host(), connectionId
        );
        
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
            "object", 
            Map.of(
                "schema", new McpSchema.JsonSchema("string", null, null, false, null, null)
            ), 
            List.of(), 
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
                    String schema = request.arguments().containsKey("schema") ? 
                            (String) request.arguments().get("schema") : null;
                    
                    var tables = provider.listTables(schema);
                    String result = mapper.createObjectNode()
                            .put("success", true)
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
     * Builds a describe table tool for a specific database connection.
     */
    public static McpServerFeatures.SyncToolSpecification buildDescribeTableTool(
            String connectionId, 
            ConnectionConfig config, 
            DbConnectionProvider provider) {
        
        String toolName = "describe_table_" + connectionId;
        String title = String.format("Describe Table — %s: %s", config.type().getDisplayName(), connectionId);
        String description = String.format(
            "Gets detailed table structure including columns, primary keys, foreign keys, " +
            "and extended metadata for the %s database '%s' on %s. " +
            "Use this tool to understand table structure before writing SELECT queries.",
            config.type().getDisplayName(), config.database(), config.host()
        );
        
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
            "object", 
            Map.of(
                "schema", new McpSchema.JsonSchema("string", null, null, false, null, null),
                "table", new McpSchema.JsonSchema("string", null, null, false, null, null)
            ), 
            List.of("schema", "table"), 
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
                    String schema = (String) request.arguments().get("schema");
                    String table = (String) request.arguments().get("table");
                    
                    var metadata = provider.describeTable(schema, table);
                    String result = mapper.createObjectNode()
                            .put("success", true)
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
     * Builds an execute select tool for a specific database connection.
     */
    public static McpServerFeatures.SyncToolSpecification buildExecuteSelectTool(
            String connectionId, 
            ConnectionConfig config, 
            DbConnectionProvider provider) {
        
        String toolName = "execute_select_" + connectionId;
        String title = String.format("Execute SELECT — %s: %s", config.type().getDisplayName(), connectionId);
        String description = String.format(
            "Executes a read-only SELECT query against the %s database '%s' on %s. " +
            "Supports %s SQL syntax. Only SELECT statements are allowed - " +
            "INSERT, UPDATE, DELETE, and DDL will be rejected.",
            config.type().getDisplayName(), config.database(), config.host(), config.type().getDisplayName()
        );
        
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
            "object", 
            Map.of(
                "query", new McpSchema.JsonSchema("string", null, null, false, null, null)
            ), 
            List.of("query"), 
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
                    String query = (String) request.arguments().get("query");
                    String result = provider.executeSelect(query);
                    
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
}
