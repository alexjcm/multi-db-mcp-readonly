package io.ajcm.db2.ibmi.mcp.tools;

import io.ajcm.db2.ibmi.mcp.db.DbClient;
import io.ajcm.db2.ibmi.mcp.db.DbClientSingleton;
import io.ajcm.db2.ibmi.mcp.util.KeySet;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.text.Normalizer;

import io.ajcm.db2.ibmi.mcp.util.ResourceUtils;
import java.sql.SQLException;
import java.util.Map;

/**
 * MCP tool that verifies server and database connection health.
 */
public class HealthTool {
    private static final Logger log = LoggerFactory.getLogger(HealthTool.class);

    private HealthTool() {
    }

    /**
     * Builds the synchronous tool specification for health check.
     * 
     * @return tool specification
     */
    public static SyncToolSpecification create() {
        String inputSchema = ResourceUtils.readClasspathResourceAsString("schemas/health.input.json");
        String outputSchema = ResourceUtils.readClasspathResourceAsString("schemas/health.output.json");
        McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
        Tool tool = Tool.builder()
                .name("health")
                .title("Health")
                .description("Verify MCP server is running and DB connection is healthy")
                .inputSchema(jsonMapper, inputSchema)
                .outputSchema(jsonMapper, outputSchema)
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(HealthTool::handle)
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
        boolean dbOk;
        String connectionId = null;
        String effectiveId = null;
        try {
            Map<String, Object> args = req.arguments();
            if (args != null) {
                Object cid = args.get("connectionId");
                if (cid instanceof String s && !s.isBlank()) {
                    String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
                    connectionId = n.toUpperCase(java.util.Locale.ROOT);
                }
            }
            if (connectionId != null && !DbClientSingleton.has(connectionId)) {
                return CallToolResult.builder()
                        .structuredContent(Map.of(
                                "status", "ok",
                                "db", "error",
                                "connectionId", connectionId,
                                "error", Map.of(
                                        "code", KeySet.ERR_VALIDATION_ERROR,
                                        "message", "Unknown or invalid connectionId: " + connectionId
                                )
                        ))
                        .isError(true)
                        .build();
            }
            effectiveId = DbClientSingleton.resolveConnectionId(connectionId);
            DbClient db = (connectionId == null) ? DbClientSingleton.get() : DbClientSingleton.get(connectionId);
            log.info("health: using connectionId={}", connectionId == null ? "<default>" : connectionId);
            dbOk = db.health();
            if (dbOk) {
                return CallToolResult.builder()
                        .structuredContent(Map.of(
                                "status", "ok",
                                "db", "ok",
                                "connectionId", effectiveId
                        ))
                        .isError(false)
                        .build();
            } else {
                return CallToolResult.builder()
                        .structuredContent(Map.of(
                                "status", "ok",
                                "db", "error",
                                "connectionId", effectiveId,
                                "error", Map.of(
                                        "code", KeySet.ERR_NET_UNREACHABLE, "message", "Preflight failed: host/port not reachable"
                                )
                        ))
                        .isError(true)
                        .build();
            }
        } catch (Throwable e) {
            log.error("Tool failed: health, error: {}", e.getMessage(), e);
            if (e instanceof SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();
                String code;
                if ("28000".equals(sqlState) || "08004".equals(sqlState)) {
                    code = KeySet.ERR_AUTH_FAILED;
                } else if ("08001".equals(sqlState)) {
                    code = KeySet.ERR_NET_UNREACHABLE;
                } else {
                    code = KeySet.ERR_INTERNAL_ERROR;
                }
                String message = sqlEx.getMessage() == null ? code : sqlEx.getMessage();
                if (effectiveId == null) effectiveId = DbClientSingleton.resolveConnectionId(connectionId);
                return CallToolResult.builder()
                        .structuredContent(Map.of(
                                "status", "ok",
                                "db", "error",
                                "connectionId", effectiveId,
                                "error", Map.of("code", code, "message", message
                                )
                        ))
                        .isError(true)
                        .build();
            } else if (e instanceof NoClassDefFoundError || e instanceof ExceptionInInitializerError) {
                // This happens when DbClientSingleton static initialization fails (e.g., no valid profiles)
                String message = "DB profiles initialization failed: verify DB2_CONN_IDS and per-profile environment variables";
                String id = (connectionId == null || connectionId.isBlank()) ? "<default>" : connectionId;
                return CallToolResult.builder()
                        .structuredContent(Map.of(
                                "status", "ok",
                                "db", "error",
                                "connectionId", id,
                                "error", Map.of("code", KeySet.ERR_VALIDATION_ERROR, "message", message)
                        ))
                        .isError(true)
                        .build();
            } else {
                String message = e.getMessage() == null ? "Internal error" : e.getMessage();
                if (effectiveId == null) effectiveId = DbClientSingleton.resolveConnectionId(connectionId);
                return CallToolResult.builder()
                        .structuredContent(Map.of(
                                "status", "ok",
                                "db", "error",
                                "connectionId", effectiveId,
                                "error", Map.of("code", KeySet.ERR_INTERNAL_ERROR, "message", message
                                )
                        ))
                        .isError(true)
                        .build();
            }
        }
    }
}
