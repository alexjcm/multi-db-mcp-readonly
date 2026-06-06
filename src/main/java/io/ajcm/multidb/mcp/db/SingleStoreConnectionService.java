package io.ajcm.multidb.mcp.db;

import io.ajcm.multidb.mcp.config.ConnectionConfig;
import io.ajcm.multidb.mcp.config.DbType;
import io.ajcm.multidb.mcp.validation.SqlGuards;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * SingleStore connection provider implementing DbConnectionProvider.
 * Provides read-only access to SingleStore databases with extended metadata support.
 */
public class SingleStoreConnectionService implements DbConnectionProvider {
    private static final Logger log = LoggerFactory.getLogger(SingleStoreConnectionService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String JDBC_DRIVER_CLASS = "com.singlestore.jdbc.Driver";
    
    private final ConnectionConfig config;
    private final String jdbcUrl;
    
    // Error details for observability
    private String lastError;
    private String lastErrorType;
    private String lastSqlState;
    private int lastErrorCode;
    
    public SingleStoreConnectionService(ConnectionConfig config) {
        if (config.type() != DbType.SINGLESTORE) {
            throw new IllegalArgumentException("SingleStoreConnectionService only supports SINGLESTORE type");
        }
        
        this.config = config;
        
        // Build JDBC URL
        this.jdbcUrl = String.format("jdbc:singlestore://%s:%d/%s", 
            config.host(), config.port(), config.database());

        registerDriver();
    }
    
    @Override
    public boolean healthCheck() {
        try (Connection conn = getConnection()) {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            log.warn("SingleStore health check failed for {}: {}", config.id(), e.getMessage());
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
        
        String sql = "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME";
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
                        DbType.SINGLESTORE,
                        List.of(), // Columns populated lazily
                        List.of(), // Primary key populated lazily  
                        List.of(), // Foreign keys populated lazily
                        null // Extended metadata populated lazily
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
        
        // Get extended metadata (shard/sort keys)
        Map<String, Object> extended = getExtendedMetadata(schema, table);
        
        return new TableMetadata(
            table,
            schema,
            DbType.SINGLESTORE,
            columns,
            primaryKey,
            foreignKeys,
            extended
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
        // SingleStore connections are handled per-operation, no persistent connection to close
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
        log.info("DIAGNOSTIC: SingleStore connecting - ID: {}, User: '{}', Password length: {}, Host: {}, Port: {}, Database: {}", 
                 config.id(), config.user(), config.password().length(), config.host(), config.port(), config.database());
        log.info("DIAGNOSTIC: SingleStore JDBC URL: {}", jdbcUrl);
        
        Properties props = new Properties();
        props.setProperty("user", config.user());
        props.setProperty("password", config.password());
        if (config.ssl()) {
            props.setProperty("useSSL", "true");
        }
        
        log.info("DIAGNOSTIC: SingleStore Properties configured - User in props: '{}', Password in props: '{}'", 
                 props.getProperty("user"), props.getProperty("password").isEmpty() ? "EMPTY" : "SET");
        
        return DriverManager.getConnection(jdbcUrl, props);
    }

    private static void registerDriver() {
        try {
            Class.forName(JDBC_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SingleStore JDBC driver is not available on the classpath", e);
        }
    }
    
    private List<TableMetadata.ColumnInfo> getColumns(String schema, String table) {
        List<TableMetadata.ColumnInfo> columns = new ArrayList<>();
        
        String sql = """
            SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH,
                   IS_NULLABLE, COLUMN_DEFAULT, COLUMN_KEY
            FROM information_schema.COLUMNS
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
                    String columnKey = rs.getString("COLUMN_KEY");
                    
                    // Build full type string
                    String fullType = dataType;
                    if (maxLength > 0 && dataType.contains("CHAR")) {
                        fullType += "(" + maxLength + ")";
                    }
                    
                    columns.add(new TableMetadata.ColumnInfo(
                        columnName,
                        fullType,
                        "YES".equalsIgnoreCase(isNullable),
                        columnKey != null ? columnKey : ""
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
            SELECT COLUMN_NAME
            FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? 
              AND CONSTRAINT_NAME = 'PRIMARY'
            ORDER BY ORDINAL_POSITION
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
            SELECT CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
            FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
              AND REFERENCED_TABLE_NAME IS NOT NULL
            ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION
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
    
    private Map<String, Object> getExtendedMetadata(String schema, String table) {
        Map<String, Object> extended = new HashMap<>();
        
        try (Connection conn = getConnection()) {
            // Get SHOW CREATE TABLE to parse shard/sort keys
            String sql = "SHOW CREATE TABLE `" + schema + "`.`" + table + "`";
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                
                if (rs.next()) {
                    String createTableDDL = rs.getString("Create Table");
                    
                    // Parse shard key and sort key using the parser we created
                    Map<String, List<String>> metadata = io.ajcm.multidb.mcp.util.SingleStoreDDLParser.parseExtendedMetadata(createTableDDL);
                    
                    if (metadata.containsKey("shard_key")) {
                        extended.put("shard_key", metadata.get("shard_key"));
                    }
                    
                    if (metadata.containsKey("sort_key")) {
                        extended.put("sort_key", metadata.get("sort_key"));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to get extended metadata for {}.{}: {}", schema, table, e.getMessage());
        }
        
        return extended.isEmpty() ? null : extended;
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
