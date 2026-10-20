package com.sonsure.synclavis4j.ai.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenCode 服务发现与认证。
 *
 * <p>默认读取 OpenCode 自己维护的 service.json（含 url/password），再做一次
 * {@code GET /api/info} 健康检查。凭据只保留在内存里，不写日志、不出现在 API 中。</p>
 */
final class OpenCodeDiscovery {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeDiscovery.class);

    private static final long CACHE_TTL_MILLIS = 2000L;
    private static final long UNAVAILABLE_CACHE_TTL_MILLIS = 1000L;
    private static final long MIN_HEALTH_TIMEOUT_MILLIS = 500L;
    private static final long MAX_HEALTH_TIMEOUT_MILLIS = 2000L;

    private final ObjectMapper mapper;
    private final String configuredServiceFile;
    private final OpenCodeEndpoint fixedEndpoint;
    private final long timeoutMillis;
    private Result cached;
    private long cachedAt;

    OpenCodeDiscovery(ObjectMapper mapper, String serviceFile, long timeoutMillis) {
        this(mapper, serviceFile, null, timeoutMillis);
    }

    OpenCodeDiscovery(ObjectMapper mapper, OpenCodeEndpoint fixedEndpoint, long timeoutMillis) {
        this(mapper, null, fixedEndpoint, timeoutMillis);
    }

    private OpenCodeDiscovery(ObjectMapper mapper, String serviceFile,
                              OpenCodeEndpoint fixedEndpoint, long timeoutMillis) {
        this.mapper = mapper;
        this.configuredServiceFile = serviceFile;
        this.fixedEndpoint = fixedEndpoint;
        this.timeoutMillis = timeoutMillis <= 0L ? 10000L : timeoutMillis;
    }

    synchronized Result resolve() {
        long now = System.currentTimeMillis();
        if (cached != null) {
            long ttl = cached.isAvailable() ? CACHE_TTL_MILLIS : UNAVAILABLE_CACHE_TTL_MILLIS;
            if (now - cachedAt < ttl) {
                return cached;
            }
        }
        Result result = fixedEndpoint != null
                ? check(fixedEndpoint)
                : resolveFromEnvironmentOrFile();
        cached = result;
        cachedAt = now;
        return result;
    }

    synchronized void invalidate() {
        cached = null;
        cachedAt = 0L;
    }

    private Result resolveFromEnvironmentOrFile() {
        String envUrl = firstNonBlank(System.getenv("SYNCLAVIS_OPENCODE_URL"),
                System.getenv("OPENCODE_SERVER"));
        if (envUrl != null) {
            String password = firstNonBlank(System.getenv("SYNCLAVIS_OPENCODE_PASSWORD"),
                    System.getenv("OPENCODE_PASSWORD"));
            return check(new OpenCodeEndpoint(OpenCodeHttp.normalizeBaseUrl(envUrl), password, null));
        }

        Path serviceFile = serviceFile();
        if (!Files.isRegularFile(serviceFile)) {
            return Result.unavailable("未发现运行中的 OpenCode 服务");
        }
        try {
            byte[] bytes = Files.readAllBytes(serviceFile);
            JsonNode root = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            String url = OpenCodeHttp.text(root, "url");
            String password = OpenCodeHttp.text(root, "password");
            String version = OpenCodeHttp.text(root, "version");
            if (url == null) {
                return Result.unavailable("OpenCode 服务信息不完整");
            }
            return check(new OpenCodeEndpoint(OpenCodeHttp.normalizeBaseUrl(url), password, version));
        } catch (IOException e) {
            log.debug("读取 OpenCode service.json 失败: {}", serviceFile, e);
            return Result.unavailable("无法读取 OpenCode 服务信息");
        }
    }

    private Result check(OpenCodeEndpoint endpoint) {
        if (endpoint.getBaseUrl() == null || endpoint.getBaseUrl().isEmpty()) {
            return Result.unavailable("OpenCode 服务地址为空");
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint.getBaseUrl() + "/api/info");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout((int) healthTimeoutMillis());
            connection.setReadTimeout((int) healthTimeoutMillis());
            connection.setRequestProperty("Accept", "application/json");
            String auth = OpenCodeHttp.basicAuth(endpoint.getPassword());
            if (auth != null) {
                connection.setRequestProperty("Authorization", auth);
            }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) {
                return Result.unavailable("OpenCode 认证失败，请检查服务凭据");
            }
            if (status != 200) {
                return Result.unavailable("OpenCode 服务返回 HTTP " + status);
            }
            String body = OpenCodeHttp.readBody(connection.getInputStream());
            String version = endpoint.getVersion();
            try {
                JsonNode root = mapper.readTree(body);
                String reported = OpenCodeHttp.text(root, "version");
                if (reported != null) {
                    version = reported;
                }
            } catch (IOException e) {
                log.debug("解析 OpenCode /api/info 失败", e);
            }
            if (version == null) {
                return Result.unavailable("无法确认 OpenCode 服务版本");
            }
            if (!version.startsWith("2.")) {
                return Result.unavailable("OpenCode 版本不兼容，需要 2.x，当前 " + version);
            }
            return Result.available(new OpenCodeEndpoint(endpoint.getBaseUrl(),
                    endpoint.getPassword(), version));
        } catch (IOException e) {
            log.debug("连接 OpenCode 服务失败: {}", endpoint.getBaseUrl(), e);
            return Result.unavailable("OpenCode 服务未响应");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Path serviceFile() {
        String configured = firstNonBlank(configuredServiceFile,
                System.getenv("SYNCLAVIS_OPENCODE_SERVICE_FILE"));
        if (configured != null) {
            return Paths.get(expandHome(configured));
        }
        String xdgState = System.getenv("XDG_STATE_HOME");
        if (xdgState != null && !xdgState.trim().isEmpty()) {
            return Paths.get(xdgState.trim(), "opencode", "service.json");
        }
        return Paths.get(System.getProperty("user.home"), ".local", "state",
                "opencode", "service.json");
    }

    private long healthTimeoutMillis() {
        return Math.max(MIN_HEALTH_TIMEOUT_MILLIS,
                Math.min(timeoutMillis, MAX_HEALTH_TIMEOUT_MILLIS));
    }

    private static String expandHome(String value) {
        if (value.startsWith("~/")) {
            return System.getProperty("user.home") + value.substring(1);
        }
        return value;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return null;
    }

    static final class Result {

        private final OpenCodeEndpoint endpoint;
        private final String reason;

        private Result(OpenCodeEndpoint endpoint, String reason) {
            this.endpoint = endpoint;
            this.reason = reason;
        }

        static Result available(OpenCodeEndpoint endpoint) {
            return new Result(endpoint, null);
        }

        static Result unavailable(String reason) {
            return new Result(null, reason);
        }

        boolean isAvailable() {
            return endpoint != null;
        }

        OpenCodeEndpoint getEndpoint() {
            return endpoint;
        }

        String getReason() {
            return reason;
        }
    }
}
