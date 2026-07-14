package io.ajcm.multidb.mcp.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.as400.access.AS400JDBCDataSource;
import io.ajcm.multidb.mcp.config.ConnectionConfig;
import io.ajcm.multidb.mcp.config.DbType;
import io.ajcm.multidb.mcp.validation.SqlGuards;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DB2 for i (IBM i) connection provider implementing DbConnectionProvider.
 * Refactored from original DbClient to support the new multi-db architecture.
 */
public class Db2ConnectionService implements DbConnectionProvider {
    private static final Logger log = LoggerFactory.getLogger(Db2ConnectionService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final ConnectionConfig config;
    private final AS400JDBCDataSource dataSource;
    
    // Error details for observability
    private String lastError;
    private String lastErrorType;
    private String lastSqlState;
    private int lastErrorCode;
    
    public Db2ConnectionService(ConnectionConfig config) {
        if (config.type() != DbType.DB2_IBMI) {
            throw new IllegalArgumentException("Db2ConnectionService only supports DB2_IBMI type");
        }
        
                
        this.config = config;
        this.dataSource = new AS400JDBCDataSource();
        this.dataSource.setServerName(config.host());
        this.dataSource.setPortNumber(config.port());
        this.dataSource.setUser(config.user());
        this.dataSource.setPassword(config.password());
        this.dataSource.setDatabaseName(config.database());
        this.dataSource.setSecure(config.ssl());
    }
    
    @Override
    public boolean healthCheck() {
        try (Connection conn = getConnection()) {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            log.warn("DB2 health check failed for {}: {}", config.id(), e.getMessage());
            // Store error details for observability
            lastError = e.getMessage();
            lastErrorType = e.getClass().getSimpleName();
            lastSqlState = e.getSQLState();
            lastErrorCode = e.getErrorCode();
            return false;
        }
    }
    
    @Override
    public List<TableMetadata> listTables(String schema) {
        List<TableMetadata> tables = new ArrayList<>();
        
        String sql = "SELECT TABLE_NAME FROM QSYS2.SYSTABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'T' ORDER BY TABLE_NAME";
        String effectiveSchema = (schema != null && !schema.trim().isEmpty()) ? schema : config.database();
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, effectiveSchema);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    
                    TableMetadata metadata = new TableMetadata(
                        tableName,
                        effectiveSchema,
                        DbType.DB2_IBMI,
                        List.of(), // Columns populated lazily
                        List.of(), // Primary key populated lazily  
                        List.of(), // Foreign keys populated lazily
                        null // Extended metadata
                    );
                    tables.add(metadata);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list tables for schema {}: {}", effectiveSchema, e.getMessage());
            throw new RuntimeException("Failed to list tables", e);
        }
        
