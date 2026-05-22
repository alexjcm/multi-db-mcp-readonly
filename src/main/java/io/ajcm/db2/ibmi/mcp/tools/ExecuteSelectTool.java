package io.ajcm.db2.ibmi.mcp.tools;

import static io.ajcm.db2.ibmi.mcp.util.McpNotifier.sendNotification;
import static io.ajcm.db2.ibmi.mcp.util.McpNotifier.resolveProgressToken;
import static io.ajcm.db2.ibmi.mcp.util.McpNotifier.sendLog;
import static io.ajcm.db2.ibmi.mcp.util.McpNotifier.startProgressPinger;

import io.ajcm.db2.ibmi.mcp.db.DbClient;
import io.ajcm.db2.ibmi.mcp.db.DbClientSingleton;
import io.ajcm.db2.ibmi.mcp.util.KeySet;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.ajcm.db2.ibmi.mcp.util.ResourceUtils;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;
import java.sql.SQLTimeoutException;
import java.sql.SQLException;

/**
 * MCP tool that executes read-only SQL queries.
 */
public class ExecuteSelectTool {
    private static final Logger log = LoggerFactory.getLogger(ExecuteSelectTool.class);
    // Configuration defaults moved to KeySet
    private ExecuteSelectTool() {}

    /**
     * Builds the synchronous tool specification for execute_select.
     * 
     * @return tool specification
     */
    public static SyncToolSpecification create() {
        String inputSchema = ResourceUtils.readClasspathResourceAsString("schemas/execute_select.input.json");
        String outputSchema = ResourceUtils.readClasspathResourceAsString("schemas/execute_select.output.json");
        McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
        Tool tool = Tool.builder()
                .name("execute_select")
                .title("Execute Select")
                .description("Execute validated SELECT queries with enforced limits (default " + KeySet.SELECT_DEFAULT_ROW_LIMIT + " rows)")
                .inputSchema(jsonMapper, inputSchema)
                .outputSchema(jsonMapper, outputSchema)
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(ExecuteSelectTool::handle)
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
            Object raw = arguments.get("sql");
            if (!(raw instanceof String sqlRaw) || sqlRaw.isBlank()) {
                long elapsed = System.currentTimeMillis() - start;
                return CallToolResult.builder()
                        .structuredContent(Map.of(
                                "columns", List.of(),
                                "rows", List.of(),
                                "rowCount", 0,
                                "elapsedMs", elapsed,
                                "connectionId", DbClientSingleton.resolveConnectionId(connectionId),
                                "error", Map.of(
                                        "code", KeySet.ERR_VALIDATION_ERROR,
                                        "message", "Validation error: 'sql' is required"
                                )
                        ))
                        .isError(true)
                        .build();
            }
            String sql = sqlRaw.trim();
            
            Object connArg = arguments.get("connectionId");
            if (connArg instanceof String s && !s.isBlank()) {
                String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
                connectionId = n.toUpperCase(java.util.Locale.ROOT);
                if (!DbClientSingleton.has(connectionId)) {
                    long elapsed = System.currentTimeMillis() - start;
                    return CallToolResult.builder()
                            .structuredContent(Map.of(
                                    "columns", List.of(),
                                    "rows", List.of(),
                                    "rowCount", 0,
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
            log.info("execute_select: using connectionId={}", connectionId == null ? "<default>" : connectionId);
            sendNotification(exchange, 10, 100, "Connecting to DB2 [" + effectiveId + "]", progressToken, McpSchema.LoggingLevel.INFO, "execute_select");
            // Be fully permissive on schema: skip ensureSchema by passing blank
            String requiredSchema = "";
            String upper = sql.toUpperCase(Locale.ROOT);
            boolean qualified = Pattern.compile("\\b[A-Z][A-Z0-9_]*\\.[A-Z][A-Z0-9_]*\\b").matcher(upper).find();
            if (!qualified) {
                log.info("select_rows: unqualified SQL detected; using connection libraries");
            }
            sendNotification(exchange, 30, 100, "Executing query...", progressToken, McpSchema.LoggingLevel.INFO, "execute_select");
            DbClient.SelectResult result = selectRowsWithProgress(db, sql, requiredSchema, exchange, progressToken);
            List<?> rows = result.rows() == null ? List.of() : result.rows();
            List<?> cols = result.columns() == null ? List.of() : result.columns();
            sendNotification(exchange, 100, 100, "Done — " + result.rowCount() + " rows in " + result.elapsedMs() + "ms", progressToken, McpSchema.LoggingLevel.INFO, "execute_select");
            return CallToolResult.builder()
                    .structuredContent(Map.of(
                            "columns", cols,
                            "rows", rows,
                            "rowCount", result.rowCount(),
                            "elapsedMs", result.elapsedMs(),
                            "connectionId", effectiveId
                    ))
                    .isError(false)
                    .build();
        } catch (IllegalArgumentException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Validation failed: select_rows, error: {}", e.getMessage());
            sendLog(exchange, McpSchema.LoggingLevel.WARNING, "execute_select", "Validation error: " + e.getMessage());
            return CallToolResult.builder()
                    .structuredContent(Map.of(
                            "columns", List.of(),
                            "rows", List.of(),
                            "rowCount", 0,
                            "elapsedMs", elapsed,
                            "connectionId", (effectiveId == null ? DbClientSingleton.resolveConnectionId(connectionId) : effectiveId),
                            "error", Map.of(
                                    "code", KeySet.ERR_VALIDATION_ERROR,
                                    "message", "Validation error: " + e.getMessage()
                            )
                    ))
                    .isError(true)
                    .build();
        } catch (SQLTimeoutException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Tool failed (timeout): select_rows, error: {}", e.getMessage());
            sendLog(exchange, McpSchema.LoggingLevel.ERROR, "execute_select", "The query exceeded the configured timeout.");
            return CallToolResult.builder()
                    .structuredContent(Map.of(
                            "columns", List.of(),
                            "rows", List.of(),
                            "rowCount", 0,
                            "elapsedMs", elapsed,
                            "connectionId", (effectiveId == null ? DbClientSingleton.resolveConnectionId(connectionId) : effectiveId),
                            "error", Map.of(
                                    "code", KeySet.ERR_QUERY_TIMEOUT,
                                    "message", "The query exceeded the configured timeout."
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
            log.error("Tool failed (sql): select_rows, state={}, msg={}", sqlState, e.getMessage());
            return CallToolResult.builder()
                    .structuredContent(Map.of(
                            "columns", List.of(),
                            "rows", List.of(),
                            "rowCount", 0,
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
            log.error("Tool failed: select_rows, error: {}", e.getMessage(), e);
            return CallToolResult.builder()
                    .structuredContent(Map.of(
                            "columns", List.of(),
                            "rows", List.of(),
                            "rowCount", 0,
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
     * Executes the select query on the database while sending periodic progress notifications to the MCP client.
     * 
     * @param db The database client wrapper
     * @param sql The SQL query to execute
     * @param requiredSchema The required schema to enforce (if any)
     * @param exchange The MCP exchange context
     * @param progressToken The token identifying the progress request
     * @return The query execution result
     */
    private static DbClient.SelectResult selectRowsWithProgress(DbClient db, String sql, String requiredSchema,
            McpSyncServerExchange exchange, String progressToken) throws SQLException {
        DbClient.SelectResult result;
        AutoCloseable pinger = startProgressPinger(exchange, progressToken, "execute_select", 10, 95, 3000);
        try {
            result = db.selectRows(
                    sql,
                    KeySet.SELECT_DEFAULT_TIMEOUT_SECONDS,
                    KeySet.SELECT_DEFAULT_ROW_LIMIT,
                    requiredSchema
            );
        } finally {
            try {
                pinger.close();
            } catch (Exception e) {
                log.warn("Failed to close progress pinger: {}", e.getMessage());
            }
        }
        return result;
    }
}
