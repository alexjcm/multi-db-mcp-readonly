package io.ajcm.multidb.mcp.tool;

import io.ajcm.multidb.mcp.config.ConnectionConfig;
import io.ajcm.multidb.mcp.config.DbType;
import io.ajcm.multidb.mcp.db.DbConnectionProvider;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolBuilderTest {

    private ConnectionConfig db2Config;
    private ConnectionConfig singleStoreConfig;
    private DbConnectionProvider mockProvider;

    @BeforeEach
    void setUp() {
        db2Config = new ConnectionConfig(
            "test_db2",
            DbType.DB2_IBMI,
            "localhost",
            446,
            "user",
            "pass",
            "TESTLIB",
            false,
            "Test DB2"
        );

        singleStoreConfig = new ConnectionConfig(
            "test_singlestore",
            DbType.SINGLESTORE,
            "localhost",
            3306,
            "user",
            "pass",
            "testdb",
            true,
            "Test SingleStore"
        );

        mockProvider = new DbConnectionProvider() {
            @Override
            public boolean healthCheck() {
                return true;
            }

            @Override
            public java.util.List<io.ajcm.multidb.mcp.db.TableMetadata> listTables(String schema) {
                return java.util.Collections.emptyList();
            }

            @Override
            public io.ajcm.multidb.mcp.db.TableMetadata describeTable(String schema, String table) {
                return null;
            }

            @Override
            public String executeSelect(String query) throws Exception {
                return "{\"success\": true, \"data\": {}}";
            }

            @Override
            public ConnectionConfig getConfig() {
                return db2Config;
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void testBuildHealthTool() {
        McpServerFeatures.SyncToolSpecification tool = ToolBuilder.buildHealthTool("test_db2", db2Config, mockProvider);

        assertNotNull(tool);
        assertEquals("health_test_db2", tool.tool().name());
        assertTrue(tool.tool().description().contains("DB2 for i"));
        assertTrue(tool.tool().description().contains("test_db2"));
        assertNotNull(tool.tool().inputSchema());
        assertNotNull(tool.callHandler());
    }

    @Test
    void testBuildListTablesTool() {
        McpServerFeatures.SyncToolSpecification tool = ToolBuilder.buildListTablesTool("test_db2", db2Config, mockProvider);

        assertNotNull(tool);
        assertEquals("list_tables_test_db2", tool.tool().name());
        assertTrue(tool.tool().description().contains("DB2 for i"));
        assertTrue(tool.tool().description().contains("test_db2"));
        assertNotNull(tool.tool().inputSchema());
        assertNotNull(tool.callHandler());
    }

    @Test
    void testBuildDescribeTableTool() {
        McpServerFeatures.SyncToolSpecification tool = ToolBuilder.buildDescribeTableTool("test_db2", db2Config, mockProvider);

        assertNotNull(tool);
        assertEquals("describe_table_test_db2", tool.tool().name());
        assertTrue(tool.tool().description().contains("DB2 for i"));
        assertTrue(tool.tool().description().contains("test_db2"));
        assertNotNull(tool.tool().inputSchema());
        assertNotNull(tool.callHandler());
    }

    @Test
    void testBuildExecuteSelectTool() {
        McpServerFeatures.SyncToolSpecification tool = ToolBuilder.buildExecuteSelectTool("test_db2", db2Config, mockProvider);

        assertNotNull(tool);
        assertEquals("execute_select_test_db2", tool.tool().name());
        assertTrue(tool.tool().description().contains("DB2 for i"));
        assertTrue(tool.tool().description().contains("test_db2"));
        assertTrue(tool.tool().description().contains("read-only"));
        assertNotNull(tool.tool().inputSchema());
        assertNotNull(tool.callHandler());
    }

    @Test
    void testToolNamesAreUnique() {
        McpServerFeatures.SyncToolSpecification health1 = ToolBuilder.buildHealthTool("test_db2", db2Config, mockProvider);
        McpServerFeatures.SyncToolSpecification health2 = ToolBuilder.buildHealthTool("test_singlestore", singleStoreConfig, mockProvider);

        assertNotEquals(health1.tool().name(), health2.tool().name());
        assertTrue(health1.tool().name().contains("test_db2"));
        assertTrue(health2.tool().name().contains("test_singlestore"));
    }

    @Test
    void testToolDescriptionsContainDatabaseType() {
        McpServerFeatures.SyncToolSpecification db2Tool = ToolBuilder.buildHealthTool("test_db2", db2Config, mockProvider);
        McpServerFeatures.SyncToolSpecification ssTool = ToolBuilder.buildHealthTool("test_singlestore", singleStoreConfig, mockProvider);

        assertTrue(db2Tool.tool().description().contains("DB2 for i"));
        assertTrue(ssTool.tool().description().contains("SingleStore"));
    }

    @Test
    void testToolDescriptionsContainConnectionId() {
        McpServerFeatures.SyncToolSpecification tool = ToolBuilder.buildListTablesTool("test_db2", db2Config, mockProvider);

        assertTrue(tool.tool().description().contains("test_db2"));
    }

    @Test
    void testToolDescriptionsContainHostAndDatabase() {
        McpServerFeatures.SyncToolSpecification tool = ToolBuilder.buildDescribeTableTool("test_db2", db2Config, mockProvider);

        assertTrue(tool.tool().description().contains("localhost"));
        assertTrue(tool.tool().description().contains("TESTLIB"));
    }

    @Test
    void testExecuteSelectToolContainsSqlValidationWarning() {
        McpServerFeatures.SyncToolSpecification tool = ToolBuilder.buildExecuteSelectTool("test_db2", db2Config, mockProvider);

        assertTrue(tool.tool().description().contains("read-only"));
        assertTrue(tool.tool().description().contains("SELECT statements are allowed"));
        assertTrue(tool.tool().description().contains("INSERT, UPDATE, DELETE, and DDL will be rejected"));
    }

    @Test
    void testToolInputSchemasAreValid() {
        McpServerFeatures.SyncToolSpecification healthTool = ToolBuilder.buildHealthTool("test_db2", db2Config, mockProvider);
        McpServerFeatures.SyncToolSpecification listTablesTool = ToolBuilder.buildListTablesTool("test_db2", db2Config, mockProvider);
        McpServerFeatures.SyncToolSpecification describeTableTool = ToolBuilder.buildDescribeTableTool("test_db2", db2Config, mockProvider);
        McpServerFeatures.SyncToolSpecification executeSelectTool = ToolBuilder.buildExecuteSelectTool("test_db2", db2Config, mockProvider);

        // Health tool should have no required parameters
        assertTrue(healthTool.tool().inputSchema().required().isEmpty());

        // List tables tool should have optional schema parameter
        assertTrue(listTablesTool.tool().inputSchema().required().isEmpty());

        // Describe table tool should require schema and table
        assertEquals(2, describeTableTool.tool().inputSchema().required().size());
        assertTrue(describeTableTool.tool().inputSchema().required().contains("schema"));
        assertTrue(describeTableTool.tool().inputSchema().required().contains("table"));

        // Execute select tool should require query
        assertEquals(1, executeSelectTool.tool().inputSchema().required().size());
        assertTrue(executeSelectTool.tool().inputSchema().required().contains("query"));
    }

    @Test
    void testToolCallHandlersAreNotNull() {
        McpServerFeatures.SyncToolSpecification healthTool = ToolBuilder.buildHealthTool("test_db2", db2Config, mockProvider);
        McpServerFeatures.SyncToolSpecification listTablesTool = ToolBuilder.buildListTablesTool("test_db2", db2Config, mockProvider);
        McpServerFeatures.SyncToolSpecification describeTableTool = ToolBuilder.buildDescribeTableTool("test_db2", db2Config, mockProvider);
        McpServerFeatures.SyncToolSpecification executeSelectTool = ToolBuilder.buildExecuteSelectTool("test_db2", db2Config, mockProvider);

        assertNotNull(healthTool.callHandler());
        assertNotNull(listTablesTool.callHandler());
        assertNotNull(describeTableTool.callHandler());
        assertNotNull(executeSelectTool.callHandler());
    }
}
