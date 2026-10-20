package com.sonsure.synclavis4j.ai.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonsure.synclavis4j.ai.AiEvent;
import com.sonsure.synclavis4j.ai.AiEventTypes;
import com.sonsure.synclavis4j.ai.AiPermissionRequest;
import com.sonsure.synclavis4j.ai.AiPromptRequest;
import com.sonsure.synclavis4j.ai.AiPromptResultDto;
import com.sonsure.synclavis4j.ai.AiSessionDto;
import com.sonsure.synclavis4j.ai.AiSessionService;
import com.sonsure.synclavis4j.ai.AiTestSupport;
import com.sonsure.synclavis4j.ai.CreateAiSessionRequest;
import com.sonsure.synclavis4j.ai.HarnessConformanceTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenCode 适配器通过 HarnessProvider 一致性测试，并验证事件归一化与权限路由。
 *
 * <p>使用本地假的 OpenCode HTTP+SSE 服务，不需要真实模型调用。</p>
 */
class OpenCodeHarnessProviderTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void conformsToHarnessContract() throws Exception {
        FakeOpenCodeServer server = new FakeOpenCodeServer(mapper);
        server.start();
        OpenCodeHarnessProvider provider = provider(server);
        try {
            HarnessConformanceTest.assertConforms(provider);
        } finally {
            provider.shutdown();
            server.close();
        }
    }

    @Test
    void normalizesTextToolsAndDiff() throws Exception {
        FakeOpenCodeServer server = new FakeOpenCodeServer(mapper);
        server.start();
        OpenCodeHarnessProvider provider = provider(server);
        ExecutorService executor = AiTestSupport.singleThreadExecutor();
        AiSessionService service = AiTestSupport.service(provider,
                new AiTestSupport.FakeStateStore(), executor);
        try {
            AiSessionDto session = service.createSession(new CreateAiSessionRequest());
            AiPromptRequest prompt = new AiPromptRequest();
            prompt.setText("hello");
            AiPromptResultDto result = service.prompt(session.getSessionRef(), prompt);
            AiTestSupport.AiSessions sessions = AiTestSupport.awaitTurn(
                    service, session.getSessionRef(), result.getTurnId(), 5000L);

            assertEquals("Hello OpenCode", payloadText(sessions.turn,
                    AiEventTypes.MESSAGE_COMPLETED));
            assertEquals("Hello ", firstText(sessions.turn, AiEventTypes.MESSAGE_DELTA));

            AiEvent started = first(sessions.turn, AiEventTypes.TOOL_STARTED);
            assertNotNull(started);
            assertEquals("read", started.getPayload().get("name"));
            assertTrue(started.getPayload().get("input") instanceof Map);

            AiEvent completed = first(sessions.turn, AiEventTypes.TOOL_COMPLETED);
            assertNotNull(completed);
            assertEquals(Boolean.TRUE, completed.getPayload().get("ok"));

            AiEvent diff = first(sessions.turn, AiEventTypes.DIFF_UPDATED);
            assertNotNull(diff);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files =
                    (List<Map<String, Object>>) diff.getPayload().get("files");
            assertEquals(1, files.size());
            assertEquals("README.md", files.get(0).get("path"));
            assertEquals("modified", files.get(0).get("status"));

            service.deleteSession(session.getSessionRef());
        } finally {
            provider.shutdown();
            server.close();
            AiTestSupport.shutdown(executor);
        }
    }

    @Test
    void routesPermissionDecision() throws Exception {
        FakeOpenCodeServer server = new FakeOpenCodeServer(mapper);
        server.start();
        OpenCodeHarnessProvider provider = provider(server);
        ExecutorService executor = AiTestSupport.singleThreadExecutor();
        AiSessionService service = AiTestSupport.service(provider,
                new AiTestSupport.FakeStateStore(), executor);
        try {
            AiSessionDto session = service.createSession(new CreateAiSessionRequest());
            AiPromptRequest prompt = new AiPromptRequest();
            prompt.setText("permission please");
            AiPromptResultDto result = service.prompt(session.getSessionRef(), prompt);

            waitForEvent(service, session.getSessionRef(), AiEventTypes.PERMISSION_REQUESTED);
            AiPermissionRequest decision = new AiPermissionRequest();
            decision.setDecision("allow");
            service.respondPermission(session.getSessionRef(), "per_1", decision);

            AiTestSupport.AiSessions sessions = AiTestSupport.awaitTurn(
                    service, session.getSessionRef(), result.getTurnId(), 5000L);
            assertEquals("once", server.lastPermissionDecision);
            AiEvent resolved = first(sessions.turn, AiEventTypes.PERMISSION_RESOLVED);
            assertNotNull(resolved);
            assertEquals("allow", resolved.getPayload().get("decision"));

            service.deleteSession(session.getSessionRef());
        } finally {
            provider.shutdown();
            server.close();
            AiTestSupport.shutdown(executor);
        }
    }

    @Test
    void reportsUnavailableWhenDiscoveryFileIsMissing() {
        OpenCodeHarnessProvider provider = new OpenCodeHarnessProvider(mapper,
                tempDir.resolve("missing.json").toString(), 500L);
        assertFalse(provider.descriptor().isAvailable());
        assertNotNull(provider.descriptor().getReason());
        provider.shutdown();
    }

    private OpenCodeHarnessProvider provider(FakeOpenCodeServer server) throws IOException {
        Map<String, Object> service = new LinkedHashMap<String, Object>();
        service.put("id", "service-test");
        service.put("version", "2.0.22");
        service.put("url", server.url());
        service.put("pid", Integer.valueOf(1234));
        service.put("password", server.password());
        Path serviceFile = tempDir.resolve("service.json");
        Files.write(serviceFile, mapper.writeValueAsBytes(service));
        return new OpenCodeHarnessProvider(mapper, serviceFile.toString(), 5000L);
    }

    private static void waitForEvent(AiSessionService service, String sessionRef, String type) {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            for (AiEvent event : service.events(sessionRef, 0, 1000).getEvents()) {
                if (type.equals(event.getType())) {
                    return;
                }
            }
            sleep(10L);
        }
        throw new AssertionError("等待事件超时: " + type);
    }

    private static AiEvent first(List<AiEvent> events, String type) {
        for (AiEvent event : events) {
            if (type.equals(event.getType())) {
                return event;
            }
        }
        return null;
    }

    private static Object payloadText(List<AiEvent> events, String type) {
        AiEvent event = first(events, type);
        return event == null ? null : event.getPayload().get("text");
    }

    private static Object firstText(List<AiEvent> events, String type) {
        return payloadText(events, type);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 最小 OpenCode 假服务：/api/info、会话 CRUD、prompt、事件 SSE、权限回复、diff。
     */
    private static final class FakeOpenCodeServer {

        private final ObjectMapper mapper;
        private final String password = "test-password";
        private final AtomicInteger sessionSeq = new AtomicInteger();
        private final AtomicInteger eventSeq = new AtomicInteger();
        private final Map<String, FakeSession> sessions =
                new ConcurrentHashMap<String, FakeSession>();
        private final Map<String, CountDownLatch> permissionLatches =
                new ConcurrentHashMap<String, CountDownLatch>();
        private final Set<String> interruptedSessions = Collections.newSetFromMap(
                new ConcurrentHashMap<String, Boolean>());

        private HttpServer server;
        private ExecutorService executor;
        private OutputStream eventOutput;
        private final Object eventLock = new Object();
        private final CountDownLatch eventClosed = new CountDownLatch(1);

        private volatile String lastPermissionDecision;

        private FakeOpenCodeServer(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        private void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "fake-opencode");
                    thread.setDaemon(true);
                    return thread;
                }
            });
            server.setExecutor(executor);
            server.createContext("/api/info", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    if (!authorized(exchange)) {
                        unauthorized(exchange);
                        return;
                    }
                    Map<String, Object> body = new LinkedHashMap<String, Object>();
                    body.put("version", "2.0.22");
                    body.put("pid", Integer.valueOf(1234));
                    body.put("urls", Collections.singletonList(url()));
                    body.put("paths", Collections.singletonMap("tmp", tempDir()));
                    json(exchange, 200, body);
                }
            });
            server.createContext("/api/event", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    handleEventStream(exchange);
                }
            });
            server.createContext("/api/session", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    handleSession(exchange);
                }
            });
            server.createContext("/api/session/", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    handleSession(exchange);
                }
            });
            server.start();
        }

        private void close() {
            eventClosed.countDown();
            if (server != null) {
                server.stop(0);
            }
            synchronized (eventLock) {
                eventOutput = null;
            }
            if (executor != null) {
                executor.shutdownNow();
            }
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private String password() {
            return password;
        }

        private String tempDir() {
            return System.getProperty("java.io.tmpdir");
        }

        private void handleSession(HttpExchange exchange) throws IOException {
            if (!authorized(exchange)) {
                unauthorized(exchange);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");
            if ("/api/session".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                createSession(exchange);
                return;
            }
            if (parts.length < 4) {
                notFound(exchange);
                return;
            }
            String sessionId = parts[3];
            FakeSession session = sessions.get(sessionId);
            if (session == null) {
                notFound(exchange);
                return;
            }
            if (parts.length == 4 && "DELETE".equals(exchange.getRequestMethod())) {
                sessions.remove(sessionId);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (parts.length == 5 && "prompt".equals(parts[4])) {
                prompt(exchange, session);
            } else if (parts.length == 5 && "interrupt".equals(parts[4])) {
                interrupt(exchange, session);
            } else if (parts.length == 5 && "diff".equals(parts[4])) {
                diff(exchange);
            } else if (parts.length == 5 && "model".equals(parts[4])) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            } else if (parts.length == 7 && "permission".equals(parts[4])
                    && "reply".equals(parts[6])) {
                replyPermission(exchange, session, parts[5]);
            } else {
                notFound(exchange);
            }
        }

        private void createSession(HttpExchange exchange) throws IOException {
            int number = sessionSeq.incrementAndGet();
            String id = "ses_test_" + number;
            JsonNode body = mapper.readTree(OpenCodeHttp.readBody(exchange.getRequestBody()));
            String title = OpenCodeHttp.text(body, "title");
            JsonNode location = body == null ? null : body.get("location");
            String directory = OpenCodeHttp.text(location, "directory");
            sessions.put(id, new FakeSession(id, title, directory));
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("id", id);
            data.put("title", title);
            Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
            wrapper.put("data", data);
            json(exchange, 200, wrapper);
        }

        private void prompt(HttpExchange exchange, FakeSession session) throws IOException {
            JsonNode body = mapper.readTree(OpenCodeHttp.readBody(exchange.getRequestBody()));
            final String text = body == null ? "" : OpenCodeHttp.text(body, "text");
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("id", "msg_" + eventSeq.incrementAndGet());
            Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
            wrapper.put("data", data);
            json(exchange, 200, wrapper);

            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    runPrompt(session, text == null ? "" : text);
                }
            }, "fake-opencode-prompt");
            worker.setDaemon(true);
            worker.start();
        }

        private void runPrompt(FakeSession session, String text) {
            sleep(20L);
            if (interruptedSessions.remove(session.id)) {
                emit(session, "session.execution.interrupted", new LinkedHashMap<String, Object>());
                emit(session, "session.idle", new LinkedHashMap<String, Object>());
                return;
            }
            emit(session, "session.execution.started", new LinkedHashMap<String, Object>());
            emit(session, "session.step.started", stepData(session));
            emit(session, "session.text.started", textData(session, 0));
            if (text.length() > 1000) {
                for (int i = 0; i < 200; i++) {
                    if (interruptedSessions.remove(session.id)) {
                        emit(session, "session.execution.interrupted",
                                new LinkedHashMap<String, Object>());
                        emit(session, "session.idle", new LinkedHashMap<String, Object>());
                        return;
                    }
                    Map<String, Object> delta = textData(session, 0);
                    delta.put("delta", "x");
                    emit(session, "session.text.delta", delta);
                    sleep(25L);
                }
            }
            Map<String, Object> delta = textData(session, 0);
            delta.put("delta", "Hello ");
            emit(session, "session.text.delta", delta);
            sleep(10L);
            Map<String, Object> delta2 = textData(session, 0);
            delta2.put("delta", "OpenCode");
            emit(session, "session.text.delta", delta2);
            Map<String, Object> ended = textData(session, 0);
            ended.put("text", "Hello OpenCode");
            emit(session, "session.text.ended", ended);

            Map<String, Object> toolStarted = toolData(session, "tool_1");
            toolStarted.put("name", "read");
            emit(session, "session.tool.input.started", toolStarted);

            Map<String, Object> called = toolData(session, "tool_1");
            called.put("input", Collections.singletonMap("path", "README.md"));
            called.put("executed", Boolean.TRUE);
            emit(session, "session.tool.called", called);

            Map<String, Object> success = toolData(session, "tool_1");
            success.put("content", Collections.singletonList(
                    Collections.singletonMap("text", "ok")));
            success.put("executed", Boolean.TRUE);
            emit(session, "session.tool.success", success);

            if (text.contains("permission")) {
                CountDownLatch latch = new CountDownLatch(1);
                permissionLatches.put(session.id, latch);
                Map<String, Object> permission = new LinkedHashMap<String, Object>();
                permission.put("id", "per_1");
                permission.put("sessionID", session.id);
                permission.put("action", "read");
                permission.put("resources", Collections.singletonList("README.md"));
                permission.put("message", "Allow read?");
                permission.put("source", Collections.singletonMap("type", "tool"));
                emit(session, "permission.asked", permission);
                try {
                    latch.await(5L, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                permissionLatches.remove(session.id);
                // 给核心发布 permission.resolved 留出时间，避免与 turn.completed 竞争
                sleep(100L);
            }

            // 真实的 OpenCode 2.0.22 在成功 turn 上不一定再发 session.idle，
            // 适配器必须把 execution.succeeded 当作终止信号（见 OpenCodeHarnessProvider）。
            emit(session, "session.execution.succeeded", new LinkedHashMap<String, Object>());
        }

        private void interrupt(HttpExchange exchange, FakeSession session) throws IOException {
            interruptedSessions.add(session.id);
            CountDownLatch latch = permissionLatches.get(session.id);
            if (latch != null) {
                latch.countDown();
            }
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("interrupted", Boolean.TRUE);
            Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
            wrapper.put("data", data);
            json(exchange, 200, wrapper);
        }

        private void diff(HttpExchange exchange) throws IOException {
            Map<String, Object> file = new LinkedHashMap<String, Object>();
            file.put("file", "README.md");
            file.put("patch", "@@");
            file.put("additions", Integer.valueOf(1));
            file.put("deletions", Integer.valueOf(0));
            file.put("status", "modified");
            Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
            wrapper.put("data", Collections.singletonList(file));
            json(exchange, 200, wrapper);
        }

        private void replyPermission(HttpExchange exchange, FakeSession session,
                                     String requestId) throws IOException {
            JsonNode body = mapper.readTree(OpenCodeHttp.readBody(exchange.getRequestBody()));
            lastPermissionDecision = body == null ? null : OpenCodeHttp.text(body, "decision");
            CountDownLatch latch = permissionLatches.get(session.id);
            if (latch != null) {
                latch.countDown();
            }
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("sessionID", session.id);
            data.put("requestID", requestId);
            data.put("reply", lastPermissionDecision);
            emit(session, "permission.replied", data);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }

        private void handleEventStream(HttpExchange exchange) throws IOException {
            if (!authorized(exchange)) {
                unauthorized(exchange);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            OutputStream output = exchange.getResponseBody();
            synchronized (eventLock) {
                eventOutput = output;
            }
            emitRaw("server.connected", new LinkedHashMap<String, Object>());
            try {
                eventClosed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (eventLock) {
                    eventOutput = null;
                }
                try {
                    output.close();
                } catch (IOException ignored) {
                    // 测试结束
                }
                exchange.close();
            }
        }

        private void emit(FakeSession session, String type, Map<String, Object> data) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>(data);
            payload.put("sessionID", session.id);
            emitRaw(type, payload);
        }

        private void emitRaw(String type, Map<String, Object> data) {
            Map<String, Object> event = new LinkedHashMap<String, Object>();
            event.put("id", "evt_" + eventSeq.incrementAndGet());
            event.put("created", Long.valueOf(System.currentTimeMillis()));
            event.put("type", type);
            event.put("data", data);
            try {
                String json = mapper.writeValueAsString(event);
                synchronized (eventLock) {
                    if (eventOutput != null) {
                        eventOutput.write(("data: " + json + "\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                        eventOutput.flush();
                    }
                }
            } catch (IOException e) {
                // 客户端断开时忽略
            }
            sleep(5L);
        }

        private Map<String, Object> stepData(FakeSession session) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("assistantMessageID", "msg_" + eventSeq.incrementAndGet());
            data.put("agent", "build");
            data.put("model", Collections.singletonMap("providerID", "test"));
            data.put("started", Long.valueOf(System.currentTimeMillis()));
            return data;
        }

        private Map<String, Object> textData(FakeSession session, int ordinal) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("assistantMessageID", "msg_text");
            data.put("ordinal", Integer.valueOf(ordinal));
            return data;
        }

        private Map<String, Object> toolData(FakeSession session, String id) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("assistantMessageID", "msg_text");
            data.put("id", id);
            return data;
        }

        private boolean authorized(HttpExchange exchange) {
            String actual = exchange.getRequestHeaders().getFirst("Authorization");
            String expected = "Basic " + Base64.getEncoder().encodeToString(
                    ("opencode:" + password).getBytes(StandardCharsets.UTF_8));
            return expected.equals(actual);
        }

        private void unauthorized(HttpExchange exchange) throws IOException {
            json(exchange, 401, Collections.singletonMap("message", "unauthorized"));
        }

        private void notFound(HttpExchange exchange) throws IOException {
            json(exchange, 404, Collections.singletonMap("message", "not found"));
        }

        private void json(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] bytes = mapper.writeValueAsBytes(body);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            OutputStream output = exchange.getResponseBody();
            try {
                output.write(bytes);
            } finally {
                output.close();
            }
            exchange.close();
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class FakeSession {

        private final String id;
        private final String title;
        private final String directory;

        private FakeSession(String id, String title, String directory) {
            this.id = id;
            this.title = title;
            this.directory = directory;
        }
    }
}
