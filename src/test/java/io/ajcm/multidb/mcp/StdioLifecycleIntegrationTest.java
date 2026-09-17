package io.ajcm.multidb.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ajcm.multidb.mcp.config.ConnectionConfig;
import io.ajcm.multidb.mcp.config.DbType;
import io.ajcm.multidb.mcp.db.DbConnectionProvider;
import io.ajcm.multidb.mcp.db.TableMetadata;
import io.ajcm.multidb.mcp.transport.ResilientStdioServerTransportProvider;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdioLifecycleIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @Timeout(10)
    void shouldRemainSilentUntilInitializeAndExposeAllStaticTools() throws Exception {
        try (StdioHarness harness = new StdioHarness()) {
            harness.server = Main.buildServer(harness.transport, providers(), "test");

            Thread.sleep(100);
            assertEquals(0, harness.pendingOutputBytes());

            JsonNode initializeResponse = harness.initialize();
            assertEquals(1, initializeResponse.path("id").asInt());
            assertTrue(initializeResponse.has("result"));
            assertFalse(initializeResponse.has("method"));

            harness.send("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);
            JsonNode toolsResponse = harness.readMessage();

            assertEquals(2, toolsResponse.path("id").asInt());
            JsonNode tools = toolsResponse.path("result").path("tools");
            assertEquals(4, tools.size());

            Set<String> toolNames = new HashSet<>();
            tools.forEach(tool -> toolNames.add(tool.path("name").asText()));
            assertEquals(
                Set.of("health", "list_tables", "describe_table", "execute_select"),
                toolNames
            );
        }
    }

    @Test
    @Timeout(15)
    void shouldDeliverConcurrentToolResponsesWithoutDroppingMessages() throws Exception {
        int requestCount = 8;
        CountDownLatch handlersReady = new CountDownLatch(requestCount);
        CountDownLatch releaseHandlers = new CountDownLatch(1);

        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "properties", Map.of(),
            "additionalProperties", false
        );
        McpSchema.Tool tool = McpSchema.Tool.builder("concurrent_echo", inputSchema)
            .description("Returns a response after all concurrent calls are ready")
            .build();
        McpServerFeatures.SyncToolSpecification toolSpecification =
            new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
                handlersReady.countDown();
                try {
                    if (!releaseHandlers.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release concurrent handlers");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to return tool result", e);
                }
                return McpSchema.CallToolResult.builder()
                    .content(List.of(McpSchema.TextContent.builder("ok").build()))
                    .isError(false)
                    .build();
            });

        try (StdioHarness harness = new StdioHarness()) {
            harness.server = McpServer.sync(harness.transport)
                .serverInfo("concurrency-test", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(toolSpecification)
                .build();
            harness.initialize();

            Set<Integer> expectedIds = new HashSet<>();
            for (int index = 0; index < requestCount; index++) {
                int requestId = 100 + index;
                expectedIds.add(requestId);
                harness.send(String.format(
                    "{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"concurrent_echo\",\"arguments\":{}}}",
                    requestId
                ));
            }

            assertTrue(handlersReady.await(5, TimeUnit.SECONDS));
            releaseHandlers.countDown();

            Set<Integer> actualIds = new HashSet<>();
            for (int index = 0; index < requestCount; index++) {
                JsonNode response = harness.readMessage();
                actualIds.add(response.path("id").asInt());
                assertEquals("ok", response.path("result").path("content").get(0).path("text").asText());
            }

            assertEquals(expectedIds, actualIds);
        }
    }

    @Test
    @Timeout(10)
    void shouldTreatAbruptClientEofAsNormalTransportClose() throws Exception {
        List<Throwable> droppedErrors = new CopyOnWriteArrayList<>();
        Hooks.onErrorDropped(droppedErrors::add);

        try (StdioHarness harness = new StdioHarness()) {
            harness.server = Main.buildServer(harness.transport, providers(), "test");
            harness.send("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"eof-test","version":"1.0.0"}}}
                """);
            harness.closeClientInput();

            Thread.sleep(250);
            assertTrue(droppedErrors.isEmpty());
        } finally {
            Hooks.resetOnErrorDropped();
        }
    }

    private Map<String, DbConnectionProvider> providers() {
        Map<String, DbConnectionProvider> providers = new LinkedHashMap<>();
        providers.put("ecuador_db2", new StubProvider());
        return providers;
    }

    private static final class StdioHarness implements AutoCloseable {
        private final PipedInputStream serverInput = new PipedInputStream();
        private final PipedOutputStream clientInput;
        private final PipedOutputStream serverOutput = new PipedOutputStream();
        private final PipedInputStream clientOutput;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final ResilientStdioServerTransportProvider transport;

        private McpSyncServer server;

        private StdioHarness() throws IOException {
            this.clientInput = new PipedOutputStream(serverInput);
            this.clientOutput = new PipedInputStream(serverOutput, 64 * 1024);
            this.writer = new BufferedWriter(
                new OutputStreamWriter(clientInput, StandardCharsets.UTF_8));
            this.reader = new BufferedReader(
                new InputStreamReader(clientOutput, StandardCharsets.UTF_8));
            this.transport = new ResilientStdioServerTransportProvider(
                McpJsonDefaults.getMapper(), serverInput, serverOutput);
        }

        private JsonNode initialize() throws Exception {
            send("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"integration-test","version":"1.0.0"}}}
                """);
            JsonNode response = readMessage();
            send("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """);
            return response;
        }

        private void send(String message) throws IOException {
            writer.write(message.strip());
            writer.newLine();
            writer.flush();
        }

        private JsonNode readMessage() throws Exception {
            String line = reader.readLine();
            if (line == null) {
                throw new IllegalStateException("MCP server closed stdout before returning a message");
            }
            return MAPPER.readTree(line);
        }

        private int pendingOutputBytes() throws IOException {
            return clientOutput.available();
        }

        private void closeClientInput() throws IOException {
            clientInput.close();
        }

        @Override
        public void close() throws Exception {
            clientInput.close();
            if (server != null) {
                server.closeGracefully();
            }
            reader.close();
            writer.close();
        }
    }

    private static final class StubProvider implements DbConnectionProvider {
        private final ConnectionConfig config = new ConnectionConfig(
            "ecuador_db2",
            DbType.DB2_IBMI,
            "localhost",
            8471,
            "user",
            "password",
            "TESTLIB",
            false,
            "Integration test database"
        );

        @Override
        public boolean healthCheck() {
            return true;
        }

        @Override
        public List<TableMetadata> listTables(String schema) {
            return List.of();
        }

        @Override
        public TableMetadata describeTable(String schema, String table) {
            return null;
        }

        @Override
        public String executeSelect(String query) {
            return "{\"success\":true,\"data\":{\"columns\":[],\"rows\":[],\"row_count\":0}}";
        }

        @Override
        public ConnectionConfig getConfig() {
            return config;
        }

        @Override
        public void close() {
        }

        @Override
        public String getLastError() {
            return null;
        }

        @Override
        public String getLastErrorType() {
            return null;
        }

        @Override
        public String getLastSqlState() {
            return null;
        }

        @Override
        public int getLastErrorCode() {
            return 0;
        }
    }
}
