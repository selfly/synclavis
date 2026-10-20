package com.sonsure.synclavis4j.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import com.sonsure.synclavis4j.state.GlobalSettings;
import com.sonsure.synclavis4j.state.PluginSettings;
import com.sonsure.synclavis4j.state.StateStore;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 本地插件目录扫描、manifest 校验与启停（见《详细设计文档》§6.4）。
 *
 * <p>v1 只支持本地目录，不下载、不动态加载后端 jar。插件启用状态落在
 * {@code globalSettings.plugins.enabled}，目录可随插件升级整体替换。</p>
 */
@Service
public class PluginService {

    private static final Logger log = LoggerFactory.getLogger(PluginService.class);

    private static final int SUPPORTED_API_VERSION = 1;
    private static final Pattern PLUGIN_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String SHELL_ANY = "shell:any";

    private final ObjectMapper mapper;
    private final StateStore stateStore;
    private final Path pluginsDir;
    private final Object scanLock = new Object();

    private volatile Map<String, ScannedPlugin> scanned;

    public PluginService(ObjectMapper mapper, StateStore stateStore,
                         @Value("${synclavis.plugins.dir:${synclavis.state.dir}/plugins}") String pluginsDir) {
        this.mapper = mapper.copy();
        this.stateStore = stateStore;
        this.pluginsDir = Paths.get(pluginsDir).toAbsolutePath().normalize();
    }

    /** 列表/重载共用：每次返回当前目录的真实快照。 */
    public PluginListDto list() {
        PluginListDto dto = new PluginListDto();
        dto.setDeveloperMode(pluginSettings().isDeveloperMode());
        dto.setPluginsDir(pluginsDir.toString());
        dto.setActiveViews(new LinkedHashMap<String, String>(pluginSettings().getActiveViews()));
        List<PluginInfo> plugins = new ArrayList<PluginInfo>();
        for (ScannedPlugin plugin : current().values()) {
            plugins.add(plugin.info);
        }
        Collections.sort(plugins, new Comparator<PluginInfo>() {
            @Override
            public int compare(PluginInfo left, PluginInfo right) {
                return left.getId().compareTo(right.getId());
            }
        });
        dto.setPlugins(plugins);
        return dto;
    }

    /** 重新扫描插件目录；已启用但已删除的插件自然从列表中消失。 */
    public PluginListDto reload() {
        synchronized (scanLock) {
            scanned = scan();
        }
        return list();
    }

