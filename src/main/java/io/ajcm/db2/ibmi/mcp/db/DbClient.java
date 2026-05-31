package io.ajcm.db2.ibmi.mcp.db;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.ibm.as400.access.AS400JDBCDataSource;
import io.ajcm.db2.ibmi.mcp.util.Env;
import io.ajcm.db2.ibmi.mcp.util.KeySet;
import io.ajcm.db2.ibmi.mcp.validation.SqlGuards;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin JDBC client for DB2 for i (IBM i) read-only operations.
 */
public class DbClient {
    private static final Logger log = LoggerFactory.getLogger(DbClient.class);
    private final String host;
    private final String user;
    private final String password;
    private final String schema;
    private final int port;
    private final boolean ssl;
    private final AS400JDBCDataSource dataSource;

    /**
     * Default constructor for DbClient.
     */
    public DbClient() {
        this(DbConfig.fromEnv());
    }

    /**
     * Constructor with configuration for DbClient.
     * 
     * @param cfg the database configuration
     */
    public DbClient(DbConfig cfg) {
        this.host = cfg.host();
        this.user = cfg.user();
        this.password = cfg.password();
        this.schema = cfg.schema();
        this.ssl = cfg.ssl();
        this.port = cfg.port();
        this.dataSource = cfg.toDataSource();
    }

    private String jdbcUrl() {
        return new DbConfig(host, user, password, schema, ssl, port).jdbcUrl();
    }

