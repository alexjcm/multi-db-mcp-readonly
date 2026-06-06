package io.ajcm.multidb.mcp.util;

import io.ajcm.multidb.mcp.config.ConnectionConfig;
import io.ajcm.multidb.mcp.config.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Path configFile;

    @BeforeEach
    void setUp() {
        configFile = tempDir.resolve("test-connections.json");
    }

    @Test
    void testLoadValidConfig() throws Exception {
        String validConfig = """
            {
              "connections": [
                {
                  "id": "test_db2",
                  "type": "DB2_IBMI",
                  "host": "localhost",
                  "port": 8471,
                  "user": "testuser",
                  "password": "testpass",
                  "database": "TESTLIB",
                  "ssl": false,
                  "description": "Test DB2 connection"
                },
                {
                  "id": "test_singlestore",
                  "type": "SINGLESTORE",
                  "host": "localhost",
                  "port": 3306,
                  "user": "testuser",
                  "password": "testpass",
                  "database": "testdb",
                  "ssl": true,
                  "description": "Test SingleStore connection"
                }
              ],
              "query_timeout_sec": 30,
              "login_timeout_sec": 5
            }
            """;

        Files.write(configFile, validConfig.getBytes());
        List<ConnectionConfig> configs = ConfigLoader.load(configFile.toString());

        assertEquals(2, configs.size());

        ConnectionConfig db2Config = configs.get(0);
        assertEquals("test_db2", db2Config.id());
        assertEquals(DbType.DB2_IBMI, db2Config.type());
        assertEquals("localhost", db2Config.host());
        assertEquals(8471, db2Config.port());
        assertEquals("testuser", db2Config.user());
        assertEquals("testpass", db2Config.password());
        assertEquals("TESTLIB", db2Config.database());
        assertFalse(db2Config.ssl());
        assertEquals("Test DB2 connection", db2Config.description());

        ConnectionConfig ssConfig = configs.get(1);
        assertEquals("test_singlestore", ssConfig.id());
        assertEquals(DbType.SINGLESTORE, ssConfig.type());
        assertEquals("localhost", ssConfig.host());
        assertEquals(3306, ssConfig.port());
        assertEquals("testuser", ssConfig.user());
        assertEquals("testpass", ssConfig.password());
        assertEquals("testdb", ssConfig.database());
        assertTrue(ssConfig.ssl());
        assertEquals("Test SingleStore connection", ssConfig.description());
    }

    @Test
    void testLoadConfigWithDefaults() throws Exception {
        String configWithDefaults = """
            {
              "connections": [
                {
                  "id": "minimal",
                  "type": "DB2_IBMI",
                  "host": "localhost",
                  "user": "testuser",
                  "password": "testpass",
                  "database": "TESTLIB"
                }
              ]
            }
            """;

        Files.write(configFile, configWithDefaults.getBytes());
        List<ConnectionConfig> configs = ConfigLoader.load(configFile.toString());

        assertEquals(1, configs.size());
        ConnectionConfig config = configs.get(0);
        
        assertEquals("minimal", config.id());
        assertEquals(DbType.DB2_IBMI, config.type());
        assertEquals("localhost", config.host());
        assertEquals(8471, config.port()); // Default DB2 port
        assertEquals("testuser", config.user());
        assertEquals("testpass", config.password());
        assertEquals("TESTLIB", config.database());
        assertTrue(config.ssl()); // Default SSL
        assertNull(config.description()); // Optional field
    }

    @Test
    void testMissingFileThrowsException() {
        String nonExistentFile = "/path/to/non/existent/file.json";
        
        assertThrows(RuntimeException.class, () -> {
            ConfigLoader.load(nonExistentFile);
        });
    }

    @Test
    void testInvalidJsonThrowsException() throws Exception {
        String invalidJson = """
            {
              "connections": [
                {
                  "id": "test",
                  "type": "INVALID_TYPE",
                  "host": "localhost"
                  // Missing required fields and invalid JSON
                }
              ]
            """;

        Files.write(configFile, invalidJson.getBytes());
        
        assertThrows(RuntimeException.class, () -> {
            ConfigLoader.load(configFile.toString());
        });
    }

    @Test
    void testMissingRequiredFieldThrowsException() throws Exception {
        String missingRequiredField = """
            {
              "connections": [
                {
                  "id": "test",
                  "type": "DB2_IBMI",
                  "host": "localhost"
                  // Missing user, password, database
                }
              ]
            }
            """;

        Files.write(configFile, missingRequiredField.getBytes());
        
        assertThrows(RuntimeException.class, () -> {
            ConfigLoader.load(configFile.toString());
        });
    }

    @Test
    void testDuplicateConnectionIdsThrowsException() throws Exception {
        String duplicateIds = """
            {
              "connections": [
                {
                  "id": "duplicate",
                  "type": "DB2_IBMI",
                  "host": "localhost",
                  "user": "testuser",
                  "password": "testpass",
                  "database": "TESTLIB"
                },
                {
                  "id": "duplicate",
                  "type": "SINGLESTORE",
                  "host": "localhost",
                  "user": "testuser",
                  "password": "testpass",
                  "database": "testdb"
                }
              ]
            }
            """;

        Files.write(configFile, duplicateIds.getBytes());
        
        assertThrows(RuntimeException.class, () -> {
            ConfigLoader.load(configFile.toString());
        });
    }

    @Test
    void testEmptyConnectionsArrayThrowsException() throws Exception {
        String emptyConnections = """
            {
              "connections": []
            }
            """;

        Files.write(configFile, emptyConnections.getBytes());
        
        assertThrows(RuntimeException.class, () -> {
            ConfigLoader.load(configFile.toString());
        });
    }
}
