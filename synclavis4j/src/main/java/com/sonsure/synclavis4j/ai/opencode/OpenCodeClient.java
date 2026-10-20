package com.sonsure.synclavis4j.ai.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenCode V2 HTTP 客户端，仅实现适配器需要的最小端点集合。
 */
final class OpenCodeClient {

    private final OpenCodeDiscovery discovery;
    private final ObjectMapper mapper;
    private final long timeoutMillis;

    OpenCodeClient(OpenCodeDiscovery discovery, ObjectMapper mapper, long timeoutMillis) {
        this.discovery = discovery;
        this.mapper = mapper;
        this.timeoutMillis = timeoutMillis <= 0L ? 10000L : timeoutMillis;
    }

    OpenCodeDiscovery.Result discovery() {
        return discovery.resolve();
    }

    OpenCodeEndpoint requireEndpoint() {
        OpenCodeDiscovery.Result result = discovery.resolve();
        if (!result.isAvailable()) {
            throw new OpenCodeException(0, result.getReason());
        }
        return result.getEndpoint();
    }

    String createSession(String title, String directory) {
        Map<String, Object> location = new LinkedHashMap<String, Object>();
        location.put("directory", directory);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", title);
        body.put("location", location);
        JsonNode root = request("POST", "/api/session", body);
        JsonNode data = root == null ? null : root.get("data");
        String id = OpenCodeHttp.text(data, "id");
        if (id == null && root != null) {
            id = OpenCodeHttp.text(root, "id");
        }
        if (id == null) {
            throw new OpenCodeException(0, "OpenCode 未返回会话 id");
        }
        return id;
    }

    void switchModel(String sessionId, String model) {
        if (model == null || model.trim().isEmpty()) {
            return;
        }
        int slash = model.indexOf('/');
        if (slash <= 0 || slash >= model.length() - 1) {
            throw new OpenCodeException(0, "model 格式应为 providerID/modelID");
        }
        Map<String, Object> ref = new LinkedHashMap<String, Object>();
        ref.put("providerID", model.substring(0, slash));
        ref.put("id", model.substring(slash + 1));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", ref);
        request("POST", "/api/session/" + OpenCodeHttp.safeSegment(sessionId) + "/model", body);
    }

    void prompt(String sessionId, String text, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("text", text);
        if (metadata != null && !metadata.isEmpty()) {
            body.put("metadata", metadata);
        }
        request("POST", "/api/session/" + OpenCodeHttp.safeSegment(sessionId) + "/prompt", body);
    }

    void interrupt(String sessionId) {
        request("POST", "/api/session/" + OpenCodeHttp.safeSegment(sessionId)
                + "/interrupt?resume=false", null);
    }

    void replyPermission(String sessionId, String requestId, String decision) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("decision", decision);
        request("POST", "/api/session/" + OpenCodeHttp.safeSegment(sessionId)
                + "/permission/" + OpenCodeHttp.safeSegment(requestId) + "/reply", body);
    }

    List<Map<String, Object>> diff(String sessionId) {
        JsonNode root = request("GET", "/api/session/"
                + OpenCodeHttp.safeSegment(sessionId) + "/diff", null);
        JsonNode data = root == null ? null : root.get("data");
        if (data == null && root != null && root.isArray()) {
            data = root;
        }
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        if (data == null || !data.isArray()) {
            return files;
        }
        for (JsonNode node : data) {
            String path = OpenCodeHttp.text(node, "file");
            String status = OpenCodeHttp.text(node, "status");
            if (path == null) {
                continue;
            }
            Map<String, Object> file = new LinkedHashMap<String, Object>();
            file.put("path", path);
            file.put("status", status == null ? "modified" : status);
            files.add(file);
        }
        return files;
    }

    void deleteSession(String sessionId) {
        request("DELETE", "/api/session/" + OpenCodeHttp.safeSegment(sessionId), null);
    }

    OpenCodeEventStream openEventStream(OpenCodeEventStream.Listener listener) {
        OpenCodeEventStream stream = new OpenCodeEventStream(requireEndpoint(), mapper,
                timeoutMillis, listener);
        stream.start();
        return stream;
    }

    private JsonNode request(String method, String path, Object body) {
        OpenCodeEndpoint endpoint = requireEndpoint();
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint.getBaseUrl() + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
            connection.setReadTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            String auth = OpenCodeHttp.basicAuth(endpoint.getPassword());
            if (auth != null) {
                connection.setRequestProperty("Authorization", auth);
            }

            byte[] payload = null;
            if (body != null) {
                try {
                    payload = mapper.writeValueAsBytes(body);
                } catch (IOException e) {
                    throw new OpenCodeException(0, "序列化 OpenCode 请求失败", e);
                }
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            }

            if (payload != null) {
                OutputStream output = connection.getOutputStream();
                try {
                    output.write(payload);
                    output.flush();
                } finally {
                    output.close();
                }
            }

            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = OpenCodeHttp.readBody(input);
            if (status < 200 || status >= 300) {
                String message = OpenCodeHttp.errorMessage(mapper, response);
                if (message == null || message.isEmpty()) {
                    message = "OpenCode 返回 HTTP " + status;
                }
                throw new OpenCodeException(status, message);
            }
            if (response == null || response.trim().isEmpty()) {
                return null;
            }
            try {
                return mapper.readTree(response);
            } catch (IOException e) {
                throw new OpenCodeException(0, "解析 OpenCode 响应失败", e);
            }
        } catch (OpenCodeException e) {
            throw e;
        } catch (IOException e) {
            discovery.invalidate();
            throw new OpenCodeException(0, "无法连接 OpenCode 服务: " + safeMessage(e), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? "连接失败" : OpenCodeHttp.truncate(message, 300);
    }
}
