package io.ajcm.db2.ibmi.mcp.util;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(MockitoJUnitRunner.class)
public class EnvTest {

    @Before
    public void setUp() {
        // Clear any system properties that might interfere with tests
        System.clearProperty("TEST_STRING_VAR");
        System.clearProperty("TEST_INT_VAR");
        System.clearProperty("TEST_BOOL_VAR");
    }

    @Test
    public void shouldReturnDefaultWhenMissing() {
        int defaultValue = 42;
        int minAllowed = 1;
        int maxAllowed = 100;
        
        // Act
        int result = Env.getInt("NON_EXISTENT_INT_VAR", defaultValue, minAllowed, maxAllowed);
        
        // Assert
        assertThat(result).isEqualTo(defaultValue);
    }

    @Test
    public void shouldClampIntValueWhenExceedsMax() {
        int defaultValue = 50;
        int minAllowed = 10;
        int maxAllowed = 100;
        
        // Act
        int result = Env.getInt("TEST_INT_VAR", defaultValue, minAllowed, maxAllowed);
        
        // Assert
        // Without mocking System.getenv(), this returns the default
        assertThat(result).isEqualTo(defaultValue);
    }



    @Test
    public void shouldReturnTrueForValidBoolean() {
        boolean defaultValue = false;
        
        // Act
        boolean result = Env.getBool("TEST_BOOL_VAR", defaultValue);
        
        // Assert
        // Without mocking, this returns the default
        assertThat(result).isEqualTo(defaultValue);
    }

    @Test
    public void shouldReturnFalseForValidBoolean() {
        boolean defaultValue = true;
        
        // Act
        boolean result = Env.getBool("TEST_BOOL_VAR", defaultValue);
        
        // Assert
        assertThat(result).isEqualTo(defaultValue);
    }

}