    /**
     * 启用/停用插件。
     *
     * <p>本地未签名插件默认禁用；不兼容插件不允许启用，但允许停用，
     * 这样用户不会被一个坏插件卡住。</p>
     */
    public synchronized PluginInfo setEnabled(String id, boolean enabled) {
        ScannedPlugin plugin = current().get(id);
        if (plugin == null) {
            throw new GitOperationException(ErrorCode.NOT_FOUND, "插件不存在: " + id);
        }
        if (enabled && !plugin.info.isCompatible()) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM,
                    "插件不兼容，无法启用: " + nullToEmpty(plugin.info.getError()));
        }
        PluginSettings settings = pluginSettings();
        List<String> enabledIds = settings.getEnabled();
        if (enabled) {
            if (!enabledIds.contains(id)) {
                enabledIds.add(id);
            }
        } else {
            enabledIds.remove(id);
        }
        stateStore.updateGlobalSettings(stateStore.load().getGlobalSettings());
        PluginInfo updated = reload().getPlugins().stream()
                .filter(item -> id.equals(item.getId()))
                .findFirst()
                .orElse(null);
        return updated == null ? plugin.info : updated;
    }

    /**
     * 切换开发者模式。
     *
     * <p>关闭开发者模式时，自动停用所有声明 {@code shell:any} 的插件，
     * 避免权限在模式关闭后仍然生效。</p>
     */
    public synchronized boolean setDeveloperMode(boolean enabled) {
        PluginSettings settings = pluginSettings();
        settings.setDeveloperMode(enabled);
        if (!enabled) {
            List<String> enabledIds = settings.getEnabled();
            for (ScannedPlugin plugin : current().values()) {
                if (plugin.info.getPermissions().contains(SHELL_ANY)) {
                    enabledIds.remove(plugin.info.getId());
                }
            }
        }
        stateStore.updateGlobalSettings(stateStore.load().getGlobalSettings());
        reload();
        return enabled;
    }

    public CapabilitiesDto capabilities() {
        CapabilitiesDto dto = new CapabilitiesDto();
        dto.getPlugins().setDeveloperMode(pluginSettings().isDeveloperMode());
        for (ScannedPlugin plugin : current().values()) {
            if (!plugin.info.isEnabled()) {
                continue;
            }
            List<String> capabilities = plugin.info.getCapabilities();
            if (capabilities.contains("ai.panel")) {
                dto.getAi().setPanel(true);
            }
            if (capabilities.contains("ai.terminal")) {
                dto.getAi().setTerminal(true);
            }
        }
        return dto;
    }

    /** 保存插槽选择；未知插槽也允许，前端可自行扩展底部/活动栏。 */
    public synchronized Map<String, String> setActiveViews(Map<String, String> activeViews) {
        PluginSettings settings = pluginSettings();
        settings.setActiveViews(activeViews);
        stateStore.updateGlobalSettings(stateStore.load().getGlobalSettings());
        return new LinkedHashMap<String, String>(settings.getActiveViews());
    }

    /**
     * 把插件目录内的相对路径解析为真实文件。
     *
     * <p>路径穿越、符号链接逃逸、目录、不可读文件一律返回 {@code null}；
     * 调用方统一按 404 处理，不泄露插件目录结构。</p>
     */
    public Path resolveAsset(String id, String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return null;
        }
        ScannedPlugin plugin = current().get(id);
        if (plugin == null) {
            return null;
        }
        try {
            Path relative = Paths.get(relativePath);
            if (relative.isAbsolute()) {
                return null;
            }
            Path normalized = relative.normalize();
            if (normalized.startsWith("..")) {
                return null;
            }
            Path target = plugin.dir.resolve(normalized).normalize();
            if (!target.startsWith(plugin.dir) || !Files.isRegularFile(target) || !Files.isReadable(target)) {
                return null;
            }
            Path realRoot = plugin.dir.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot)) {
                return null;
            }
            return realTarget;
        } catch (InvalidPathException e) {
            return null;
        } catch (IOException e) {
            log.debug("解析插件资源失败: {} {}", id, relativePath, e);
            return null;
        }
    }

    public String getPluginsDir() {
        return pluginsDir.toString();
    }

    private PluginSettings pluginSettings() {
        return stateStore.load().getGlobalSettings().getPlugins();
    }

    private Map<String, ScannedPlugin> current() {
        Map<String, ScannedPlugin> snapshot = scanned;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (scanLock) {
            if (scanned == null) {
                scanned = scan();
            }
            return scanned;
        }
    }

    private Map<String, ScannedPlugin> scan() {
        Map<String, ScannedPlugin> result = new LinkedHashMap<String, ScannedPlugin>();
        try {
            Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            log.warn("创建插件目录失败: {}", pluginsDir, e);
            return result;
        }
        if (!Files.isDirectory(pluginsDir)) {
            log.warn("插件路径不是目录: {}", pluginsDir);
            return result;
        }

        PluginSettings settings = pluginSettings();
        boolean developerMode = settings.isDeveloperMode();
        List<String> enabledIds = settings.getEnabled();
        List<Path> candidates = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry) && !entry.getFileName().toString().startsWith(".")) {
                    candidates.add(entry);
                }
            }
        } catch (IOException e) {
            log.warn("扫描插件目录失败: {}", pluginsDir, e);
            return result;
        }
        Collections.sort(candidates, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return left.getFileName().toString().compareTo(right.getFileName().toString());
            }
        });

        for (Path dir : candidates) {
            Path manifestFile = dir.resolve(MANIFEST_FILE);
            if (!Files.isRegularFile(manifestFile)) {
                log.debug("跳过没有 manifest.json 的插件目录: {}", dir);
                continue;
            }
            String dirName = dir.getFileName().toString();
            PluginManifest manifest;
            try {
                manifest = mapper.readValue(manifestFile.toFile(), PluginManifest.class);
            } catch (IOException e) {
                PluginInfo broken = brokenInfo(dirName, "manifest.json 解析失败: " + e.getMessage());
                result.put(broken.getId(), new ScannedPlugin(broken, dir));
                continue;
            }
            if (manifest == null) {
                PluginInfo broken = brokenInfo(dirName, "manifest.json 为空");
                result.put(broken.getId(), new ScannedPlugin(broken, dir));
                continue;
            }

            PluginInfo info = toInfo(dir, manifest, developerMode, enabledIds);
            if (result.containsKey(info.getId())) {
                log.warn("插件 id 重复，已忽略: {} ({})", info.getId(), dir);
                continue;
            }
            result.put(info.getId(), new ScannedPlugin(info, dir));
        }
        return result;
    }

    private PluginInfo toInfo(Path dir, PluginManifest manifest, boolean developerMode,
                              List<String> enabledIds) {
        PluginInfo info = new PluginInfo();
        String id = trimToEmpty(manifest.getId());
        if (id.isEmpty()) {
            id = dir.getFileName().toString();
        }
        info.setId(id);
        info.setName(trimToEmpty(manifest.getName()).isEmpty() ? id : manifest.getName().trim());
        info.setVersion(trimToEmpty(manifest.getVersion()).isEmpty() ? "0.0.0" : manifest.getVersion().trim());
        info.setApiVersion(manifest.getApiVersion());
        info.setPublisher(manifest.getPublisher());
        info.setDescription(manifest.getDescription());
        info.setPermissions(manifest.getPermissions());
        info.setCapabilities(manifest.getCapabilities());
        info.setManifest(manifest);
        info.setAssetBase("/plugin-assets/" + id + "/");

        List<String> errors = new ArrayList<String>();
        if (!PLUGIN_ID.matcher(id).matches()) {
            errors.add("插件 id 非法，只能包含小写字母、数字、点、下划线和短横线");
        }
        if (manifest.getApiVersion() == null || manifest.getApiVersion() != SUPPORTED_API_VERSION) {
            errors.add("不支持的 apiVersion，需要 " + SUPPORTED_API_VERSION);
        }
        if (!safeExistingFile(dir, manifest.getEntry(), "entry")) {
            errors.add("entry 不存在或越出插件目录");
        }
        if (!safeExistingFile(dir, manifest.getStyle(), "style")) {
            errors.add("style 不存在或越出插件目录");
        }
        if (manifest.getPermissions().contains(SHELL_ANY) && !developerMode) {
            errors.add("shell:any 需要先开启开发者模式");
        }

        boolean compatible = errors.isEmpty();
        info.setCompatible(compatible);
        info.setError(compatible ? null : String.join("；", errors));
        info.setEnabled(compatible && enabledIds.contains(id));
        return info;
    }

    /**
     * 校验 manifest 里的相对路径：空值（未声明）视为通过；声明了就必须是目录内的普通文件。
     */
    private boolean safeExistingFile(Path dir, String relative, String field) {
        if (relative == null || relative.trim().isEmpty()) {
            return true;
        }
        try {
            Path path = Paths.get(relative.trim());
            if (path.isAbsolute()) {
                return false;
            }
            Path resolved = dir.resolve(path).normalize();
            return resolved.startsWith(dir) && Files.isRegularFile(resolved);
        } catch (InvalidPathException e) {
            log.debug("插件 {} 路径非法: {}", field, relative);
            return false;
        }
    }

    private PluginInfo brokenInfo(String dirName, String error) {
        PluginInfo info = new PluginInfo();
        info.setId(dirName);
        info.setName(dirName);
        info.setVersion("0.0.0");
        info.setCompatible(false);
        info.setEnabled(false);
        info.setError(error);
        info.setAssetBase("/plugin-assets/" + dirName + "/");
        return info;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class ScannedPlugin {
        private final PluginInfo info;
        private final Path dir;

        private ScannedPlugin(PluginInfo info, Path dir) {
            this.info = info;
            this.dir = dir;
        }
    }
}
