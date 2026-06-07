package io.ajcm.multidb.mcp.tool;

import io.ajcm.multidb.mcp.config.ConnectionConfig;
import io.ajcm.multidb.mcp.config.DbType;
import io.ajcm.multidb.mcp.db.DbConnectionProvider;
import io.ajcm.multidb.mcp.db.TableMetadata;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartDefaultToolBuilderTest {

    private DbConnectionProvider ecuadorDb2;
    private DbConnectionProvider ecuadorSingleStore;
    private DbConnectionProvider panamaDb2;

    @BeforeEach
    void setUp() {
        ecuadorDb2 = new StubProvider(new ConnectionConfig(
                "ecuador_db2",
                DbType.DB2_IBMI,
                "db2-ec.example",
                8471,
                "user",
                "pass",
                "MYDB",
                false,
                "Ecuador DB2"
        ), true);

        ecuadorSingleStore = new StubProvider(new ConnectionConfig(
                "ecuador_singlestore",
                DbType.SINGLESTORE,
                "ss-ec.example",
                3306,
                "user",
                "pass",
                "MYDB2",
                false,
                "Ecuador SingleStore"
        ), true);

        panamaDb2 = new StubProvider(new ConnectionConfig(
                "panama_db2",
                DbType.DB2_IBMI,
                "db2-pa.example",
                8471,
                "user",
                "pass",
                "MYDB",
                false,
                "Panama DB2"
        ), true);
    }

    @Test
    void healthToolDescriptionAdvertisesEcuadorDb2AsDefault() {
        McpServerFeatures.SyncToolSpecification tool = SmartDefaultToolBuilder.buildHealthTool(providers(
                Map.entry("ecuador_singlestore", ecuadorSingleStore),
                Map.entry("panama_db2", panamaDb2),
                Map.entry("ecuador_db2", ecuadorDb2)
        ));

        assertTrue(tool.tool().description().contains("ecuador_db2"));
        assertTrue(tool.tool().description().contains("Default rules: Ecuador > DB2 > First available"));
    }

    @Test
    void healthToolFallsBackToAnyDb2WhenNoEcuadorConnectionExists() {
        McpServerFeatures.SyncToolSpecification tool = SmartDefaultToolBuilder.buildHealthTool(providers(
                Map.entry("analytics_singlestore", ecuadorSingleStore),
                Map.entry("panama_db2", panamaDb2)
        ));

        assertTrue(tool.tool().description().contains("panama_db2"));
        assertFalse(tool.tool().description().contains("analytics_singlestore ("));
    }

    @Test
    void healthToolCallUsesDefaultProviderWhenConnectionIdIsOmitted() {
        McpServerFeatures.SyncToolSpecification tool = SmartDefaultToolBuilder.buildHealthTool(providers(
                Map.entry("ecuador_singlestore", ecuadorSingleStore),
                Map.entry("ecuador_db2", ecuadorDb2)
        ));

        McpSchema.CallToolResult result = tool.callHandler().apply(null, new McpSchema.CallToolRequest("health", Map.of()));
        String payload = ((McpSchema.TextContent) result.content().getFirst()).text();

        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertTrue(payload.contains("\"connection_id\":\"ecuador_db2\""));
        assertTrue(payload.contains("\"is_default\":true"));
        assertTrue(payload.contains("\"status\":\"connected\""));
    }

    @Test
    void healthToolReturnsConnectionNotFoundWhenUnknownConnectionIdIsRequested() {
        McpServerFeatures.SyncToolSpecification tool = SmartDefaultToolBuilder.buildHealthTool(providers(
                Map.entry("ecuador_db2", ecuadorDb2)
        ));

        McpSchema.CallToolResult result = tool.callHandler()
                .apply(null, new McpSchema.CallToolRequest("health", Map.of("connection_id", "missing")));
        String payload = ((McpSchema.TextContent) result.content().getFirst()).text();

        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertTrue(payload.contains("\"error_type\":\"CONNECTION_NOT_FOUND\""));
        assertTrue(payload.contains("ecuador_db2"));
    }

    @Test
    void listTablesToolSchemaMarksConnectionIdAsOptional() {
        McpServerFeatures.SyncToolSpecification tool = SmartDefaultToolBuilder.buildListTablesTool(providers(
                Map.entry("ecuador_db2", ecuadorDb2)
        ));

        assertTrue(tool.tool().inputSchema().required().isEmpty());
        assertTrue(tool.tool().inputSchema().properties().containsKey("connection_id"));
        assertTrue(tool.tool().inputSchema().properties().containsKey("schema"));
    }

    private Map<String, DbConnectionProvider> providers(Map.Entry<String, DbConnectionProvider>... entries) {
        Map<String, DbConnectionProvider> providers = new LinkedHashMap<>();
        for (Map.Entry<String, DbConnectionProvider> entry : entries) {
            providers.put(entry.getKey(), entry.getValue());
        }
        return providers;
    }

    private static final class StubProvider implements DbConnectionProvider {
        private final ConnectionConfig config;
        private final boolean healthy;

        private StubProvider(ConnectionConfig config, boolean healthy) {
            this.config = config;
            this.healthy = healthy;
        }

        @Override
        public boolean healthCheck() {
            return healthy;
        }

        @Override
        public List<TableMetadata> listTables(String schema) {
            return Collections.emptyList();
        }

        @Override
        public TableMetadata describeTable(String schema, String table) {
            return null;
        }

        @Override
        public String executeSelect(String query) {
            return "{\"success\":true}";
        }

        @Override
        public ConnectionConfig getConfig() {
            return config;
        }

        @Override
        public void close() {
        }

        @Override
        public String getLastError() {
            return null;
        }

        @Override
        public String getLastErrorType() {
            return null;
        }

        @Override
        public String getLastSqlState() {
            return null;
        }

        @Override
        public int getLastErrorCode() {
            return 0;
        }
    }
}
