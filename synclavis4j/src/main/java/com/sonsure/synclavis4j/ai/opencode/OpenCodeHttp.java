package com.sonsure.synclavis4j.ai.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * OpenCode HTTP/SSE 的公共小工具。
 */
final class OpenCodeHttp {

    private OpenCodeHttp() {
    }

    static String basicAuth(String password) {
        if (password == null || password.isEmpty()) {
            return null;
        }
        String token = "opencode:" + password;
        return "Basic " + Base64.getEncoder().encodeToString(
                token.getBytes(StandardCharsets.UTF_8));
    }

    static String readBody(InputStream input) {
        if (input == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                builder.append(buffer, 0, read);
            }
        } catch (IOException e) {
            return builder.toString();
        }
        return builder.toString();
    }

    /**
     * OpenCode 的 id 只用于路径片段；拒绝任何可能改变路径的字符。
     */
    static String safeSegment(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("OpenCode id 不能为空");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!safe) {
                throw new IllegalArgumentException("非法的 OpenCode id");
            }
        }
        return value;
    }

    static String normalizeBaseUrl(String url) {
        if (url == null) {
            return null;
        }
        String value = url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

    /**
     * 读取正文类字段，保留空格；用于流式文本增量。
     */
    static String rawText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    static String errorMessage(ObjectMapper mapper, String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(body);
            String message = text(root, "message");
            if (message == null) {
                JsonNode error = root.get("error");
                if (error != null && error.isTextual()) {
                    message = error.asText();
                } else if (error != null) {
                    message = text(error, "message");
                }
            }
            if (message != null) {
                return truncate(message, 500);
            }
        } catch (IOException ignored) {
            // 非 JSON 错误体，按纯文本处理
        }
        return truncate(body.trim(), 500);
    }

    static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }
}
