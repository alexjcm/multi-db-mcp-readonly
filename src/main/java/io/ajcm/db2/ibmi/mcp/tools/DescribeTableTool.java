package io.ajcm.db2.ibmi.mcp.tools;

import static io.ajcm.db2.ibmi.mcp.util.McpNotifier.sendNotification;
import static io.ajcm.db2.ibmi.mcp.util.McpNotifier.resolveProgressToken;
import static io.ajcm.db2.ibmi.mcp.util.McpNotifier.sendLog;
import static io.ajcm.db2.ibmi.mcp.util.McpNotifier.startProgressPinger;

import io.modelcontextprotocol.spec.McpSchema;
import io.ajcm.db2.ibmi.mcp.db.DbClient;
import io.ajcm.db2.ibmi.mcp.db.DbClientSingleton;
import io.ajcm.db2.ibmi.mcp.util.KeySet;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.ajcm.db2.ibmi.mcp.util.ResourceUtils;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.sql.SQLTimeoutException;
import java.sql.SQLException;

/**
 * MCP tool that returns metadata for a DB2 for i table.
 */
public class DescribeTableTool {
    private static final Logger log = LoggerFactory.getLogger(DescribeTableTool.class);
    private DescribeTableTool() {}

    /**
     * Builds the synchronous tool specification for describe_table.
     */
    /**
     * Builds the synchronous tool specification for describe_table.
     * 
     * @return tool specification
     */
    public static SyncToolSpecification create() {
        // Sync tool specification
        String inputSchema = ResourceUtils.readClasspathResourceAsString("schemas/describe_table.input.json");
        McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
        String outputSchema = ResourceUtils.readClasspathResourceAsString("schemas/describe_table.output.json");
        Tool tool = Tool.builder()
                .name("describe_table")
                .title("Describe Table")
                .description("Retrieve column metadata for a table in the allowed schema")
                .inputSchema(jsonMapper, inputSchema)
                .outputSchema(jsonMapper, outputSchema)
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(DescribeTableTool::handle)
                .build();
    }

