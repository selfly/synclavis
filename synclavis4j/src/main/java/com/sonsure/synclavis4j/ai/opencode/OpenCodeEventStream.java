package com.sonsure.synclavis4j.ai.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenCode {@code /api/event} SSE 订阅。
 *
 * <p>OpenCode 的事件流是 live-only，没有回放；断线后由适配器让当前 turn 失败，
 * 下一次 prompt 再重新建立连接。这里不做无限重连，避免误以为丢失的事件还能补回来。</p>
 */
final class OpenCodeEventStream {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeEventStream.class);

    private final OpenCodeEndpoint endpoint;
    private final ObjectMapper mapper;
    private final long timeoutMillis;
    private final Listener listener;

    private volatile boolean closed;
    private boolean notified;
    private HttpURLConnection connection;
    private Thread thread;

    OpenCodeEventStream(OpenCodeEndpoint endpoint, ObjectMapper mapper, long timeoutMillis,
                        Listener listener) {
        this.endpoint = endpoint;
        this.mapper = mapper;
        this.timeoutMillis = timeoutMillis <= 0L ? 10000L : timeoutMillis;
        this.listener = listener;
    }

    void start() {
        HttpURLConnection current = null;
        try {
            URL url = new URL(endpoint.getBaseUrl() + "/api/event");
            current = (HttpURLConnection) url.openConnection();
            current.setRequestMethod("GET");
            current.setConnectTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
            // 必须有读超时：否则 close() 无法打断阻塞的 SSE read，JDK8 的
            // HttpURLConnection.disconnect() 还会与读线程争锁导致卡死。
            current.setReadTimeout(1000);
            current.setRequestProperty("Accept", "text/event-stream");
            current.setRequestProperty("Cache-Control", "no-cache");
            String auth = OpenCodeHttp.basicAuth(endpoint.getPassword());
            if (auth != null) {
                current.setRequestProperty("Authorization", auth);
            }
            int status = current.getResponseCode();
            if (status != 200) {
                String body = OpenCodeHttp.readBody(current.getErrorStream());
                String message = OpenCodeHttp.errorMessage(mapper, body);
                throw new OpenCodeException(status, message == null
                        ? "OpenCode 事件流返回 HTTP " + status : message);
            }
            this.connection = current;
            final InputStream input = current.getInputStream();
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop(input);
                }
            }, "synclavis-opencode-events");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException e) {
            if (current != null) {
                current.disconnect();
            }
            throw new OpenCodeException(0, "无法连接 OpenCode 事件流: " + safeMessage(e), e);
        }
    }

    void close() {
        closed = true;
        Thread currentThread = thread;
        if (currentThread != null) {
            // 由读线程在 1s 读超时后自行断开连接，避免跨线程 disconnect 死锁
            currentThread.interrupt();
        } else {
            HttpURLConnection current = connection;
            if (current != null) {
                current.disconnect();
            }
        }
    }

    boolean isClosed() {
        return closed;
    }

    private void readLoop(InputStream input) {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        try {
            while (!closed) {
                String line;
                try {
                    line = reader.readLine();
                } catch (SocketTimeoutException e) {
                    // 正常心跳间隔内的读超时，继续等待；close() 后跳出
                    continue;
                }
                if (line == null) {
                    break;
                }
                if (line.isEmpty()) {
                    dispatch(data.toString());
                    data.setLength(0);
                } else if (line.startsWith(":")) {
                    // SSE 注释/心跳
                } else if (line.startsWith("data:")) {
                    String chunk = line.substring(5);
                    if (chunk.startsWith(" ")) {
                        chunk = chunk.substring(1);
                    }
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(chunk);
                }
                // event/id/retry 字段对 OpenCode 事件没有额外意义
            }
            if (!closed) {
                notifyClosed(new IOException("OpenCode 事件流已结束"));
            }
        } catch (IOException e) {
            if (!closed) {
                notifyClosed(e);
            }
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {
                // 连接已关闭
            }
            HttpURLConnection current = connection;
            if (current != null) {
                current.disconnect();
            }
        }
    }

    private void dispatch(String data) {
        if (data == null || data.trim().isEmpty()) {
            return;
        }
        JsonNode event;
        try {
            event = mapper.readTree(data);
        } catch (IOException e) {
            log.debug("忽略无法解析的 OpenCode 事件", e);
            return;
        }
        try {
            listener.onEvent(event);
        } catch (RuntimeException e) {
            log.warn("处理 OpenCode 事件失败", e);
        }
    }

    private void notifyClosed(Throwable error) {
        synchronized (this) {
            if (notified || closed) {
                return;
            }
            notified = true;
        }
        try {
            listener.onClosed(this, error);
        } catch (RuntimeException e) {
            log.warn("通知 OpenCode 事件流关闭失败", e);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? "连接失败" : OpenCodeHttp.truncate(message, 300);
    }

    interface Listener {

        void onEvent(JsonNode event);

        void onClosed(OpenCodeEventStream stream, Throwable error);
    }
}
