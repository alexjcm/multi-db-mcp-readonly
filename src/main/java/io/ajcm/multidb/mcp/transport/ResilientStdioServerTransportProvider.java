/*
 * Based on the MCP Java SDK StdioServerTransportProvider.
 * Copyright 2024-2024 the original author or authors.
 * SPDX-License-Identifier: MIT
 */

package io.ajcm.multidb.mcp.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * STDIO server transport hardened for concurrent responses and abrupt client EOF.
 *
 * <p>This is a compatibility layer for MCP Java SDK 2.0.1. It preserves the SDK
 * transport behavior while serializing concurrent outbound emissions and consuming
 * terminal reactive errors so they cannot surface as ErrorCallbackNotImplemented.</p>
 */
public final class ResilientStdioServerTransportProvider implements McpServerTransportProvider {
    private static final int DEFAULT_INPUT_MAX_SIZE = 16 * 1024 * 1024;
    private static final Duration OUTBOUND_EMIT_RETRY = Duration.ofSeconds(1);
    private static final Logger log =
        LoggerFactory.getLogger(ResilientStdioServerTransportProvider.class);

    private final McpJsonMapper jsonMapper;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final int inputMaxSize;
    private final AtomicBoolean isClosing = new AtomicBoolean(false);
    private final Sinks.One<Void> inboundReady = Sinks.one();

    private McpServerSession session;

    /**
     * Creates a transport over the process standard streams.
     *
     * @param jsonMapper mapper used for MCP messages
     */
    public ResilientStdioServerTransportProvider(McpJsonMapper jsonMapper) {
        this(jsonMapper, System.in, System.out);
    }

    /**
     * Creates a transport over explicit streams, primarily for integration testing.
     *
     * @param jsonMapper mapper used for MCP messages
     * @param inputStream stream carrying client messages
     * @param outputStream stream receiving server messages
     */
    public ResilientStdioServerTransportProvider(
            McpJsonMapper jsonMapper,
            InputStream inputStream,
            OutputStream outputStream) {
        this(jsonMapper, inputStream, outputStream, DEFAULT_INPUT_MAX_SIZE);
    }

    /**
     * Creates a transport with a maximum inbound message size.
     *
     * @param jsonMapper mapper used for MCP messages
     * @param inputStream stream carrying client messages
     * @param outputStream stream receiving server messages
     * @param inputMaxSize maximum characters accepted in one newline-delimited message
     */
    public ResilientStdioServerTransportProvider(
            McpJsonMapper jsonMapper,
            InputStream inputStream,
            OutputStream outputStream,
            int inputMaxSize) {
        Assert.notNull(jsonMapper, "The JsonMapper can not be null");
        Assert.notNull(inputStream, "The InputStream can not be null");
        Assert.notNull(outputStream, "The OutputStream can not be null");
        Assert.isTrue(inputMaxSize > 0, "inputMaxSize must be positive");

        this.jsonMapper = jsonMapper;
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.inputMaxSize = inputMaxSize;
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        var transport = new StdioMcpSessionTransport();
        this.session = sessionFactory.create(transport);
        transport.initProcessing();
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (this.session == null) {
            return Mono.error(new IllegalStateException("No session to notify"));
        }
        return this.session.sendNotification(method, params)
            .doOnError(error -> logIfNotClosing("Failed to send notification", error));
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return Mono.defer(() -> {
            if (this.session == null) {
                return Mono.error(new IllegalStateException("No session to notify"));
            }
            if (!this.session.getId().equals(sessionId)) {
                return Mono.error(new IllegalStateException(
                    "Existing session id " + this.session.getId()
                        + " doesn't match the notification target: " + sessionId));
            }
            return this.session.sendNotification(method, params);
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        if (this.session == null) {
            return Mono.empty();
        }
        return this.session.closeGracefully();
    }

    private void logIfNotClosing(String message, Throwable error) {
        if (!isClosing.get()) {
            log.error(message, error);
        }
    }

    private final class StdioMcpSessionTransport implements McpServerTransport {
        private final Sinks.Many<JSONRPCMessage> inboundSink =
            Sinks.many().unicast().onBackpressureBuffer();
        private final Sinks.Many<JSONRPCMessage> outboundSink =
            Sinks.many().unicast().onBackpressureBuffer();
        private final AtomicBoolean isStarted = new AtomicBoolean(false);
        private final Sinks.One<Void> outboundReady = Sinks.one();

        private final Scheduler inboundScheduler = Schedulers.fromExecutorService(
            Executors.newSingleThreadExecutor(), "stdio-inbound");
        private final Scheduler outboundScheduler = Schedulers.fromExecutorService(
            Executors.newSingleThreadExecutor(), "stdio-outbound");

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.zip(inboundReady.asMono(), outboundReady.asMono())
                .then(Mono.defer(() -> {
                    if (isClosing.get()) {
                        return Mono.empty();
                    }

                    try {
                        outboundSink.emitNext(
                            message,
                            Sinks.EmitFailureHandler.busyLooping(OUTBOUND_EMIT_RETRY)
                        );
                        return Mono.empty();
                    } catch (Sinks.EmissionException e) {
                        if (isClosing.get()) {
                            return Mono.empty();
                        }
                        return Mono.error(new IllegalStateException(
                            "Failed to enqueue MCP message", e));
                    }
                }));
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return jsonMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> {
                isClosing.set(true);
                log.debug("Session transport closing gracefully");
                inboundSink.tryEmitComplete();
            });
        }

        @Override
        public void close() {
            isClosing.set(true);
            log.debug("Session transport closed");
        }

        private void initProcessing() {
            handleIncomingMessages();
            startInboundProcessing();
            startOutboundProcessing();
        }

        private void handleIncomingMessages() {
            inboundSink.asFlux()
                .flatMap(message -> session.handle(message)
                    .onErrorResume(error -> {
                        logIfNotClosing("Error handling inbound MCP message", error);
                        return Mono.empty();
                    }))
                .doFinally(signal -> {
                    outboundSink.tryEmitComplete();
                    inboundScheduler.dispose();
                })
                .subscribe(ignored -> { }, error ->
                    logIfNotClosing("Inbound MCP processing terminated", error));
        }

        private void startInboundProcessing() {
            if (!isStarted.compareAndSet(false, true)) {
                return;
            }

            inboundScheduler.schedule(() -> {
                inboundReady.tryEmitEmpty();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    while (!isClosing.get()) {
                        try {
                            String line = readLine(reader, inputMaxSize);
                            if (line == null || isClosing.get()) {
                                break;
                            }

                            log.debug("Received JSON message: {}", line);
                            JSONRPCMessage message =
                                McpSchema.deserializeJsonRpcMessage(jsonMapper, line);
                            if (!inboundSink.tryEmitNext(message).isSuccess()) {
                                break;
                            }
                        } catch (MaxSizeExceededException e) {
                            logIfNotClosing("Inbound message exceeds the maximum allowed size", e);
                            break;
                        } catch (IOException e) {
                            logIfNotClosing("Error reading from stdin", e);
                            break;
                        } catch (Exception e) {
                            logIfNotClosing("Error processing inbound message", e);
                            break;
                        }
                    }
                } catch (IOException e) {
                    logIfNotClosing("Error closing stdin reader", e);
                } finally {
                    isClosing.set(true);
                    if (session != null) {
                        session.close();
                    }
                    inboundSink.tryEmitComplete();
                }
            });
        }

