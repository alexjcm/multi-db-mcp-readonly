package io.ajcm.multidb.mcp.util;

import io.ajcm.multidb.mcp.config.ConnectionConfig;
import io.ajcm.multidb.mcp.config.DbType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads and validates connection configuration from JSON file.
 * Implements fail-fast validation - exits if configuration is invalid.
 */
public class ConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    /**
     * Loads connection configuration from file.
     * 
     * @param configPath path to configuration file
     * @return list of validated connection configs
     * @throws IllegalArgumentException if configuration is invalid
     */
    public static List<ConnectionConfig> load(String configPath) {
        Path path = Paths.get(configPath);
        
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Configuration file not found: " + configPath);
        }
        
        try {
            String content = Files.readString(path);
            JsonNode root = mapper.readTree(content);
            
            // Validate root structure
            if (!root.has("connections")) {
                throw new IllegalArgumentException("Missing 'connections' array in configuration");
            }
            
            JsonNode connectionsNode = root.get("connections");
            if (!connectionsNode.isArray()) {
                throw new IllegalArgumentException("'connections' must be an array");
            }
            
            ArrayNode connectionsArray = (ArrayNode) connectionsNode;
            List<ConnectionConfig> configs = new ArrayList<>();
            
            for (int i = 0; i < connectionsArray.size(); i++) {
                JsonNode connNode = connectionsArray.get(i);
                ConnectionConfig config = parseConnectionConfig(connNode, i);
                configs.add(config);
            }
            
            if (configs.isEmpty()) {
                throw new IllegalArgumentException("At least one connection must be configured");
            }
            
            log.info("Loaded {} database connections from {}", configs.size(), configPath);
            return configs;
            
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read configuration file: " + configPath, e);
        }
    }
    
    /**
     * Parses a single connection configuration.
     */
    private static ConnectionConfig parseConnectionConfig(JsonNode node, int index) {
        // Validate required fields
        String[] requiredFields = {"id", "type", "host", "user", "password", "database"};
        for (String field : requiredFields) {
            if (!node.has(field) || node.get(field).asText().trim().isEmpty()) {
                throw new IllegalArgumentException(String.format(
                    "Connection %d: Missing required field '%s'", index, field));
            }
        }
        
        // Parse fields
        String id = node.get("id").asText().trim();
        String typeStr = node.get("type").asText().trim().toUpperCase();
        String host = node.get("host").asText().trim();
        int port = node.has("port") ? node.get("port").asInt() : getDefaultPort(typeStr);
        String user = node.get("user").asText().trim();
        String password = node.get("password").asText().trim();
        String database = node.get("database").asText().trim();
        boolean ssl = node.has("ssl") ? node.get("ssl").asBoolean() : true;
        String description = node.has("description") ? node.get("description").asText().trim() : null;
        
        // Parse database type
        DbType dbType;
        try {
            dbType = DbType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format(
                "Connection %d (%s): Invalid database type '%s'. Supported types: %s",
                index, id, typeStr, java.util.Arrays.toString(DbType.values())));
        }
        
        return new ConnectionConfig(id, dbType, host, port, user, password, database, ssl, description);
    }
    
    /**
     * Gets default port for database type.
     */
    private static int getDefaultPort(String type) {
        return switch (type) {
            case "DB2_IBMI" -> 446;
            case "SINGLESTORE" -> 3306;
            default -> throw new IllegalArgumentException("Unknown database type: " + type);
        };
    }
}
