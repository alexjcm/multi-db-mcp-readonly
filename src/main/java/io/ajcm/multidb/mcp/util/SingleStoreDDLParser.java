package io.ajcm.multidb.mcp.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser para extraer SHARD KEY y SORT KEY del DDL de SingleStore
 * Basado en la sintaxis oficial de CREATE TABLE
 */
public class SingleStoreDDLParser {
    
    // Patrones regex basados en la sintaxis oficial
    private static final Pattern SHARD_KEY_PATTERN = 
        Pattern.compile("SHARD\\s+KEY\\s*(?:\\[\\w+\\])?\\s*\\(\\s*([^)]+)\\s*\\)", 
                      Pattern.CASE_INSENSITIVE);
    
    private static final Pattern SORT_KEY_PATTERN = 
        Pattern.compile("SORT\\s+KEY\\s*\\(\\s*([^)]+)\\s*\\)", 
                      Pattern.CASE_INSENSITIVE);
    
    private static final Pattern COLUMN_PATTERN = 
        Pattern.compile("`?([^`\\s,]+)`?", Pattern.CASE_INSENSITIVE);
    
    /**
     * Extrae metadata extendida del DDL de SHOW CREATE TABLE
     * @param createTableDDL - Resultado de SHOW CREATE TABLE
     * @return Map con shard_key y sort key
     */
    public static Map<String, List<String>> parseExtendedMetadata(String createTableDDL) {
        Map<String, List<String>> result = new HashMap<>();
        
        if (createTableDDL == null || createTableDDL.trim().isEmpty()) {
            return result;
        }
        
        // Extraer SHARD KEY
        Matcher shardMatcher = SHARD_KEY_PATTERN.matcher(createTableDDL);
        if (shardMatcher.find()) {
            String shardColumns = shardMatcher.group(1);
            List<String> shardKeyColumns = parseColumnList(shardColumns);
            if (!shardKeyColumns.isEmpty()) {
                result.put("shard_key", shardKeyColumns);
            }
        }
        
        // Extraer SORT KEY
        Matcher sortMatcher = SORT_KEY_PATTERN.matcher(createTableDDL);
        if (sortMatcher.find()) {
            String sortColumns = sortMatcher.group(1);
            List<String> sortKeyColumns = parseColumnList(sortColumns);
            if (!sortKeyColumns.isEmpty()) {
                result.put("sort_key", sortKeyColumns);
            }
        }
        
        return result;
    }
    
    /**
     * Parsea una lista de columnas del DDL
     * Ej: "col1, col2" -> ["col1", "col2"]
     */
    private static List<String> parseColumnList(String columnList) {
        List<String> columns = new ArrayList<>();
        if (columnList == null || columnList.trim().isEmpty()) {
            return columns;
        }
        
        Matcher columnMatcher = COLUMN_PATTERN.matcher(columnList);
        while (columnMatcher.find()) {
            String column = columnMatcher.group(1).trim();
            if (!column.isEmpty()) {
                columns.add(column);
            }
        }
        
        return columns;
    }
    
    /**
     * Test del parser con ejemplos reales
     */
    public static void main(String[] args) {
        String ddl1 = """
            CREATE TABLE `orders` (
              `id` bigint(20) NOT NULL AUTO_INCREMENT,
              `customer_id` bigint(20) NOT NULL,
              `order_date` datetime(6) DEFAULT CURRENT_TIMESTAMP(6),
              `total` decimal(10,2) NOT NULL,
              SHARD KEY (`customer_id`),
              SORT KEY (`order_date`, `id`),
              KEY (`id`)
            )
            """;
        
        String ddl2 = """
            CREATE TABLE `products` (
              `id` int(11) NOT NULL,
              `name` varchar(255) NOT NULL,
              `category_id` int(11) DEFAULT NULL,
              SHARD KEY (`id`),
              KEY (`category_id`)
            )
            """;
        
        String ddl3 = """
            CREATE TABLE `logs` (
              `id` bigint(20) NOT NULL AUTO_INCREMENT,
              `message` text,
              `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
              KEY (`id`)
            )
            """;
        
        System.out.println("=== Test SingleStore DDL Parser ===\n");
        
        System.out.println("Test 1 - Shard + Sort Key:");
        testParser(ddl1);
        
        System.out.println("\nTest 2 - Solo Shard Key:");
        testParser(ddl2);
        
        System.out.println("\nTest 3 - Sin Keys:");
        testParser(ddl3);
    }
    
    private static void testParser(String ddl) {
        Map<String, List<String>> result = parseExtendedMetadata(ddl);
        
        System.out.println("DDL: " + ddl.substring(0, Math.min(100, ddl.length())) + "...");
        
        if (result.containsKey("shard_key")) {
            System.out.println("  Shard Key: " + result.get("shard_key"));
        } else {
            System.out.println("  Shard Key: [no encontrado]");
        }
        
        if (result.containsKey("sort_key")) {
            System.out.println("  Sort Key: " + result.get("sort_key"));
        } else {
            System.out.println("  Sort Key: [no encontrado]");
        }
    }
}
