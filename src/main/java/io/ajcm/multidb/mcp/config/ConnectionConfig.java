package io.ajcm.multidb.mcp.config;

/**
 * Configuration for a database connection.
 * 
 * @param id           Unique identifier for this connection
 * @param type         Database type (DB2_IBMI, SINGLESTORE)
 * @param host         Database host
 * @param port         Database port
 * @param user         Username
 * @param password     Password
 * @param database     Database/schema name
 * @param ssl          Whether to use SSL
 * @param description  Optional description
 */
public record ConnectionConfig(
    String id,
    DbType type,
    String host,
    int port,
    String user,
    String password,
    String database,
    boolean ssl,
    String description
) {
    
    public ConnectionConfig {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Connection ID cannot be null or empty");
        }
        if (type == null) {
            throw new IllegalArgumentException("Database type cannot be null");
        }
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Host cannot be null or empty");
        }
        if (user == null || user.trim().isEmpty()) {
            throw new IllegalArgumentException("User cannot be null or empty");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }
        if (database == null || database.trim().isEmpty()) {
            throw new IllegalArgumentException("Database cannot be null or empty");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
    }
    
    /**
     * Gets the description, generating one if not provided.
     */
    public String getDescription() {
        if (description != null && !description.trim().isEmpty()) {
            return description;
        }
        return String.format("%s database '%s' on %s", type, database, host);
    }
}
