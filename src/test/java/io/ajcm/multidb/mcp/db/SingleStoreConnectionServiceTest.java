package io.ajcm.multidb.mcp.db;

import io.ajcm.multidb.mcp.config.ConnectionConfig;
import io.ajcm.multidb.mcp.config.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.*;

class SingleStoreConnectionServiceTest {

    private SingleStoreConnectionService service;
    private ConnectionConfig config;

    @BeforeEach
    void setUp() {
        config = new ConnectionConfig(
            "test_singlestore",
            DbType.SINGLESTORE,
            "invalid.test.host",
            3306,
            "testuser",
            "testpass",
            "testdb",
            false,
            "Test SingleStore connection"
        );
        service = new SingleStoreConnectionService(config);
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
            service.listTables("testdb");
        });
    }

    @Test
    void testDescribeTableWithInvalidConnection() {
        // Should throw exception for invalid connection
        assertThrows(RuntimeException.class, () -> {
            service.describeTable("testdb", "testtable");
        });
    }

    @Test
    void testExecuteSelectWithInvalidConnection() {
        assertDoesNotThrow(() -> {
            String result = service.executeSelect("SELECT * FROM testdb.testtable");
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
        String validSql = "SELECT column1, column2 FROM testdb.testtable WHERE column1 = 'value'";

        String result = service.executeSelect(validSql);

        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("\"error_type\":\"QUERY_ERROR\""));
    }

    @Test
    void testConstructorWithInvalidType() {
        ConnectionConfig invalidConfig = new ConnectionConfig(
            "test",
            DbType.DB2_IBMI,
            "localhost",
            446,
            "user",
            "pass",
            "db",
            false,
            "Test"
        );

        // Should throw exception for wrong database type
        assertThrows(IllegalArgumentException.class, () -> {
            new SingleStoreConnectionService(invalidConfig);
        });
    }

    @Test
    void testClose() {
        // Should not throw exception
        assertDoesNotThrow(() -> {
            service.close();
        });
    }

    @Test
    void testConnectionUrlGeneration() {
        assertDoesNotThrow(() -> {
            new SingleStoreConnectionService(config);
        });
    }

    @Test
    void testSslConfiguration() {
        ConnectionConfig sslConfig = new ConnectionConfig(
            "test_ssl",
            DbType.SINGLESTORE,
            "localhost",
            3306,
            "user",
            "pass",
            "db",
            true,
            "SSL test"
        );

        // Should not throw exception with SSL enabled
        assertDoesNotThrow(() -> {
            new SingleStoreConnectionService(sslConfig);
        });
    }
}
