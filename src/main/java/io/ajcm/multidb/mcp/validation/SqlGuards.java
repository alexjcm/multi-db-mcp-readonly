package io.ajcm.multidb.mcp.validation;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL validation and enforcement helpers for read-only SELECT usage.
 */
public class SqlGuards {
    private static final Pattern FETCH_FIRST_PATTERN = Pattern.compile("FETCH\\s+FIRST\\s+(\\d+)\\s+ROWS\\s+ONLY", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT_PATTERN = Pattern.compile("\\bLIMIT\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    // Forbid DML/DDL and dangerous statements using word boundaries to avoid substrings in identifiers
    private static final Pattern FORBIDDEN_KEYWORDS = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|MERGE|CALL|ALTER|DROP|TRUNCATE|CREATE|RENAME|GRANT|REVOKE)\\b",
            Pattern.CASE_INSENSITIVE);
    // Detect qualified object references like SCHEMA.OBJECT (uppercased for matching)
    private static final Pattern QUALIFIED_OBJECT = Pattern.compile("\\b[A-Z][A-Z0-9_]*\\.[A-Z][A-Z0-9_]*\\b");

    private SqlGuards() {
    }

    /**
     * Validates that the SQL is a safe SELECT statement.
     *
     * @param sql the SQL query
     * @return the trimmed and validated SQL
     */
    public static String validateSelectOnly(String sql) {
        if (sql == null) throw new IllegalArgumentException("SQL is null");
        String trimmed = sql.trim();
        // Allow a single trailing semicolon (common in clients) but still prevent multiple statements
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        // Accept classic SELECT ... or CTEs starting with WITH ... SELECT
        if (!(upper.startsWith("SELECT ") || upper.equals("SELECT") || upper.startsWith("WITH ") || upper.equals("WITH"))) {
            throw new IllegalArgumentException("Only SELECT statements are allowed");
        }
        if (upper.contains(";") || upper.contains("--") || upper.contains("/*") || upper.contains("*/")) {
            throw new IllegalArgumentException("Multiple statements or comments are not allowed");
        }
        Matcher forbid = FORBIDDEN_KEYWORDS.matcher(upper);
        if (forbid.find()) {
            throw new IllegalArgumentException("Forbidden keyword detected: " + forbid.group(1));
        }
        return trimmed;
    }

    /**
     * Ensures the query references allowed schemas.
     *
     * @param sql            the SQL query
     * @param requiredSchema the schema constraints
     * @return the validated SQL
     */
    public static String ensureSchema(String sql, String requiredSchema) {
        if (requiredSchema == null || requiredSchema.isBlank()) return sql;
        String upper = sql.toUpperCase(Locale.ROOT);
        String req = requiredSchema.trim();
        // Support comma-separated list of allowed schemas (e.g., "SCHEMAA,SCHEMAB")
        String[] allowed = req.contains(",") ? req.split(",") : new String[]{req};
        // If no qualified object (SCHEMA.OBJECT) appears in the SQL, allow it as referring to the primary/default schema
        // This enables unqualified table names for the main schema while still requiring qualification for other schemas
        Matcher qualified = QUALIFIED_OBJECT.matcher(upper);
        if (!qualified.find()) {
            return sql;
        }
        for (String sRaw : allowed) {
            String s = sRaw.trim();
            if (s.isEmpty()) continue;
            String schemaUpper = s.toUpperCase(Locale.ROOT) + ".";
            if (upper.contains(schemaUpper)) {
                return sql;
            }
        }
        throw new IllegalArgumentException("Query must reference one of schemas: " + req);
    }

    /**
     * Extracts existing limit or returns default.
     *
     * @param sql          the SQL query
     * @param defaultLimit the fallback limit
     * @return the limit to apply
     */
    public static int extractOrDefaultLimit(String sql, int defaultLimit) {
        // Prefer FETCH FIRST if present
        Matcher m = FETCH_FIRST_PATTERN.matcher(sql);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return defaultLimit;
            }
        }
        // Fallback to LIMIT if present
        Matcher lm = LIMIT_PATTERN.matcher(sql);
        if (lm.find()) {
            try {
                return Integer.parseInt(lm.group(1));
            } catch (NumberFormatException e) {
                return defaultLimit;
            }
        }
        return defaultLimit;
    }

    /**
     * Extracts an explicit limit from the SQL if present.
     *
     * @param sql the SQL query
     * @return explicit limit or null when not present
     */
    public static Integer extractLimit(String sql) {
        Matcher fetch = FETCH_FIRST_PATTERN.matcher(sql);
        if (fetch.find()) {
            return Integer.parseInt(fetch.group(1));
        }
        Matcher lim = LIMIT_PATTERN.matcher(sql);
        if (lim.find()) {
            return Integer.parseInt(lim.group(1));
        }
        return null;
    }

    /**
     * Enforces a maximum row limit on the query.
     *
     * @param sql   the SQL query
     * @param limit the maximum allowed rows
     * @return the modified SQL with limit enforced
     */
    public static String enforceLimit(String sql, int limit) {
        // If a FETCH FIRST exists, clamp it to the provided limit
        Matcher fetch = FETCH_FIRST_PATTERN.matcher(sql);
        if (fetch.find()) {
            int existing = Integer.parseInt(fetch.group(1));
            int effective = Math.min(existing, limit);
            return fetch.replaceFirst("FETCH FIRST " + effective + " ROWS ONLY");
        }

        // If a LIMIT exists, remove it and apply FETCH FIRST with min(limitFound, limit)
        Matcher lim = LIMIT_PATTERN.matcher(sql);
        if (lim.find()) {
            int found;
            try {
                found = Integer.parseInt(lim.group(1));
            } catch (NumberFormatException e) {
                found = limit;
            }
            int effective = Math.min(found, limit);
            // remove the LIMIT clause
            String withoutLimit = lim.replaceFirst("");
            String trimmed = withoutLimit.trim().replaceAll("\\s+", " ");
            if (!trimmed.endsWith(" ")) {
                trimmed = trimmed + " ";
            }
            return trimmed + "FETCH FIRST " + effective + " ROWS ONLY";
        }

        // No limit clause present: append FETCH FIRST
        String trimmed = sql.trim();
        if (!trimmed.endsWith(" ")) {
            trimmed = trimmed + " ";
        }
        return trimmed + "FETCH FIRST " + limit + " ROWS ONLY";
    }

    /**
     * Replaces an existing limit clause with the exact requested limit, or appends it
     * when none exists.
     *
     * @param sql   the SQL query
     * @param limit the exact limit to apply
     * @return SQL with the exact limit applied
     */
    public static String applyLimit(String sql, int limit) {
        Matcher fetch = FETCH_FIRST_PATTERN.matcher(sql);
        if (fetch.find()) {
            return fetch.replaceFirst("FETCH FIRST " + limit + " ROWS ONLY");
        }

        Matcher lim = LIMIT_PATTERN.matcher(sql);
        if (lim.find()) {
            String withoutLimit = lim.replaceFirst("");
            String trimmed = withoutLimit.trim().replaceAll("\\s+", " ");
            if (!trimmed.endsWith(" ")) {
                trimmed = trimmed + " ";
            }
            return trimmed + "FETCH FIRST " + limit + " ROWS ONLY";
        }

        String trimmed = sql.trim();
        if (!trimmed.endsWith(" ")) {
            trimmed = trimmed + " ";
        }
        return trimmed + "FETCH FIRST " + limit + " ROWS ONLY";
    }
}
