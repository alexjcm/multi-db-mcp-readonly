package io.ajcm.db2.ibmi.mcp.validation;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RunWith(MockitoJUnitRunner.class)
public class SqlGuardsTest {

    @Test
    public void shouldAcceptValidSelect() {
        String sql = "SELECT * FROM users";
        
        // Act
        String result = SqlGuards.validateSelectOnly(sql);
        
        // Assert
        assertThat(result).isEqualTo(sql);
    }

    @Test
    public void shouldRejectForbiddenKeywords() {
        // Arrange & Act & Assert
        assertThatThrownBy(() -> SqlGuards.validateSelectOnly("INSERT INTO users VALUES ('test')"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Only SELECT statements are allowed");
    }

    @Test
    public void shouldRejectMultipleStatements() {
        // Arrange & Act & Assert
        assertThatThrownBy(() -> SqlGuards.validateSelectOnly("SELECT * FROM users; SELECT * FROM orders"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Multiple statements or comments are not allowed");
    }

    @Test
    public void shouldAcceptWithClause() {
        String sql = "WITH temp AS (SELECT id FROM users) SELECT * FROM temp";
        
        // Act
        String result = SqlGuards.validateSelectOnly(sql);
        
        // Assert
        assertThat(result).isEqualTo(sql);
    }

    @Test
    public void shouldValidateSchemaAllowed() {
        String sql = "SELECT * FROM MYSCHEMA.users";
        String requiredSchema = "MYSCHEMA";
        
        // Act
        String result = SqlGuards.ensureSchema(sql, requiredSchema);
        
        // Assert
        assertThat(result).isEqualTo(sql);
    }

    @Test
    public void shouldRejectSchemaDisallowed() {
        String sql = "SELECT * FROM OTHERSCHEMA.users";
        String requiredSchema = "MYSCHEMA";
        
        // Act & Assert
        assertThatThrownBy(() -> SqlGuards.ensureSchema(sql, requiredSchema))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Query must reference one of schemas: MYSCHEMA");
    }

    @Test
    public void shouldExtractFetchFirstLimit() {
        String sql = "SELECT * FROM users FETCH FIRST 10 ROWS ONLY";
        
        // Act
        Integer result = SqlGuards.extractLimit(sql);
        
        // Assert
        assertThat(result).isEqualTo(10);
    }

    @Test
    public void shouldApplyLimitWhenMissing() {
        String sql = "SELECT * FROM users";
        int limit = 50;
        
        // Act
        String result = SqlGuards.applyLimit(sql, limit);
        
        // Assert
        assertThat(result).isEqualTo("SELECT * FROM users FETCH FIRST 50 ROWS ONLY");
    }
}
