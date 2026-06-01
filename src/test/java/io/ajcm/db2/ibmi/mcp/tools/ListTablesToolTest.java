package io.ajcm.db2.ibmi.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.ajcm.db2.ibmi.mcp.db.DbClient;
import io.ajcm.db2.ibmi.mcp.util.KeySet;
import org.junit.jupiter.api.Test;

class ListTablesToolTest {

    @Test
    void normalizeLimitUsesDefaultWhenMissing() {
        assertEquals(KeySet.LIST_TABLES_DEFAULT_LIMIT, ListTablesTool.normalizeLimit(null));
    }

    @Test
    void normalizeLimitCapsAtConfiguredMaximum() {
        assertEquals(KeySet.LIST_TABLES_MAX_LIMIT, ListTablesTool.normalizeLimit(999));
    }

    @Test
    void normalizeLimitClampsToAtLeastOne() {
        assertEquals(1, ListTablesTool.normalizeLimit(0));
    }

    @Test
    void cursorTokenRoundTripPreservesPaginationState() {
        DbClient.TablePageCursor cursor = new DbClient.TablePageCursor("SMXSIC", "%CLIENT%", "CLIENTES", "T");
        String token = ListTablesTool.encodeCursorToken("ECUADOR", cursor, 3157L);

        ListTablesTool.DecodedCursor decoded = ListTablesTool.decodeCursorToken(token);

        assertEquals("ECUADOR", decoded.connectionId());
        assertEquals("SMXSIC", decoded.pageCursor().schema());
        assertEquals("%CLIENT%", decoded.pageCursor().tablePattern());
        assertEquals("CLIENTES", decoded.pageCursor().lastTableName());
        assertEquals("T", decoded.pageCursor().lastTableType());
        assertEquals(3157L, decoded.totalCount());
    }

    @Test
    void invalidCursorFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> ListTablesTool.decodeCursorToken("bad-cursor"));
    }
}
