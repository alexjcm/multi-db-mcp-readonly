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
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Base64;
import java.util.LinkedHashMap;
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
                .description("Retrieve a paginated list of tables and views in the allowed schema. If hasMore is true, call this tool again with cursor=nextCursor to continue browsing.")
                .inputSchema(jsonMapper, inputSchema)
                .outputSchema(jsonMapper, outputSchema)
                .annotations(new ToolAnnotations(null, true, false, true, true, null))
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
        Map<String, Object> arguments = req.arguments() == null ? Map.of() : req.arguments();
        long start = System.currentTimeMillis();
        String connectionId = null;
        String effectiveId = null;
        try {
            String searchPattern = null;
            Object raw = arguments.get("searchPattern");
            if (raw instanceof String s && !s.isBlank()) {
                searchPattern = s.trim();
            }
            int limit = normalizeLimit(arguments.get("limit"));
            String cursorToken = null;
            Object cursorRaw = arguments.get("cursor");
            if (cursorRaw instanceof String s && !s.isBlank()) {
                cursorToken = s.trim();
            }

            Object connArg = arguments.get("connectionId");
            if (connArg instanceof String s && !s.isBlank()) {
                String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
                connectionId = n.toUpperCase(Locale.ROOT);
                if (!DbClientSingleton.has(connectionId)) {
                    long elapsed = System.currentTimeMillis() - start;
                    return CallToolResult.builder()
                            .structuredContent(errorResponse(List.of(), connectionId, connectionId, elapsed,
                                    normalizeLimit(arguments.get("limit")), 0L, false, KeySet.ERR_VALIDATION_ERROR,
                                    "Unknown or invalid connectionId: " + connectionId))
                            .isError(true)
                            .build();
                }
            }
            effectiveId = DbClientSingleton.resolveConnectionId(connectionId);
            DbClient db = (connectionId == null) ? DbClientSingleton.get() : DbClientSingleton.get(connectionId);
            log.info("list_tables: using connectionId={}", connectionId == null ? "<default>" : connectionId);

            DecodedCursor decodedCursor = decodeCursorToken(cursorToken);
            if (decodedCursor != null && !effectiveId.equals(decodedCursor.connectionId())) {
                throw new IllegalArgumentException(
                        "Cursor does not belong to the selected connectionId. Reuse the same connection or restart without cursor.");
            }

            sendNotification(exchange, 10, 100, "Connecting to DB2 [" + effectiveId + "]", progressToken,
                    McpSchema.LoggingLevel.INFO, "list_tables");

            sendNotification(exchange, 30, 100, "Retrieving tables and views metadata...", progressToken,
                    McpSchema.LoggingLevel.INFO, "list_tables");
            DbClient.TablesPage page = fetchTablesWithProgress(db, searchPattern, limit,
                    decodedCursor == null ? null : decodedCursor.pageCursor(),
                    decodedCursor == null ? null : decodedCursor.totalCount(),
                    exchange, progressToken);
            long elapsed = System.currentTimeMillis() - start;
            String nextCursor = page.nextCursor() == null ? null
                    : encodeCursorToken(effectiveId, page.nextCursor(), page.totalCount());
            sendNotification(exchange, 100, 100,
                    "Done — returned " + page.tables().size() + " tables in " + elapsed + "ms"
                            + (page.hasMore() ? " (more pages available)" : ""),
                    progressToken, McpSchema.LoggingLevel.INFO, "list_tables");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("tables", page.tables());
            response.put("count", page.tables().size());
            response.put("limit", limit);
            response.put("totalCount", page.totalCount());
            response.put("hasMore", page.hasMore());
            if (nextCursor != null) {
                response.put("nextCursor", nextCursor);
            }
            response.put("elapsedMs", elapsed);
            response.put("connectionId", effectiveId);

            return CallToolResult.builder()
                    .content(List.of(new TextContent(successSummary(effectiveId, searchPattern, page, limit))))
                    .structuredContent(response)
                    .isError(false)
                    .build();
        } catch (IllegalArgumentException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Validation failed: list_tables, error: {}", e.getMessage());
            sendLog(exchange, McpSchema.LoggingLevel.WARNING, "list_tables", "Validation error: " + e.getMessage());
            return CallToolResult.builder()
                    .structuredContent(errorResponse(List.of(), effectiveId, connectionId, elapsed,
                            normalizeLimit(arguments.get("limit")), 0L, false, KeySet.ERR_VALIDATION_ERROR,
                            "Validation error: " + e.getMessage()))
                    .isError(true)
                    .build();
        } catch (SQLTimeoutException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Tool failed (timeout): list_tables, error: {}", e.getMessage());
            sendLog(exchange, McpSchema.LoggingLevel.ERROR, "list_tables",
                    "The query exceeded the configured timeout.");
            return CallToolResult.builder()
                    .structuredContent(errorResponse(List.of(), effectiveId, connectionId, elapsed,
                            normalizeLimit(arguments.get("limit")), 0L, false, KeySet.ERR_QUERY_TIMEOUT,
                            "The request exceeded the configured timeout."))
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
                    .structuredContent(errorResponse(List.of(), effectiveId, connectionId, elapsed,
                            normalizeLimit(arguments.get("limit")), 0L, false, code,
                            e.getMessage() == null ? code : e.getMessage()))
                    .isError(true)
                    .build();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Tool failed: list_tables, error: {}", e.getMessage(), e);
            return CallToolResult.builder()
                    .structuredContent(errorResponse(List.of(), effectiveId, connectionId, elapsed,
                            normalizeLimit(arguments.get("limit")), 0L, false, KeySet.ERR_INTERNAL_ERROR,
                            e.getMessage() == null ? "Internal error" : e.getMessage()))
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
    private static DbClient.TablesPage fetchTablesWithProgress(DbClient db, String searchPattern, int limit,
            DbClient.TablePageCursor cursor, Long totalCountHint, McpSyncServerExchange exchange, String progressToken)
            throws SQLException {
        DbClient.TablesPage tables;
        AutoCloseable pinger = startProgressPinger(exchange,
                progressToken, "list_tables", 10, 95, 3000);
        try {
            tables = db.getTablesPage(searchPattern, limit, cursor, totalCountHint);
        } finally {
            try {
                pinger.close();
            } catch (Exception e) {
                log.warn("Failed to close progress pinger: {}", e.getMessage());
            }
        }
        return tables;
    }

    static int normalizeLimit(Object rawLimit) {
        if (rawLimit instanceof Number n) {
            return Math.max(1, Math.min(KeySet.LIST_TABLES_MAX_LIMIT, n.intValue()));
        }
        if (rawLimit instanceof String s) {
            try {
                return Math.max(1, Math.min(KeySet.LIST_TABLES_MAX_LIMIT, Integer.parseInt(s.trim())));
            } catch (NumberFormatException ignored) {
                return KeySet.LIST_TABLES_DEFAULT_LIMIT;
            }
        }
        return KeySet.LIST_TABLES_DEFAULT_LIMIT;
    }

    static String encodeCursorToken(String connectionId, DbClient.TablePageCursor cursor, long totalCount) {
        String payload = String.join("\u0000",
                "v1",
                connectionId,
                cursor.schema(),
                cursor.tablePattern(),
                cursor.lastTableName(),
                cursor.lastTableType(),
                Long.toString(totalCount));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    static DecodedCursor decodeCursorToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\u0000", -1);
            if (parts.length != 7 || !"v1".equals(parts[0])) {
                throw new IllegalArgumentException("Unsupported cursor format");
            }
            return new DecodedCursor(parts[1], new DbClient.TablePageCursor(parts[2], parts[3], parts[4], parts[5]),
                    Long.parseLong(parts[6]));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cursor. Restart browsing without cursor.", e);
        }
    }

    private static String successSummary(String effectiveId, String searchPattern, DbClient.TablesPage page, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("Returned ").append(page.tables().size()).append(" table(s) from connection ").append(effectiveId);
        sb.append(" with limit ").append(limit);
        if (searchPattern != null && !searchPattern.isBlank()) {
            sb.append(" using searchPattern ").append(searchPattern);
        }
        sb.append(". Total matches: ").append(page.totalCount()).append(".");
        if (page.hasMore()) {
            sb.append(" More pages are available; call list_tables again with the same filters and cursor=nextCursor from this response.");
        }
        return sb.toString();
    }

    private static Map<String, Object> errorResponse(List<Map<String, Object>> tables, String effectiveId, String connectionId,
            long elapsedMs, int limit, long totalCount, boolean hasMore, String code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tables", tables);
        response.put("count", tables.size());
        response.put("limit", limit);
        response.put("totalCount", totalCount);
        response.put("hasMore", hasMore);
        response.put("elapsedMs", elapsedMs);
        response.put("connectionId", effectiveId == null ? DbClientSingleton.resolveConnectionId(connectionId) : effectiveId);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    record DecodedCursor(String connectionId, DbClient.TablePageCursor pageCursor, long totalCount) {
    }
}
