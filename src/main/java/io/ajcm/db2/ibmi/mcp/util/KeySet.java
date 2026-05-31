package io.ajcm.db2.ibmi.mcp.util;

/**
 * Configuration key constants.
 */
public final class KeySet {
    private KeySet() {}

    public static final String DB2_CONN_IDS = "DB2_CONN_IDS";
    public static final String DB2_DEFAULT_CONN_ID = "DB2_DEFAULT_CONN_ID";
    public static final String DB2_CONN_DEFAULT_ID = "DB2_CONN_DEFAULT_ID";
    public static final String DB2_SSL = "DB2_SSL";
    public static final String DB2_PORT = "DB2_PORT";

    // Timeouts (seconds)
    public static final String DB_LOGIN_TIMEOUT_SEC = "DB_LOGIN_TIMEOUT_SEC";
    public static final String DB_QUERY_TIMEOUT_SEC = "DB_QUERY_TIMEOUT_SEC";
    public static final String DB_HEALTH_TIMEOUT_SEC = "DB_HEALTH_TIMEOUT_SEC";

    // Error codes (structured error responses)
    public static final String ERR_QUERY_TIMEOUT = "QUERY_TIMEOUT";
    public static final String ERR_NET_UNREACHABLE = "NET_UNREACHABLE";
    public static final String ERR_AUTH_FAILED = "AUTH_FAILED";
    public static final String ERR_SQL_ERROR = "SQL_ERROR";
    public static final String ERR_VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String ERR_INTERNAL_ERROR = "INTERNAL_ERROR";

    // Tool defaults
    public static final int SELECT_DEFAULT_TIMEOUT_SECONDS = 10;
    public static final int SELECT_DEFAULT_ROW_LIMIT = 20;
    public static final int SELECT_MAX_ROW_LIMIT = 200;
    public static final int LIST_TABLES_DEFAULT_LIMIT = 200;
    public static final int LIST_TABLES_MAX_LIMIT = 500;
}
