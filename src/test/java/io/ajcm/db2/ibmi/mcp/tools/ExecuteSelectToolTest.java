package io.ajcm.db2.ibmi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ajcm.db2.ibmi.mcp.util.KeySet;
import org.junit.jupiter.api.Test;

class ExecuteSelectToolTest {

    @Test
    void normalizeLimitUsesDefaultWhenMissing() {
        assertEquals(KeySet.SELECT_DEFAULT_ROW_LIMIT, ExecuteSelectTool.normalizeLimit(null));
    }

    @Test
    void normalizeLimitCapsAtConfiguredMaximum() {
        assertEquals(KeySet.SELECT_MAX_ROW_LIMIT, ExecuteSelectTool.normalizeLimit(999));
    }

    @Test
    void normalizeLimitClampsToAtLeastOne() {
        assertEquals(1, ExecuteSelectTool.normalizeLimit(0));
    }

    @Test
    void normalizeLimitRespectsExplicitValueWithinRange() {
        assertEquals(75, ExecuteSelectTool.normalizeLimit(75));
    }
}