        return tables;
    }
    
    @Override
    public TableMetadata describeTable(String schema, String table) {
        // Get columns
        List<TableMetadata.ColumnInfo> columns = getColumns(schema, table);
        
        // Get primary key
        List<String> primaryKey = getPrimaryKey(schema, table);
        
        // Get foreign keys
        List<TableMetadata.ForeignKeyInfo> foreignKeys = getForeignKeys(schema, table);
        
        return new TableMetadata(
            table,
            schema,
            DbType.DB2_IBMI,
            columns,
            primaryKey,
            foreignKeys,
            null // DB2 doesn't have extended metadata like shard keys
        );
    }
    
    @Override
    public String executeSelect(String query) throws Exception {
        SqlGuards.validateSelectOnly(query);
        
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            
            // Build result
            List<Map<String, Object>> rows = new ArrayList<>();
            
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    Object value = rs.getObject(i);
                    row.put(columnName, value);
                }
                rows.add(row);
            }
            
            // Return JSON response
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("data", Map.of(
                "columns", getColumnNames(metaData),
                "rows", rows,
                "row_count", rows.size()
            ));
            
            return mapper.writeValueAsString(response);
            
        } catch (SQLException e) {
            Map<String, Object> errorResponse = new LinkedHashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            errorResponse.put("error_type", "QUERY_ERROR");
            
            return mapper.writeValueAsString(errorResponse);
        }
    }
    
    @Override
    public ConnectionConfig getConfig() {
        return config;
    }
    
    @Override
    public void close() {
        // DB2 connections are handled per-operation, no persistent connection to close
    }
    
    @Override
    public String getLastError() {
        return lastError;
    }
    
    @Override
    public String getLastErrorType() {
        return lastErrorType;
    }
    
    @Override
    public String getLastSqlState() {
        return lastSqlState;
    }
    
    @Override
    public int getLastErrorCode() {
        return lastErrorCode;
    }
    
    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    private List<TableMetadata.ColumnInfo> getColumns(String schema, String table) {
        List<TableMetadata.ColumnInfo> columns = new ArrayList<>();
        
        String sql = """
            SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH,
                   IS_NULLABLE, COLUMN_DEFAULT
            FROM QSYS2.SYSCOLUMNS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
            """;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, schema);
            stmt.setString(2, table);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    String dataType = rs.getString("DATA_TYPE");
                    int maxLength = rs.getInt("CHARACTER_MAXIMUM_LENGTH");
                    String isNullable = rs.getString("IS_NULLABLE");

                    // Build full type string
                    String fullType = dataType;
                    if (maxLength > 0 && dataType.contains("CHAR")) {
                        fullType += "(" + maxLength + ")";
                    }

                    columns.add(new TableMetadata.ColumnInfo(
                        columnName,
                        fullType,
                        "YES".equalsIgnoreCase(isNullable),
                        ""
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get columns for {}.{}: {}", schema, table, e.getMessage());
            throw new RuntimeException("Failed to get columns", e);
        }
        
        return columns;
    }
    
    private List<String> getPrimaryKey(String schema, String table) {
        List<String> pkColumns = new ArrayList<>();
        
        String sql = """
            SELECT K.COLUMN_NAME
            FROM QSYS2.SYSCST C
            JOIN QSYS2.SYSKEYCST K
                ON C.CONSTRAINT_SCHEMA = K.CONSTRAINT_SCHEMA
               AND C.CONSTRAINT_NAME = K.CONSTRAINT_NAME
            WHERE C.CONSTRAINT_SCHEMA = ? AND C.TABLE_NAME = ?
              AND C.CONSTRAINT_TYPE = 'PRIMARY KEY'
            ORDER BY K.ORDINAL_POSITION
            """;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, schema);
            stmt.setString(2, table);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    pkColumns.add(rs.getString("COLUMN_NAME"));
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to get primary key for {}.{}: {}", schema, table, e.getMessage());
        }
        
        return pkColumns;
    }
    
    private List<TableMetadata.ForeignKeyInfo> getForeignKeys(String schema, String table) {
        List<TableMetadata.ForeignKeyInfo> foreignKeys = new ArrayList<>();
        
        String sql = """
            SELECT C.CONSTRAINT_NAME AS CONSTRAINT_NAME,
                   K.COLUMN_NAME AS COLUMN_NAME,
                   P.TABLE_NAME AS REFERENCED_TABLE_NAME,
                   PK.COLUMN_NAME AS REFERENCED_COLUMN_NAME
            FROM QSYS2.SYSCST C
            JOIN QSYS2.SYSKEYCST K
                ON C.CONSTRAINT_SCHEMA = K.CONSTRAINT_SCHEMA
               AND C.CONSTRAINT_NAME = K.CONSTRAINT_NAME
            JOIN QSYS2.SYSREFCST R
                ON C.CONSTRAINT_SCHEMA = R.CONSTRAINT_SCHEMA
               AND C.CONSTRAINT_NAME = R.CONSTRAINT_NAME
            JOIN QSYS2.SYSCST P
                ON R.UNIQUE_CONSTRAINT_SCHEMA = P.CONSTRAINT_SCHEMA
               AND R.UNIQUE_CONSTRAINT_NAME = P.CONSTRAINT_NAME
            JOIN QSYS2.SYSKEYCST PK
                ON P.CONSTRAINT_SCHEMA = PK.CONSTRAINT_SCHEMA
               AND P.CONSTRAINT_NAME = PK.CONSTRAINT_NAME
               AND PK.ORDINAL_POSITION = K.ORDINAL_POSITION
            WHERE C.CONSTRAINT_SCHEMA = ? AND C.TABLE_NAME = ?
              AND C.CONSTRAINT_TYPE = 'FOREIGN KEY'
            ORDER BY C.CONSTRAINT_NAME, K.ORDINAL_POSITION
            """;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, schema);
            stmt.setString(2, table);
            
            Map<String, List<String>> fkColumns = new LinkedHashMap<>();
            Map<String, String> fkTables = new LinkedHashMap<>();
            Map<String, List<String>> fkRefColumns = new LinkedHashMap<>();
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String constraintName = rs.getString("CONSTRAINT_NAME");
                    String columnName = rs.getString("COLUMN_NAME");
                    String refTable = rs.getString("REFERENCED_TABLE_NAME");
                    String refColumn = rs.getString("REFERENCED_COLUMN_NAME");
                    
                    fkColumns.computeIfAbsent(constraintName, k -> new ArrayList<>()).add(columnName);
                    fkTables.put(constraintName, refTable);
                    fkRefColumns.computeIfAbsent(constraintName, k -> new ArrayList<>()).add(refColumn);
                }
            }
            
            // Build foreign key info
            for (Map.Entry<String, List<String>> entry : fkColumns.entrySet()) {
                String constraintName = entry.getKey();
                List<String> columns = entry.getValue();
                String refTable = fkTables.get(constraintName);
                List<String> refColumns = fkRefColumns.get(constraintName);
                
                foreignKeys.add(new TableMetadata.ForeignKeyInfo(
                    constraintName,
                    columns,
                    refTable,
                    refColumns
                ));
            }
        } catch (SQLException e) {
            log.warn("Failed to get foreign keys for {}.{}: {}", schema, table, e.getMessage());
        }
        
        return foreignKeys;
    }
    
    private List<String> getColumnNames(ResultSetMetaData metaData) throws SQLException {
        List<String> columnNames = new ArrayList<>();
        int columnCount = metaData.getColumnCount();
        
        for (int i = 1; i <= columnCount; i++) {
            columnNames.add(metaData.getColumnName(i));
        }
        
        return columnNames;
    }
}
