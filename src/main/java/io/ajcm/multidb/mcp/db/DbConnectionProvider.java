package io.ajcm.multidb.mcp.db;

import io.ajcm.multidb.mcp.config.ConnectionConfig;
import io.ajcm.multidb.mcp.db.TableMetadata;

import java.util.List;

/**
 * Interface for database connection providers.
 * Each database type implements this interface to provide
 * standardized access to its metadata and query capabilities.
 */
public interface DbConnectionProvider {
    
    /**
     * Checks if the database connection is healthy.
     * 
     * @return true if connection is working
     */
    boolean healthCheck();
    
    /**
     * Lists all tables in the database.
     * 
     * @param schema optional schema filter (can be null)
     * @return list of table metadata
     */
    List<TableMetadata> listTables(String schema);
    
    /**
     * Gets detailed table information including columns and keys.
     * 
     * @param schema schema name
     * @param table table name
     * @return table metadata with extended information
     */
    TableMetadata describeTable(String schema, String table);
    
    /**
     * Executes a read-only SELECT query.
     * 
     * @param query SQL SELECT statement
     * @return query results as JSON string
     * @throws Exception if query is invalid or fails
     */
    String executeSelect(String query) throws Exception;
    
    /**
     * Gets the connection configuration.
     * 
     * @return connection config
     */
    ConnectionConfig getConfig();
    
    /**
     * Closes the connection and cleans up resources.
     */
    void close();
    
    /**
     * Gets the last error message from health check (for observability).
     * 
     * @return last error message or null if no error
     */
    String getLastError();
    
    /**
     * Gets the last error type from health check (for observability).
     * 
     * @return last error type or null if no error
     */
    String getLastErrorType();
    
    /**
     * Gets the last SQL state from health check (for observability).
     * 
     * @return last SQL state or null if no error
     */
    String getLastSqlState();
    
    /**
     * Gets the last error code from health check (for observability).
     * 
     * @return last error code or 0 if no error
     */
    int getLastErrorCode();
}
