package io.ajcm.multidb.mcp.config;

/**
 * Supported database types.
 */
public enum DbType {
    /**
     * IBM DB2 for i (AS/400)
     */
    DB2_IBMI("DB2 for i"),
    
    /**
     * SingleStore (MemSQL)
     */
    SINGLESTORE("SingleStore");
    
    private final String displayName;
    
    DbType(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    @Override
    public String toString() {
        return displayName;
    }
}
