package com.sonsure.synclavis4j.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import com.sonsure.synclavis4j.state.AppState;
import com.sonsure.synclavis4j.state.GlobalSettings;
import com.sonsure.synclavis4j.state.RepoSettings;
import com.sonsure.synclavis4j.state.SessionState;
import com.sonsure.synclavis4j.state.StateStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地插件扫描与权限门禁（见《详细设计文档》§6.4）。
 */
class PluginServiceTest {

    @TempDir
    Path temp;

    private FakeStateStore stateStore;
    private PluginService pluginService;

    @BeforeEach
    void setUp() {
        stateStore = new FakeStateStore();
        pluginService = new PluginService(
                new ObjectMapper(), stateStore, temp.resolve("plugins").toString());
    }

    @Test
    void scansManifestAndDefaultsToDisabled() throws IOException {
        writePlugin("acme.plugin", manifest("acme.plugin", "[\"repo:read\"]", "[]"));

        PluginListDto list = pluginService.list();

        assertEquals(1, list.getPlugins().size());
        PluginInfo info = list.getPlugins().get(0);
        assertEquals("acme.plugin", info.getId());
        assertEquals("Acme 插件", info.getName());
        assertTrue(info.isCompatible());
        assertFalse(info.isEnabled());
        assertEquals("/plugin-assets/acme.plugin/", info.getAssetBase());
    }

    @Test
    void enabledPluginContributesCapabilities() throws IOException {
        writePlugin("acme.panel", manifest("acme.panel", "[\"repo:read\"]", "[\"ai.panel\"]"));

        pluginService.setEnabled("acme.panel", true);

        assertTrue(pluginService.list().getPlugins().get(0).isEnabled());
        assertTrue(pluginService.capabilities().getAi().isPanel());
        assertFalse(pluginService.capabilities().getTerminal().isPanel());
        assertTrue(stateStore.load().getGlobalSettings().getPlugins()
                .getEnabled().contains("acme.panel"));
    }

    @Test
    void terminalCapabilityAndPermissionFollowEnabledPlugin() throws IOException {
        writePlugin("acme.terminal",
                manifest("acme.terminal", "[\"repo:read\",\"terminal\"]", "[\"terminal.panel\"]"));

        assertFalse(pluginService.hasEnabledPermission("terminal"));
        assertFalse(pluginService.hasEnabledCapability("terminal.panel"));

        pluginService.setEnabled("acme.terminal", true);

        assertTrue(pluginService.hasEnabledPermission("terminal"));
        assertTrue(pluginService.hasEnabledCapability("terminal.panel"));
        assertTrue(pluginService.capabilities().getTerminal().isPanel());

        pluginService.setEnabled("acme.terminal", false);

        assertFalse(pluginService.hasEnabledPermission("terminal"));
        assertFalse(pluginService.hasEnabledCapability("terminal.panel"));
        assertFalse(pluginService.capabilities().getTerminal().isPanel());
    }

    @Test
    void shellAnyRequiresDeveloperModeAndIsRevokedWhenDisabled() throws IOException {
        writePlugin("acme.shell", manifest("acme.shell", "[\"shell:any\"]", "[]"));

        PluginInfo blocked = pluginService.list().getPlugins().get(0);
        assertFalse(blocked.isCompatible());
        assertTrue(blocked.getError().contains("开发者模式"));
        assertThrows(GitOperationException.class,
                () -> pluginService.setEnabled("acme.shell", true));

        pluginService.setDeveloperMode(true);
        PluginInfo allowed = pluginService.list().getPlugins().get(0);
        assertTrue(allowed.isCompatible());
        pluginService.setEnabled("acme.shell", true);
        assertTrue(pluginService.list().getPlugins().get(0).isEnabled());

        pluginService.setDeveloperMode(false);
        PluginInfo revoked = pluginService.list().getPlugins().get(0);
        assertFalse(revoked.isEnabled());
        assertFalse(revoked.isCompatible());
    }

    @Test
    void rejectsMissingEntryAndTraversal() throws IOException {
        writePlugin("acme.broken", "{\"id\":\"acme.broken\",\"name\":\"Broken\","
                + "\"version\":\"1.0.0\",\"apiVersion\":1,\"entry\":\"../outside.js\"}");
        PluginInfo broken = pluginService.list().getPlugins().get(0);
        assertFalse(broken.isCompatible());
        assertTrue(broken.getError().contains("entry"));

        writePlugin("acme.plugin", manifest("acme.plugin", "[\"repo:read\"]", "[]"));
        pluginService.reload();
        assertNotNull(pluginService.resolveAsset("acme.plugin", "dist/index.js"));
        assertNull(pluginService.resolveAsset("acme.plugin", "../../state.json"));
        assertNull(pluginService.resolveAsset("acme.plugin", "dist"));
    }

    @Test
    void persistsActiveViewsAndUnknownEnableFails() throws IOException {
        writePlugin("acme.plugin", manifest("acme.plugin", "[\"repo:read\"]", "[]"));

        LinkedHashMap<String, String> views = new LinkedHashMap<String, String>();
        views.put("rightDock", "acme.plugin:main");
        pluginService.setActiveViews(views);

        assertEquals("acme.plugin:main", pluginService.list().getActiveViews().get("rightDock"));
        GitOperationException error = assertThrows(GitOperationException.class,
                () -> pluginService.setEnabled("missing.plugin", true));
        assertEquals(ErrorCode.NOT_FOUND, error.getCode());
    }

    private void writePlugin(String id, String manifest) throws IOException {
        Path dir = temp.resolve("plugins").resolve(id);
        Files.createDirectories(dir.resolve("dist"));
        Files.write(dir.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("dist/index.js"),
                "export default {}".getBytes(StandardCharsets.UTF_8));
    }

    private String manifest(String id, String permissions, String capabilities) {
        return "{\"id\":\"" + id + "\",\"name\":\"Acme 插件\",\"version\":\"1.0.0\","
                + "\"apiVersion\":1,\"entry\":\"dist/index.js\","
                + "\"permissions\":" + permissions + ","
                + "\"capabilities\":" + capabilities + "}";
    }

    private static class FakeStateStore implements StateStore {

        private final AppState state = new AppState();

        @Override
        public AppState load() {
            return state;
        }

        @Override
        public void save(AppState state) {
            // 单测只关心内存状态
        }

        @Override
        public void updateSession(SessionState session) {
            state.setSession(session);
        }

        @Override
        public void updateGlobalSettings(GlobalSettings settings) {
            state.setGlobalSettings(settings);
        }

        @Override
        public void updateRepoSettings(String repoId, RepoSettings settings) {
            state.getRepos().put(repoId, settings);
        }
    }
}