        private void startOutboundProcessing() {
            Function<Flux<JSONRPCMessage>, Flux<JSONRPCMessage>> outboundConsumer = messages ->
                messages
                    .doOnSubscribe(subscription -> outboundReady.tryEmitEmpty())
                    .publishOn(outboundScheduler)
                    .handle((message, sink) -> {
                        if (message != null && !isClosing.get()) {
                            try {
                                String jsonMessage = jsonMapper.writeValueAsString(message)
                                    .replace("\r\n", "\\n")
                                    .replace("\n", "\\n")
                                    .replace("\r", "\\n");

                                synchronized (outputStream) {
                                    outputStream.write(jsonMessage.getBytes(StandardCharsets.UTF_8));
                                    outputStream.write('\n');
                                    outputStream.flush();
                                }
                                sink.next(message);
                            } catch (IOException e) {
                                if (isClosing.get()) {
                                    log.debug("Stream closed during shutdown", e);
                                } else {
                                    sink.error(new IllegalStateException("Error writing MCP message", e));
                                }
                            }
                        } else if (isClosing.get()) {
                            sink.complete();
                        }
                    })
                    .doOnComplete(() -> {
                        isClosing.set(true);
                        outboundScheduler.dispose();
                    })
                    .doOnError(error -> {
                        logIfNotClosing("Error in outbound processing", error);
                        isClosing.set(true);
                        outboundScheduler.dispose();
                    })
                    .map(message -> (JSONRPCMessage) message);

            outboundConsumer.apply(outboundSink.asFlux())
                .subscribe(ignored -> { }, error -> {
                    // doOnError above records the failure; consume it to avoid
                    // Reactor's ErrorCallbackNotImplemented wrapper.
                });
        }

        private static String readLine(BufferedReader reader, int maxSize)
                throws IOException, MaxSizeExceededException {
            StringBuilder value = new StringBuilder();
            int character;
            while ((character = reader.read()) != -1) {
                if (character == '\n') {
                    return value.toString();
                }
                if (character == '\r') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next != '\n' && next != -1) {
                        reader.reset();
                    }
                    return value.toString();
                }
                if (value.length() >= maxSize) {
                    throw new MaxSizeExceededException(
                        "Inbound message exceeds the maximum allowed size of "
                            + maxSize + " characters");
                }
                value.append((char) character);
            }
            return value.isEmpty() ? null : value.toString();
        }
    }

    private static final class MaxSizeExceededException extends Exception {
        private MaxSizeExceededException(String message) {
            super(message);
        }
    }
}
