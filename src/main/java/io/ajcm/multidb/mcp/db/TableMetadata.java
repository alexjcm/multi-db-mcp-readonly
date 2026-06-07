package io.ajcm.multidb.mcp.db;

import java.util.List;
import java.util.Map;

import io.ajcm.multidb.mcp.config.DbType;

/**
 * Metadata for a database table.
 * 
 * @param table        Table name
 * @param schema       Schema name
 * @param dbType       Database type
 * @param columns      List of column information
 * @param primaryKey   Primary key columns
 * @param foreignKeys  Foreign key information
 * @param extended     Database-specific extended metadata (optional)
 */
public record TableMetadata(
    String table,
    String schema,
    DbType dbType,
    List<ColumnInfo> columns,
    List<String> primaryKey,
    List<ForeignKeyInfo> foreignKeys,
    Map<String, Object> extended
) {
    
    /**
     * Column information.
     * 
     * @param name     Column name
     * @param type     Data type
     * @param nullable Whether nullable
     * @param key      Key type (PRIMARY, UNIQUE, etc.)
     */
    public record ColumnInfo(
        String name,
        String type,
        boolean nullable,
        String key
    ) {}
    
    /**
     * Foreign key information.
     * 
     * @param name           Constraint name
     * @param columns        Local columns
     * @param referencedTable Referenced table
     * @param referencedColumns Referenced columns
     */
    public record ForeignKeyInfo(
        String name,
        List<String> columns,
        String referencedTable,
        List<String> referencedColumns
    ) {}
}
