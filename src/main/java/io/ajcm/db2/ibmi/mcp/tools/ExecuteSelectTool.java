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
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.ajcm.db2.ibmi.mcp.util.ResourceUtils;

import java.text.Normalizer;
import java.util.LinkedHashMap;
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
                .description("Execute validated read-only SELECT queries and return a limited preview. If hasMore is true, refine the query or increase limit instead of expecting pagination.")
                .inputSchema(jsonMapper, inputSchema)
                .outputSchema(jsonMapper, outputSchema)
                .annotations(new ToolAnnotations(null, true, false, true, true, null))
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
        Map<String, Object> arguments = req.arguments() == null ? Map.of() : req.arguments();
        long start = System.currentTimeMillis();
        String connectionId = null;
        String effectiveId = null;
        try {
            Object raw = arguments.get("sql");
            int requestedLimit = normalizeLimit(arguments.get("limit"));
            if (!(raw instanceof String sqlRaw) || sqlRaw.isBlank()) {
                long elapsed = System.currentTimeMillis() - start;
                return CallToolResult.builder()
                        .structuredContent(errorResponse(List.of(), List.of(), 0, elapsed,
                                DbClientSingleton.resolveConnectionId(connectionId), requestedLimit, false, false,
                                KeySet.ERR_VALIDATION_ERROR, "Validation error: 'sql' is required"))
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
                            .structuredContent(errorResponse(List.of(), List.of(), 0, elapsed, connectionId,
                                    requestedLimit, false, false, KeySet.ERR_VALIDATION_ERROR,
                                    "Unknown or invalid connectionId: " + connectionId))
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
            DbClient.SelectResult result = selectRowsWithProgress(db, sql, requestedLimit, requiredSchema, exchange, progressToken);
            List<?> rows = result.rows() == null ? List.of() : result.rows();
            List<?> cols = result.columns() == null ? List.of() : result.columns();
            sendNotification(exchange, 100, 100,
                    "Done — " + result.rowCount() + " rows in " + result.elapsedMs() + "ms"
                            + (result.hasMore() ? " (preview truncated)" : ""),
                    progressToken, McpSchema.LoggingLevel.INFO, "execute_select");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("columns", cols);
            response.put("rows", rows);
            response.put("rowCount", result.rowCount());
            response.put("appliedLimit", result.appliedLimit());
            response.put("hasMore", result.hasMore());
            response.put("truncated", result.truncated());
            response.put("elapsedMs", result.elapsedMs());
            response.put("connectionId", effectiveId);
            return CallToolResult.builder()
                    .content(List.of(new TextContent(successSummary(effectiveId, result))))
                    .structuredContent(response)
                    .isError(false)
                    .build();
        } catch (IllegalArgumentException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Validation failed: select_rows, error: {}", e.getMessage());
            sendLog(exchange, McpSchema.LoggingLevel.WARNING, "execute_select", "Validation error: " + e.getMessage());
            return CallToolResult.builder()
                    .structuredContent(errorResponse(List.of(), List.of(), 0, elapsed,
                            (effectiveId == null ? DbClientSingleton.resolveConnectionId(connectionId) : effectiveId),
                            normalizeLimit(arguments.get("limit")), false, false, KeySet.ERR_VALIDATION_ERROR,
                            "Validation error: " + e.getMessage()))
                    .isError(true)
                    .build();
        } catch (SQLTimeoutException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Tool failed (timeout): select_rows, error: {}", e.getMessage());
            sendLog(exchange, McpSchema.LoggingLevel.ERROR, "execute_select", "The query exceeded the configured timeout.");
            return CallToolResult.builder()
                    .structuredContent(errorResponse(List.of(), List.of(), 0, elapsed,
                            (effectiveId == null ? DbClientSingleton.resolveConnectionId(connectionId) : effectiveId),
                            normalizeLimit(arguments.get("limit")), false, false, KeySet.ERR_QUERY_TIMEOUT,
                            "The query exceeded the configured timeout."))
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
                    .structuredContent(errorResponse(List.of(), List.of(), 0, elapsed,
                            (effectiveId == null ? DbClientSingleton.resolveConnectionId(connectionId) : effectiveId),
                            normalizeLimit(arguments.get("limit")), false, false, code,
                            e.getMessage() == null ? code : e.getMessage()))
                    .isError(true)
                    .build();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Tool failed: select_rows, error: {}", e.getMessage(), e);
            return CallToolResult.builder()
                    .structuredContent(errorResponse(List.of(), List.of(), 0, elapsed,
                            (effectiveId == null ? DbClientSingleton.resolveConnectionId(connectionId) : effectiveId),
                            normalizeLimit(arguments.get("limit")), false, false, KeySet.ERR_INTERNAL_ERROR,
                            e.getMessage() == null ? "Internal error" : e.getMessage()))
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
    private static DbClient.SelectResult selectRowsWithProgress(DbClient db, String sql, int limit, String requiredSchema,
            McpSyncServerExchange exchange, String progressToken) throws SQLException {
        DbClient.SelectResult result;
        AutoCloseable pinger = startProgressPinger(exchange, progressToken, "execute_select", 10, 95, 3000);
        try {
            result = db.selectRows(
                    sql,
                    KeySet.SELECT_DEFAULT_TIMEOUT_SECONDS,
                    limit,
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

    static int normalizeLimit(Object rawLimit) {
        if (rawLimit instanceof Number n) {
            return Math.max(1, Math.min(KeySet.SELECT_MAX_ROW_LIMIT, n.intValue()));
        }
        if (rawLimit instanceof String s) {
            try {
                return Math.max(1, Math.min(KeySet.SELECT_MAX_ROW_LIMIT, Integer.parseInt(s.trim())));
            } catch (NumberFormatException ignored) {
                return KeySet.SELECT_DEFAULT_ROW_LIMIT;
            }
        }
        return KeySet.SELECT_DEFAULT_ROW_LIMIT;
    }

    private static String successSummary(String effectiveId, DbClient.SelectResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Returned ").append(result.rowCount()).append(" row(s) from connection ").append(effectiveId);
        sb.append(" with appliedLimit ").append(result.appliedLimit()).append(".");
        if (result.hasMore()) {
            sb.append(" Result truncated; refine the query or increase limit if you need more rows.");
        }
        return sb.toString();
    }

    private static Map<String, Object> errorResponse(List<?> columns, List<?> rows, int rowCount, long elapsedMs,
            String connectionId, int appliedLimit, boolean hasMore, boolean truncated, String code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("columns", columns);
        response.put("rows", rows);
        response.put("rowCount", rowCount);
        response.put("appliedLimit", appliedLimit);
        response.put("hasMore", hasMore);
        response.put("truncated", truncated);
        response.put("elapsedMs", elapsedMs);
        response.put("connectionId", connectionId);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }
}