    /**
     * Handles the tool execution request.
     * 
     * @param exchange the MCP exchange context
     * @param req the tool call request
     * @return the tool call result
     */
    private static CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest req) {
        String progressToken = resolveProgressToken(req);
        Map<String, Object> arguments = req.arguments();
        long start = System.currentTimeMillis();
        String connectionId = null;
        String effectiveId = null;
        try {
            Object raw = arguments.get("tableName");
            if (!(raw instanceof String tableNameRaw) || tableNameRaw.isBlank()) {
                long elapsed = System.currentTimeMillis() - start;
                return CallToolResult.builder()
                        .structuredContent(Map.of(
                                "columns", List.of(),
                                "primaryKey", null,
                                "elapsedMs", elapsed,
                                "connectionId", DbClientSingleton.resolveConnectionId(connectionId),
                                "error", Map.of(
                                        "code", KeySet.ERR_VALIDATION_ERROR,
                                        "message", "Validation error: 'tableName' is required"
                                )
                        ))
                        .isError(true)
                        .build();
            }
            String tableName = tableNameRaw.trim().toUpperCase(Locale.ROOT);

            Object connArg = arguments.get("connectionId");
            if (connArg instanceof String s && !s.isBlank()) {
                String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
                connectionId = n.toUpperCase(java.util.Locale.ROOT);
                if (!DbClientSingleton.has(connectionId)) {
                    long elapsed = System.currentTimeMillis() - start;
                    return CallToolResult.builder()
                            .structuredContent(Map.of(
                                    "columns", List.of(),
                                    "primaryKey", null,
                                    "elapsedMs", elapsed,
                                    "connectionId", connectionId,
                                    "error", Map.of(
                                            "code", KeySet.ERR_VALIDATION_ERROR,
                                            "message", "Unknown or invalid connectionId: " + connectionId
                                    )
                            ))
                            .isError(true)
                            .build();
                }
            }
            effectiveId = DbClientSingleton.resolveConnectionId(connectionId);
            DbClient db = (connectionId == null) ? DbClientSingleton.get() : DbClientSingleton.get(connectionId);
            log.info("describe_table: using connectionId={}", connectionId == null ? "<default>" : connectionId);
            sendNotification(exchange, 10, 100, "Connecting to DB2 [" + effectiveId + "]", progressToken, McpSchema.LoggingLevel.INFO, "describe_table");
            
            boolean includeFks = false;
            Object fko = arguments.get("includeForeignKeys");
            if (fko instanceof Boolean b2) includeFks = b2;

            sendNotification(exchange, 30, 100, "Retrieving table schema...", progressToken, McpSchema.LoggingLevel.INFO, "describe_table");
            Map<String, Object> resp = describeTableWithProgress(db, tableName, includeFks, exchange, progressToken);

            long elapsed = System.currentTimeMillis() - start;
            resp.put("elapsedMs", elapsed);
            resp.put("connectionId", effectiveId);

            return CallToolResult.builder()
                    .structuredContent(resp)
                    .isError(false)
                    .build();
        } catch (SQLTimeoutException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Tool failed (timeout): describe_table, error: {}", e.getMessage());
            return CallToolResult.builder()
                    .structuredContent(Map.of(
                            "columns", List.of(),
                            "primaryKey", null,
                            "elapsedMs", elapsed,
                            "connectionId", (effectiveId == null ? DbClientSingleton.resolveConnectionId(connectionId) : effectiveId),
                            "error", Map.of(
                                    "code", KeySet.ERR_QUERY_TIMEOUT,
                                    "message", "The request exceeded the configured timeout."
                            )
                    ))
                    .isError(true)
                    .build();
        } catch (SQLException e) {
            long elapsed = System.currentTimeMillis() - start;
            String sqlState = e.getSQLState();
            String code;
            if ("28000".equals(sqlState) || "08004".equals(sqlState)) {
                code = KeySet.ERR_AUTH_FAILED;
            } else if ("08001".equals(sqlState)) {
                code = KeySet.ERR_NET_UNREACHABLE;
            } else {
                code = KeySet.ERR_SQL_ERROR;
            }
            log.error("Tool failed (sql): describe_table, state={}, msg={}", sqlState, e.getMessage());
            return CallToolResult.builder()
                    .structuredContent(Map.of(
                            "columns", List.of(),
                            "primaryKey", null,
                            "elapsedMs", elapsed,
                            "connectionId", (effectiveId == null ? DbClientSingleton.resolveConnectionId(connectionId) : effectiveId),
                            "error", Map.of(
                                    "code", code,
                                    "message", e.getMessage() == null ? code : e.getMessage()
                            )
                    ))
                    .isError(true)
                    .build();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Tool failed: describe_table, error: {}", e.getMessage(), e);
            sendLog(exchange, McpSchema.LoggingLevel.ERROR, "describe_table", "Internal error: " + e.getMessage());
            return CallToolResult.builder()
                    .structuredContent(Map.of(
                            "columns", List.of(),
                            "primaryKey", null,
                            "elapsedMs", elapsed,
                            "connectionId", (effectiveId == null ? DbClientSingleton.resolveConnectionId(connectionId) : effectiveId),
                            "error", Map.of(
                                    "code", KeySet.ERR_INTERNAL_ERROR,
                                    "message", e.getMessage() == null ? "Internal error" : e.getMessage()
                            )
                    ))
                    .isError(true)
                    .build();
        }
    }

    /**
     * Fetches table metadata (columns, primary key, foreign keys) with periodic progress notifications.
     * 
     * @param db The database client wrapper
     * @param tableName The name of the table to describe
     * @param includeFks Whether to include foreign keys lookup
     * @param exchange The MCP exchange context
     * @param progressToken The token identifying the progress request
     * @return Map containing columns, primary key, and optionally foreign keys
     */
    private static Map<String, Object> describeTableWithProgress(DbClient db, String tableName, boolean includeFks,
            McpSyncServerExchange exchange, String progressToken) throws java.sql.SQLException {
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        AutoCloseable pinger = startProgressPinger(exchange, progressToken, "describe_table", 10, 95, 3000);
        try {
            resp.put("columns", db.getColumns(tableName));
            resp.put("primaryKey", db.getPrimaryKey(tableName));
            if (includeFks) {
                List<Map<String, Object>> fks = db.getForeignKeys(tableName);
                resp.put("foreignKeys", fks == null ? List.of() : fks);
            }
        } finally {
            try {
                pinger.close();
            } catch (Exception e) {
                log.warn("Failed to close progress pinger: {}", e.getMessage());
            }
        }
        return resp;
    }
}
