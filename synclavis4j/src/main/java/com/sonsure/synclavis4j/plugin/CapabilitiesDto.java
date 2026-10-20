package com.sonsure.synclavis4j.plugin;

/**
 * {@code GET /api/capabilities} 的响应数据。
 *
 * <p>前端只据此判断「能不能显示某个入口」，不直接推断插件是否安装。</p>
 */
public class CapabilitiesDto {

    private Plugins plugins = new Plugins();
    private Ai ai = new Ai();
    private Terminal terminal = new Terminal();

    public Plugins getPlugins() {
        return plugins;
    }

    public void setPlugins(Plugins plugins) {
        this.plugins = plugins == null ? new Plugins() : plugins;
    }

    public Ai getAi() {
        return ai;
    }

    public void setAi(Ai ai) {
        this.ai = ai == null ? new Ai() : ai;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public void setTerminal(Terminal terminal) {
        this.terminal = terminal == null ? new Terminal() : terminal;
    }

    public static class Plugins {
        private boolean local = true;
        private boolean developerMode;
        private boolean signed;

        public boolean isLocal() {
            return local;
        }

        public void setLocal(boolean local) {
            this.local = local;
        }

        public boolean isDeveloperMode() {
            return developerMode;
        }

        public void setDeveloperMode(boolean developerMode) {
            this.developerMode = developerMode;
        }

        public boolean isSigned() {
            return signed;
        }

        public void setSigned(boolean signed) {
            this.signed = signed;
        }
    }

    public static class Ai {
        private boolean panel;

        public boolean isPanel() {
            return panel;
        }

        public void setPanel(boolean panel) {
            this.panel = panel;
        }
    }

    public static class Terminal {
        private boolean panel;

        public boolean isPanel() {
            return panel;
        }

        public void setPanel(boolean panel) {
            this.panel = panel;
        }
    }
}
