package io.ajcm.db2.ibmi.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.ajcm.db2.ibmi.mcp.tools.HealthTool;
import io.ajcm.db2.ibmi.mcp.tools.ListTablesTool;
import io.ajcm.db2.ibmi.mcp.tools.DescribeTableTool;
import io.ajcm.db2.ibmi.mcp.tools.ExecuteSelectTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the MCP server running over STDIO.
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final String SERVER_NAME = "db2-ibmi-mcp-readonly";

    /**
     * Starts the MCP server and registers all tools.
     * 
     * @param args application arguments
     */
    public static void main(String[] args) {
        String serverVersion = Main.class.getPackage().getImplementationVersion();
        if (serverVersion == null || serverVersion.isBlank()) {
            serverVersion = "unknown";
        }
        McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
        StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);

        log.info("Starting MCP server (STDIO) {} ...", serverVersion);

        ServerCapabilities capabilities = ServerCapabilities.builder()
                .tools(true) // Enable tool support
                .logging() // Enable logging support
                .build();

        McpSyncServer syncServer = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, serverVersion)
                .capabilities(capabilities)
                .build();

        // Register tools
        syncServer.addTool(HealthTool.create());
        syncServer.addTool(ListTablesTool.create());
        syncServer.addTool(DescribeTableTool.create());
        syncServer.addTool(ExecuteSelectTool.create());

    }
}