    private Connection getConnection(int loginTimeoutSec) throws SQLException {
        try {
            Class.forName("com.ibm.as400.access.AS400JDBCDriver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("AS400 JDBC driver not found on classpath", e);
        }
        // Prefer DataSource for obtaining connections; if it fails, fallback to
        // DriverManager.
        try {
            dataSource.setLoginTimeout(loginTimeoutSec);
            return dataSource.getConnection();
        } catch (SQLException ex) {
            DriverManager.setLoginTimeout(loginTimeoutSec);
            return DriverManager.getConnection(jdbcUrl(), user, password);
        }
    }

    /**
     * Checks database connection health.
     * 
     * @return true if healthy, false otherwise
     */
    public boolean health() throws SQLException {
        // Overall guard: run the entire health routine (DNS + socket + JDBC) with a
        // bounded timeout.
        // Rationale: OS DNS resolution can block beyond socket/JDBC timeouts. Wrapping
        // everything
        // ensures the tool never hangs longer than DB_HEALTH_TIMEOUT_SEC.
        int healthTimeoutSec = Env.getInt(KeySet.DB_HEALTH_TIMEOUT_SEC, 30, 1, 60);
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "db-health-overall");
            t.setDaemon(true);
            return t;
        });
        Future<Boolean> fut = exec.submit(() -> {
            // 1) Fast preflight: DNS + socket connect with small timeout
            int socketTimeoutSec = Math.min(healthTimeoutSec, 3); // keep this very small for fast fail
            try {
                InetAddress addr = InetAddress.getByName(host);
                String ip = addr.getHostAddress();
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(ip, port), socketTimeoutSec * 1000);
                } catch (IOException ioe) {
                    log.warn("Socket connect check: FAILED to {}:{} -> {}", ip, port, ioe.getMessage());
                    return false;
                }
            } catch (UnknownHostException uhe) {
                log.warn("DNS resolve failed for host {}: {}", host, uhe.getMessage());
                return false;
            }

            // 2) JDBC: prefer Connection.isValid with a very small timeout; fallback to
            // lightweight SELECT
            int loginTimeoutSec = Env.getInt(KeySet.DB_LOGIN_TIMEOUT_SEC, 5, 1, 60);
            try (Connection conn = getConnection(loginTimeoutSec)) {
                int validTimeoutSec = Math.max(1, socketTimeoutSec);
                try {
                    if (conn.isValid(validTimeoutSec))
                        return true;
                } catch (Throwable t) {
                    // Some drivers may not support isValid; fallback below
                    log.debug("Connection.isValid not supported or failed: {}", t.getMessage());
                }
                try (Statement st = conn.createStatement()) {
                    st.setQueryTimeout(validTimeoutSec);
                    try (ResultSet rs = st
                            .executeQuery("SELECT 1 FROM sysibm.sysdummy1 WITH UR FETCH FIRST 1 ROWS ONLY")) {
                        return rs.next();
                    }
                }
            }
        });
        try {
            Boolean ok = fut.get(healthTimeoutSec, TimeUnit.SECONDS);
            return ok != null && ok;
        } catch (TimeoutException te) {
            log.warn("Overall health check timed out after {}s (DNS/socket/JDBC)", healthTimeoutSec);
            fut.cancel(true);
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof SQLException se)
                throw se;
            log.warn("Health check failed: {}", cause == null ? ee.getMessage() : cause.getMessage());
            return false;
        } finally {
            exec.shutdownNow();
            try {
                exec.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException ie2) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Retrieves table columns metadata.
     * 
     * @param tableName the table name
     * @return list of column definitions
     */
    public List<Map<String, Object>> getColumns(String tableName) throws SQLException {
        int loginTimeoutSec = Env.getInt(KeySet.DB_LOGIN_TIMEOUT_SEC, 5, 1, 60);
        try (Connection conn = getConnection(loginTimeoutSec)) {
            DatabaseMetaData meta = conn.getMetaData();
            // Determine schema and table (support qualified SCHEMA.TABLE)
            String schemaUpper = this.schema.toUpperCase(Locale.ROOT);
            String tableUpper = tableName.toUpperCase(Locale.ROOT);
            int dot = tableUpper.indexOf('.');
            if (dot > 0 && dot < tableUpper.length() - 1) {
                schemaUpper = tableUpper.substring(0, dot);
                tableUpper = tableUpper.substring(dot + 1);
            }
            List<Map<String, Object>> columns = new ArrayList<>();
            try (ResultSet rs = meta.getColumns(null, schemaUpper, tableUpper, "%")) {
                while (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("name", rs.getString("COLUMN_NAME"));
                    col.put("dataType", rs.getString("TYPE_NAME"));
                    col.put("nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    columns.add(col);
                }
            }
            return columns;
        }
    }

    /**
     * Retrieves tables matching a pattern.
     * 
     * @param searchPattern the table search pattern
     * @return list of table metadata
     */
    public List<Map<String, Object>> getTables(String searchPattern) throws SQLException {
        int loginTimeoutSec = Env.getInt(KeySet.DB_LOGIN_TIMEOUT_SEC, 5, 1, 60);
        try (Connection conn = getConnection(loginTimeoutSec)) {
            DatabaseMetaData meta = conn.getMetaData();
            String schemaUpper = this.schema.toUpperCase(Locale.ROOT);
            String pattern = searchPattern == null || searchPattern.isBlank() ? "%"
                    : searchPattern.toUpperCase(Locale.ROOT);
            int dot = pattern.indexOf('.');
            if (dot > 0 && dot < pattern.length() - 1) {
                schemaUpper = pattern.substring(0, dot);
                pattern = pattern.substring(dot + 1);
            }
            List<Map<String, Object>> tables = new ArrayList<>();
            try (ResultSet rs = meta.getTables(null, schemaUpper, pattern, new String[] { "TABLE", "VIEW" })) {
                while (rs.next()) {
                    Map<String, Object> table = new LinkedHashMap<>();
                    table.put("name", rs.getString("TABLE_NAME"));
                    table.put("type", rs.getString("TABLE_TYPE"));
                    table.put("remarks", rs.getString("REMARKS"));
                    tables.add(table);
                }
            }
            return tables;
        }
    }

    /**
     * Cursor state for list_tables keyset pagination.
     *
     * @param schema schema being browsed
     * @param tablePattern normalized LIKE pattern being browsed
     * @param lastTableName last table name from the previous page
     * @param lastTableType last table type from the previous page
     */
    public record TablePageCursor(String schema, String tablePattern, String lastTableName, String lastTableType) {
    }

    /**
     * Paginated list_tables result.
     *
     * @param tables current page items
     * @param totalCount total matching objects across all pages
     * @param hasMore whether more pages are available
     * @param nextCursor cursor for the next page, null when no more results exist
     */
    public record TablesPage(List<Map<String, Object>> tables, long totalCount, boolean hasMore,
            TablePageCursor nextCursor) {
    }

    /**
     * Retrieves a paginated slice of tables and views using keyset pagination against
     * QSYS2.SYSTABLES. The total count is only recomputed when no hint is provided.
     *
     * @param searchPattern optional table pattern or schema-qualified pattern
     * @param limit page size already validated by the caller
     * @param cursor pagination cursor from the previous page
     * @param totalCountHint total count from a prior page, or null to compute it
     * @return paginated tables page
     */
    public TablesPage getTablesPage(String searchPattern, int limit, TablePageCursor cursor, Long totalCountHint)
            throws SQLException {
        SearchScope scope = resolveSearchScope(searchPattern);
        if (cursor != null) {
            if (!scope.schema().equals(cursor.schema()) || !scope.tablePattern().equals(cursor.tablePattern())) {
                throw new IllegalArgumentException(
                        "Cursor does not match the current schema or searchPattern. Reuse the same filters or restart without cursor.");
            }
        }

        int loginTimeoutSec = Env.getInt(KeySet.DB_LOGIN_TIMEOUT_SEC, 5, 1, 60);
        int queryTimeoutSec = Env.getInt(KeySet.DB_QUERY_TIMEOUT_SEC, KeySet.SELECT_DEFAULT_TIMEOUT_SECONDS, 1, 60);
        try (Connection conn = getConnection(loginTimeoutSec)) {
            long totalCount = totalCountHint != null ? totalCountHint.longValue() : countTables(conn, scope, queryTimeoutSec);

            StringBuilder sql = new StringBuilder();
            sql.append("""
                    SELECT TABLE_SCHEMA,
                           TABLE_NAME,
                           CASE TABLE_TYPE
                               WHEN 'T' THEN 'TABLE'
                               WHEN 'V' THEN 'VIEW'
                               ELSE TABLE_TYPE
                           END AS TABLE_KIND,
                           TABLE_TEXT
                      FROM QSYS2.SYSTABLES
                     WHERE TABLE_SCHEMA = ?
                       AND TABLE_TYPE IN ('T', 'V')
                       AND TABLE_NAME LIKE ?
                    """);
            if (cursor != null) {
                sql.append("""
                       AND (
                               TABLE_NAME > ?
                            OR (TABLE_NAME = ? AND TABLE_TYPE > ?)
                           )
                        """);
            }
            sql.append(" ORDER BY TABLE_NAME, TABLE_TYPE FETCH FIRST ").append(limit + 1).append(" ROWS ONLY");

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int index = 1;
                ps.setString(index++, scope.schema());
                ps.setString(index++, scope.tablePattern());
                if (cursor != null) {
                    ps.setString(index++, cursor.lastTableName());
                    ps.setString(index++, cursor.lastTableName());
                    ps.setString(index++, cursor.lastTableType());
                }
                ps.setQueryTimeout(queryTimeoutSec);

                List<Map<String, Object>> tables = new ArrayList<>();
                boolean hasMore = false;
                TablePageCursor nextCursor = null;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (tables.size() == limit) {
                            hasMore = true;
                            break;
                        }
                        Map<String, Object> table = new LinkedHashMap<>();
                        table.put("schema", rs.getString("TABLE_SCHEMA"));
                        table.put("name", rs.getString("TABLE_NAME"));
                        table.put("type", rs.getString("TABLE_KIND"));
                        table.put("remarks", rs.getString("TABLE_TEXT"));
                        tables.add(table);
                    }
                }

                if (hasMore && !tables.isEmpty()) {
                    Map<String, Object> last = tables.get(tables.size() - 1);
                    nextCursor = new TablePageCursor(scope.schema(), scope.tablePattern(),
                            String.valueOf(last.get("name")), toCatalogTableType(String.valueOf(last.get("type"))));
                }

                return new TablesPage(tables, totalCount, hasMore, nextCursor);
            }
        }
    }

    /**
     * Returns primary key metadata for the given table or null if no PK exists.
     */
    /**
     * Retrieves primary key for a table.
     * 
     * @param tableName the table name
     * @return map containing primary key metadata
     */
    public Map<String, Object> getPrimaryKey(String tableName) throws SQLException {
        int loginTimeoutSec = Env.getInt(KeySet.DB_LOGIN_TIMEOUT_SEC, 5, 1, 60);
        try (Connection conn = getConnection(loginTimeoutSec)) {
            DatabaseMetaData meta = conn.getMetaData();
            String schemaUpper = this.schema.toUpperCase(Locale.ROOT);
            String tableUpper = tableName.toUpperCase(Locale.ROOT);
            int dot = tableUpper.indexOf('.');
            if (dot > 0 && dot < tableUpper.length() - 1) {
                schemaUpper = tableUpper.substring(0, dot);
                tableUpper = tableUpper.substring(dot + 1);
            }
            List<Map<String, Object>> cols = new ArrayList<>();
            String pkName = null;
            try (ResultSet rs = meta.getPrimaryKeys(null, schemaUpper, tableUpper)) {
                while (rs.next()) {
                    if (pkName == null) {
                        pkName = rs.getString("PK_NAME");
                    }
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("name", rs.getString("COLUMN_NAME"));
                    c.put("position", rs.getInt("KEY_SEQ"));
                    cols.add(c);
                }
            }
            if (pkName == null || cols.isEmpty()) {
                return null;
            }
            // sort by position
            cols.sort(Comparator.comparingInt(m -> (Integer) m.get("position")));
            Map<String, Object> pk = new LinkedHashMap<>();
            pk.put("name", pkName);
            // Return only column names (omit position from output)
            List<Map<String, Object>> cleanCols = new ArrayList<>();
            for (Map<String, Object> m : cols) {
                Map<String, Object> onlyName = new LinkedHashMap<>();
                onlyName.put("name", m.get("name"));
                cleanCols.add(onlyName);
            }
            pk.put("columns", cleanCols);
            return pk;
        }
    }

    /**
     * Returns foreign keys imported by the given table.
     */
    /**
     * Retrieves foreign keys for a table.
     * 
     * @param tableName the table name
     * @return list of foreign key metadata
     */
    public List<Map<String, Object>> getForeignKeys(String tableName) throws SQLException {
        int loginTimeoutSec = Env.getInt(KeySet.DB_LOGIN_TIMEOUT_SEC, 5, 1, 60);
        try (Connection conn = getConnection(loginTimeoutSec)) {
            DatabaseMetaData meta = conn.getMetaData();
            String schemaUpper = this.schema.toUpperCase(Locale.ROOT);
            String tableUpper = tableName.toUpperCase(Locale.ROOT);
            int dot = tableUpper.indexOf('.');
            if (dot > 0 && dot < tableUpper.length() - 1) {
                schemaUpper = tableUpper.substring(0, dot);
                tableUpper = tableUpper.substring(dot + 1);
            }

            // Group rows by FK_NAME
            Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
            try (ResultSet rs = meta.getImportedKeys(null, schemaUpper, tableUpper)) {
                while (rs.next()) {
                    String fkName = rs.getString("FK_NAME");
                    if (fkName == null || fkName.isBlank()) {
                        fkName = String.format("FK_%s_%s_%d", rs.getString("FKTABLE_NAME"),
                                rs.getString("FKCOLUMN_NAME"), rs.getInt("KEY_SEQ"));
                    }

                    // Extract values from ResultSet BEFORE lambda to avoid checked exceptions
                    // inside lambda
                    String fkSchema = rs.getString("FKTABLE_SCHEM");
                    String fkTable = rs.getString("FKTABLE_NAME");
                    String pkSchema = rs.getString("PKTABLE_SCHEM");
                    String pkTable = rs.getString("PKTABLE_NAME");
                    // Skip mapping of referential actions; not required in the response

                    Map<String, Object> finalFk;
                    finalFk = byName.computeIfAbsent(fkName, k -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("name", k);
                        map.put("fkTable", (fkSchema != null ? fkSchema + "." : "") + fkTable);
                        map.put("pkTable", (pkSchema != null ? pkSchema + "." : "") + pkTable);
                        map.put("fkColumns", new ArrayList<Map<String, Object>>());
                        map.put("pkColumns", new ArrayList<Map<String, Object>>());
                        // intentionally omit updateRule/deleteRule as per requirements
                        return map;
                    });

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> fkCols = (List<Map<String, Object>>) finalFk.get("fkColumns");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> pkCols = (List<Map<String, Object>>) finalFk.get("pkColumns");

                    int seq = rs.getInt("KEY_SEQ");
                    Map<String, Object> fkCol = new LinkedHashMap<>();
                    fkCol.put("name", rs.getString("FKCOLUMN_NAME"));
                    fkCol.put("position", seq);
                    fkCols.add(fkCol);

                    Map<String, Object> pkCol = new LinkedHashMap<>();
                    pkCol.put("name", rs.getString("PKCOLUMN_NAME"));
                    pkCol.put("position", seq);
                    pkCols.add(pkCol);
                }
            }

            // sort columns by position for each FK and return only column names in order
            for (Map<String, Object> fk : byName.values().stream().toList()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> fkCols = (List<Map<String, Object>>) fk.get("fkColumns");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> pkCols = (List<Map<String, Object>>) fk.get("pkColumns");
                fkCols.sort(Comparator.comparingInt(m -> (Integer) m.get("position")));
                pkCols.sort(Comparator.comparingInt(m -> (Integer) m.get("position")));
                // deduplicate and strip 'position' from output, keep order by position
                List<Map<String, Object>> fkDedup = dedupColumns(fkCols);
                List<Map<String, Object>> pkDedup = dedupColumns(pkCols);
                fk.put("fkColumns", namesOnly(fkDedup));
                fk.put("pkColumns", namesOnly(pkDedup));
            }

            return new ArrayList<>(byName.values());
        }
    }

    private static List<Map<String, Object>> dedupColumns(List<Map<String, Object>> cols) {
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        for (Map<String, Object> m : cols) {
            Object n = m.get("name");
            Object p = m.get("position");
            String key = n + "#" + p;
            seen.computeIfAbsent(key, k -> m);
        }
        return new ArrayList<>(seen.values());
    }

    private static List<Map<String, Object>> namesOnly(List<Map<String, Object>> cols) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : cols) {
            Map<String, Object> only = new LinkedHashMap<>();
            only.put("name", m.get("name"));
            out.add(only);
        }
        return out;
    }

    /**
     * Executes a SELECT query safely.
     * 
     * @param sql the SQL query
     * @param timeoutSeconds the execution timeout in seconds
     * @param defaultLimit the result row limit
     * @param requiredSchema the schema to enforce
     * @return result containing columns and rows
     */
    public SelectResult selectRows(String sql, int timeoutSeconds, int defaultLimit, String requiredSchema)
            throws SQLException {
        String validated = SqlGuards.validateSelectOnly(sql);
        validated = SqlGuards.ensureSchema(validated, requiredSchema);
        Integer explicitSqlLimit = SqlGuards.extractLimit(validated);
        boolean sqlOwnLimitRespected = explicitSqlLimit != null && explicitSqlLimit <= defaultLimit;
        int visibleLimit = sqlOwnLimitRespected ? explicitSqlLimit : defaultLimit;
        int executionLimit = sqlOwnLimitRespected ? visibleLimit : visibleLimit + 1;
        validated = SqlGuards.applyLimit(validated, executionLimit);

        // Log the final SQL that will be executed
        log.info("Executing SQL (visibleLimit={}, executionLimit={}, sqlOwnLimitRespected={}): {}",
                visibleLimit, executionLimit, sqlOwnLimitRespected, validated);

        long start = System.currentTimeMillis();
        int loginTimeoutSec = Env.getInt(KeySet.DB_LOGIN_TIMEOUT_SEC, 5, 1, 60);
        int queryTimeoutSec = Env.getInt(KeySet.DB_QUERY_TIMEOUT_SEC, timeoutSeconds, 1, 60);
        try (Connection conn = getConnection(loginTimeoutSec);
                Statement st = conn.createStatement()) {
            st.setQueryTimeout(queryTimeoutSec);
            List<Map<String, Object>> columns = new ArrayList<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            int count = 0;
            try (ResultSet rs = st.executeQuery(validated)) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                // Build columns metadata once
                for (int i = 1; i <= cols; i++) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("name", md.getColumnLabel(i));
                    col.put("dataType", md.getColumnTypeName(i));
                    col.put("nullable", md.isNullable(i) == ResultSetMetaData.columnNullable);
                    columns.add(col);
                }
                boolean hasMore = false;
                while (rs.next()) {
                    if (!sqlOwnLimitRespected && rows.size() == visibleLimit) {
                        hasMore = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                    count++;
                }
                long elapsed = System.currentTimeMillis() - start;
                return new SelectResult(columns, rows, count, elapsed, visibleLimit, hasMore, hasMore);
                }
        }
    }

    /**
     * Encapsulates query execution results.
     */
    public record SelectResult(List<Map<String, Object>> columns, List<Map<String, Object>> rows, int rowCount,
            long elapsedMs, int appliedLimit, boolean hasMore, boolean truncated) {
    }

    private SearchScope resolveSearchScope(String searchPattern) {
        String schemaUpper = this.schema.toUpperCase(Locale.ROOT);
        String pattern = searchPattern == null || searchPattern.isBlank() ? "%"
                : searchPattern.toUpperCase(Locale.ROOT);
        int dot = pattern.indexOf('.');
        if (dot > 0 && dot < pattern.length() - 1) {
            schemaUpper = pattern.substring(0, dot);
            pattern = pattern.substring(dot + 1);
        }
        return new SearchScope(schemaUpper, pattern);
    }

    private long countTables(Connection conn, SearchScope scope, int queryTimeoutSec) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                  FROM QSYS2.SYSTABLES
                 WHERE TABLE_SCHEMA = ?
                   AND TABLE_TYPE IN ('T', 'V')
                   AND TABLE_NAME LIKE ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scope.schema());
            ps.setString(2, scope.tablePattern());
            ps.setQueryTimeout(queryTimeoutSec);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static String toCatalogTableType(String toolType) {
        if ("TABLE".equals(toolType)) {
            return "T";
        }
        if ("VIEW".equals(toolType)) {
            return "V";
        }
        return toolType;
    }

    private record SearchScope(String schema, String tablePattern) {
    }
}
