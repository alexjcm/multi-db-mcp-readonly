package io.ajcm.multidb.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.ajcm.multidb.mcp.config.ConnectionConfig;
import io.ajcm.multidb.mcp.db.Db2ConnectionService;
import io.ajcm.multidb.mcp.db.DbConnectionProvider;
import io.ajcm.multidb.mcp.db.SingleStoreConnectionService;
import io.ajcm.multidb.mcp.tool.SmartDefaultToolBuilder;
import io.ajcm.multidb.mcp.util.ConfigLoader;
import io.ajcm.multidb.mcp.util.ConfigPathResolver;
import io.ajcm.multidb.mcp.util.ConfigPathResolver.ResolvedConfigPath;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the multi-database MCP server running over STDIO.
 * Supports DB2 for i and SingleStore databases with read-only operations.
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final String SERVER_NAME = "multi-db-mcp-readonly";

    /**
     * Starts the MCP server and registers all tools for configured database connections.
     * 
     * @param args application arguments
     */
    public static void main(String[] args) {
        try {
            String serverVersion = Main.class.getPackage().getImplementationVersion();
            if (serverVersion == null || serverVersion.isBlank()) {
                serverVersion = "2.0.0";
            }

            // Load configuration with fail-fast validation
            ResolvedConfigPath resolvedConfigPath = ConfigPathResolver.resolve(args);
            log.info(
                "Loading configuration from {} ({})",
                resolvedConfigPath.path(),
                resolvedConfigPath.source()
            );
            List<ConnectionConfig> configs = ConfigLoader.load(resolvedConfigPath.path().toString());

            // Create connection providers
            Map<String, DbConnectionProvider> providers = new LinkedHashMap<>();
            for (ConnectionConfig cfg : configs) {
                DbConnectionProvider provider = switch (cfg.type()) {
                    case DB2_IBMI -> new Db2ConnectionService(cfg);
                    case SINGLESTORE -> new SingleStoreConnectionService(cfg);
                };
                providers.put(cfg.id(), provider);
            }

            // Create MCP server
            McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
            StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);
            McpSyncServer server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, serverVersion)
                .capabilities(ServerCapabilities.builder()
                    .tools(true)
                    .logging()
                    .build())
                .build();

            // Register 4 generic tools with smart defaults
            
            server.addTool(SmartDefaultToolBuilder.buildHealthTool(providers));
            server.addTool(SmartDefaultToolBuilder.buildListTablesTool(providers));
            server.addTool(SmartDefaultToolBuilder.buildDescribeTableTool(providers));
            server.addTool(SmartDefaultToolBuilder.buildExecuteSelectTool(providers));

            log.info("Multi-DB MCP server {} started with {} connections", serverVersion, configs.size());
            
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
            log.error("Failed to start MCP server: {}", e.getMessage(), e);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("ERROR: Failed to start MCP server. " + e.getMessage());
            log.error("Failed to start MCP server", e);
            System.exit(1);
        }
    }
}
