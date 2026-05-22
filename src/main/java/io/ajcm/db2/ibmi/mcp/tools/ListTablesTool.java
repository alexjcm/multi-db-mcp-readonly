package io.ajcm.db2.ibmi.mcp.tools;

import static io.ajcm.db2.ibmi.mcp.util.McpNotifier.sendNotification;
import static io.ajcm.db2.ibmi.mcp.util.McpNotifier.resolveProgressToken;
import static io.ajcm.db2.ibmi.mcp.util.McpNotifier.sendLog;
import static io.ajcm.db2.ibmi.mcp.util.McpNotifier.startProgressPinger;

import io.ajcm.db2.ibmi.mcp.db.DbClient;
import io.ajcm.db2.ibmi.mcp.db.DbClientSingleton;
import io.ajcm.db2.ibmi.mcp.util.KeySet;
import io.ajcm.db2.ibmi.mcp.util.ResourceUtils;
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

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.sql.SQLTimeoutException;
import java.sql.SQLException;

/**
 * MCP tool that lists available database tables and views.
 */
public class ListTablesTool {
    private static final Logger log = LoggerFactory.getLogger(ListTablesTool.class);

    private ListTablesTool() {
    }

    /**
     * Builds the synchronous tool specification for list_tables.
     * 
     * @return tool specification
     */
    public static SyncToolSpecification create() {
        String inputSchema = ResourceUtils.readClasspathResourceAsString("schemas/list_tables.input.json");
        McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
        String outputSchema = ResourceUtils.readClasspathResourceAsString("schemas/list_tables.output.json");
        Tool tool = Tool.builder()
                .name("list_tables")
                .title("List Tables")
                .description("Retrieve a list of tables and views in the allowed schema")
                .inputSchema(jsonMapper, inputSchema)
                .outputSchema(jsonMapper, outputSchema)
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(ListTablesTool::handle)
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
            String searchPattern = null;
            Object raw = arguments.get("searchPattern");
            if (raw instanceof String s && !s.isBlank()) {
                searchPattern = s.trim();
            }

            Object connArg = arguments.get("connectionId");
            if (connArg instanceof String s && !s.isBlank()) {
                String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
                connectionId = n.toUpperCase(Locale.ROOT);
                if (!DbClientSingleton.has(connectionId)) {
                    long elapsed = System.currentTimeMillis() - start;
                    return CallToolResult.builder()
                            .structuredContent(Map.of(
                                    "tables", List.of(),
                                    "elapsedMs", elapsed,
                                    "connectionId", connectionId,
                                    "error", Map.of(
                                            "code", KeySet.ERR_VALIDATION_ERROR,
                                            "message", "Unknown or invalid connectionId: " + connectionId)))
                            .isError(true)
                            .build();
                }
            }
            effectiveId = DbClientSingleton.resolveConnectionId(connectionId);
            DbClient db = (connectionId == null) ? DbClientSingleton.get() : DbClientSingleton.get(connectionId);
            log.info("list_tables: using connectionId={}", connectionId == null ? "<default>" : connectionId);
            sendNotification(exchange, 10, 100, "Connecting to DB2 [" + effectiveId + "]", progressToken,
                    McpSchema.LoggingLevel.INFO, "list_tables");

            sendNotification(exchange, 10, 100, "Retrieving tables and views metadata...", progressToken,
                    McpSchema.LoggingLevel.INFO, "list_tables");
            List<Map<String, Object>> tables = fetchTablesWithProgress(db, searchPattern, exchange, progressToken);
            long elapsed = System.currentTimeMillis() - start;
            sendNotification(exchange, 100, 100, "Done — found " + tables.size() + " tables in " + elapsed + "ms",
                    progressToken, McpSchema.LoggingLevel.INFO, "list_tables");

            return CallToolResult.builder()
                    .structuredContent(Map.of(
                            "tables", tables,
                            "elapsedMs", elapsed,
                            "connectionId", effectiveId))
                    .isError(false)
                    .build();
        } catch (SQLTimeoutException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Tool failed (timeout): list_tables, error: {}", e.getMessage());
            sendLog(exchange, McpSchema.LoggingLevel.ERROR, "list_tables",
                    "The query exceeded the configured timeout.");
            return CallToolResult.builder()
                    .structuredContent(Map.of(
                            "tables", List.of(),
                            "elapsedMs", elapsed,
                            "connectionId",
                            (effectiveId == null ? DbClientSingleton.resolveConnectionId(connectionId) : effectiveId),
                            "error", Map.of(
                                    "code", KeySet.ERR_QUERY_TIMEOUT,
                                    "message", "The request exceeded the configured timeout.")))
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
            log.error("Tool failed (sql): list_tables, state={}, msg={}", sqlState, e.getMessage());
            return CallToolResult.builder()
                    .structuredContent(Map.of(
                            "tables", List.of(),
                            "elapsedMs", elapsed,
                            "connectionId",
                            (effectiveId == null ? DbClientSingleton.resolveConnectionId(connectionId) : effectiveId),
                            "error", Map.of(
                                    "code", code,
                                    "message", e.getMessage() == null ? code : e.getMessage())))
                    .isError(true)
                    .build();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Tool failed: list_tables, error: {}", e.getMessage(), e);
            return CallToolResult.builder()
                    .structuredContent(Map.of(
                            "tables", List.of(),
                            "elapsedMs", elapsed,
                            "connectionId",
                            (effectiveId == null ? DbClientSingleton.resolveConnectionId(connectionId) : effectiveId),
                            "error", Map.of(
                                    "code", KeySet.ERR_INTERNAL_ERROR,
                                    "message", e.getMessage() == null ? "Internal error" : e.getMessage())))
                    .isError(true)
                    .build();
        }
    }

    /**
     * Fetches tables from the database while sending periodic progress
     * notifications to the MCP client.
     * 
     * @param db            The database client wrapper
     * @param searchPattern The search pattern to filter tables
     * @param exchange      The MCP exchange context
     * @param progressToken The token identifying the progress request
     * @return List of tables matching the pattern
     */
    private static List<Map<String, Object>> fetchTablesWithProgress(DbClient db, String searchPattern,
            McpSyncServerExchange exchange, String progressToken) throws SQLException {
        List<Map<String, Object>> tables;
        AutoCloseable pinger = startProgressPinger(exchange,
                progressToken, "list_tables", 10, 95, 3000);
        try {
            tables = db.getTables(searchPattern);
        } finally {
            try {
                pinger.close();
            } catch (Exception e) {
                log.warn("Failed to close progress pinger: {}", e.getMessage());
            }
        }
        return tables;
    }
}
