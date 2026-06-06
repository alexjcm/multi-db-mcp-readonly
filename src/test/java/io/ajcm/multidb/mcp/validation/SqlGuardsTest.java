package io.ajcm.multidb.mcp.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlGuardsTest {

    @Test
    void validateSelectOnlyAcceptsPlainSelect() {
        assertEquals("SELECT * FROM users", SqlGuards.validateSelectOnly("SELECT * FROM users"));
    }

    @Test
    void validateSelectOnlyAcceptsWithClause() {
        String sql = "WITH temp AS (SELECT id FROM users) SELECT * FROM temp";
        assertEquals(sql, SqlGuards.validateSelectOnly(sql));
    }

    @Test
    void validateSelectOnlyRejectsNonSelectStatements() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> SqlGuards.validateSelectOnly("INSERT INTO users VALUES ('test')"));

        assertEquals("Only SELECT statements are allowed", exception.getMessage());
    }

    @Test
    void validateSelectOnlyRejectsMultipleStatements() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> SqlGuards.validateSelectOnly("SELECT * FROM users; SELECT * FROM orders"));

        assertEquals("Multiple statements or comments are not allowed", exception.getMessage());
    }

    @Test
    void ensureSchemaAllowsQualifiedSchemaMatch() {
        String sql = "SELECT * FROM MYSCHEMA.users";
        assertEquals(sql, SqlGuards.ensureSchema(sql, "MYSCHEMA"));
    }

    @Test
    void ensureSchemaRejectsDisallowedSchema() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> SqlGuards.ensureSchema("SELECT * FROM OTHERSCHEMA.users", "MYSCHEMA"));

        assertEquals("Query must reference one of schemas: MYSCHEMA", exception.getMessage());
    }

    @Test
    void extractLimitReturnsFetchFirstValue() {
        assertEquals(10, SqlGuards.extractLimit("SELECT * FROM users FETCH FIRST 10 ROWS ONLY"));
    }

    @Test
    void extractLimitReturnsNullWhenMissing() {
        assertNull(SqlGuards.extractLimit("SELECT * FROM users"));
    }

    @Test
    void applyLimitAddsFetchFirstWhenMissing() {
        assertEquals("SELECT * FROM users FETCH FIRST 50 ROWS ONLY",
                SqlGuards.applyLimit("SELECT * FROM users", 50));
    }
}
