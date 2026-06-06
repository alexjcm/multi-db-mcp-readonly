package io.ajcm.multidb.mcp.db;

import io.ajcm.multidb.mcp.config.ConnectionConfig;
import io.ajcm.multidb.mcp.config.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.*;

class Db2ConnectionServiceTest {

    private Db2ConnectionService service;
    private ConnectionConfig config;

    @BeforeEach
    void setUp() {
        config = new ConnectionConfig(
            "test_db2",
            DbType.DB2_IBMI,
            "invalid.test.host",
            446,
            "testuser",
            "testpass",
            "TESTLIB",
            false,
            "Test DB2 connection"
        );
        service = new Db2ConnectionService(config);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void testHealthCheckWithInvalidConnection() {
        // Should return false for invalid connection
        assertFalse(service.healthCheck());
    }

    @Test
    void testGetConfig() {
        assertEquals(config, service.getConfig());
    }

    @Test
    void testListTablesWithInvalidConnection() {
        // Should throw exception for invalid connection
        assertThrows(RuntimeException.class, () -> {
            service.listTables("TESTLIB");
        });
    }

    @Test
    void testDescribeTableWithInvalidConnection() {
        // Should throw exception for invalid connection
        assertThrows(RuntimeException.class, () -> {
            service.describeTable("TESTLIB", "TESTTABLE");
        });
    }

    @Test
    void testExecuteSelectWithInvalidConnection() {
        assertDoesNotThrow(() -> {
            String result = service.executeSelect("SELECT * FROM TESTLIB.TESTTABLE");
            assertTrue(result.contains("\"success\":false"));
            assertTrue(result.contains("\"error_type\":\"QUERY_ERROR\""));
        });
    }

    @Test
    void testExecuteSelectWithInvalidSql() {
        // Should throw exception for non-SELECT queries
        assertThrows(Exception.class, () -> {
            service.executeSelect("INSERT INTO test VALUES (1)");
        });
        
        assertThrows(Exception.class, () -> {
            service.executeSelect("UPDATE test SET col = 1");
        });
        
        assertThrows(Exception.class, () -> {
            service.executeSelect("DELETE FROM test");
        });
        
        assertThrows(Exception.class, () -> {
            service.executeSelect("DROP TABLE test");
        });
    }

    @Test
    void testExecuteSelectWithValidSelectSql() throws Exception {
        String validSql = "SELECT column1, column2 FROM TESTLIB.TESTTABLE WHERE column1 = 'value'";

        String result = service.executeSelect(validSql);

        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("\"error_type\":\"QUERY_ERROR\""));
    }

    @Test
    void testConstructorWithInvalidType() {
        ConnectionConfig invalidConfig = new ConnectionConfig(
            "test",
            DbType.SINGLESTORE,
            "localhost",
            3306,
            "user",
            "pass",
            "db",
            false,
            "Test"
        );

        // Should throw exception for wrong database type
        assertThrows(IllegalArgumentException.class, () -> {
            new Db2ConnectionService(invalidConfig);
        });
    }

    @Test
    void testClose() {
        // Should not throw exception
        assertDoesNotThrow(() -> {
            service.close();
        });
    }
}
