import { defineStore } from 'pinia';
import { pluginsApi } from '@/api/plugins';
import { pluginRegistry } from '@/plugins/registry';
import { RepoEventHub } from '@/plugins/repoEvents';
import type {
  Disposable,
  PluginContext,
  PluginDefinition,
  PluginInfo,
  PluginList,
  PluginNotificationType,
  PluginSlotName,
  RegisteredPluginView,
} from '@/plugins/types';
import { useTabsStore } from '@/stores/tabs';

const RIGHT_DOCK_OPEN_KEY = 'synclavis.plugins.rightDockOpen';
const BOTTOM_DOCK_OPEN_KEY = 'synclavis.plugins.bottomDockOpen';
const BOTTOM_DOCK_COLLAPSED_KEY = 'synclavis.plugins.bottomDockCollapsed';
const BOTTOM_DOCK_TAB_KEY = 'synclavis.plugins.bottomDockTab';

let notifier: (message: string, type: PluginNotificationType) => void = () => {};
let registrySubscribed = false;
const repoEvents = new RepoEventHub(() => useTabsStore());

interface ActivePlugin {
  info: PluginInfo;
  definition: PluginDefinition;
  disposables: Disposable[];
  style: HTMLLinkElement | null;
}

const activePlugins = new Map<string, ActivePlugin>();

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function joinAsset(base: string, path: string): string {
  return `${base.endsWith('/') ? base : `${base}/`}${path.replace(/^\/+/, '')}`;
}

function readRightDockOpen(): boolean {
  try {
    return localStorage.getItem(RIGHT_DOCK_OPEN_KEY) !== 'false';
  } catch {
    return true;
  }
}

function readBoolean(key: string, fallback: boolean): boolean {
  try {
    const raw = localStorage.getItem(key);
    return raw === null ? fallback : raw !== 'false';
  } catch {
    return fallback;
  }
}

function readString(key: string, fallback: string): string {
  try {
    return localStorage.getItem(key) ?? fallback;
  } catch {
    return fallback;
  }
}

function writeLocal(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    // 缓存失败不影响功能
  }
}

/** 仓库生命周期通知：插件上下文通过 {@link RepoEventHub} 订阅。 */

function appendStyle(info: PluginInfo): HTMLLinkElement | null {
  const style = info.manifest?.style;
  if (!style) {
    return null;
  }
  const existing = Array.from(
    document.querySelectorAll<HTMLLinkElement>('link[data-synclavis-plugin]'),
  ).find((link) => link.dataset.synclavisPlugin === info.id);
  if (existing) {
    return existing;
  }
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = joinAsset(info.assetBase, style);
  link.dataset.synclavisPlugin = info.id;
  document.head.appendChild(link);
  return link;
}

/**
 * 插件宿主：加载启用插件、维护贡献点、持久化插槽选择。
 *
 * <p>插件加载失败只记录在 `runtimeErrors`，不能影响工作台启动；
 * 停用时必须按注册顺序反向释放，避免插件持有的事件/样式泄漏。</p>
 */
export const usePluginsStore = defineStore('plugins', {
  state: () => ({
    items: [] as PluginInfo[],
    developerMode: false,
    pluginsDir: '',
    activeViews: {} as Record<string, string>,
    runtimeErrors: {} as Record<string, string>,
    loading: false,
    loaded: false,
    error: null as string | null,
    registryVersion: 0,
    activeIds: [] as string[],
    rightDockOpen: readRightDockOpen(),
    bottomDockOpen: readBoolean(BOTTOM_DOCK_OPEN_KEY, true),
    bottomDockCollapsed: readBoolean(BOTTOM_DOCK_COLLAPSED_KEY, true),
    bottomDockTab: readString(BOTTOM_DOCK_TAB_KEY, 'log'),
  }),

  getters: {
    enabledItems(state): PluginInfo[] {
      return state.items.filter((item) => item.enabled);
    },

    viewsFor(state) {
      return (slot: PluginSlotName): RegisteredPluginView[] => {
        // 读取版本号建立响应式依赖：注册表变化后插槽会自动重算
        void state.registryVersion;
        return pluginRegistry.viewsFor(slot);
      };
    },

    activeViewFor() {
      return (slot: PluginSlotName): RegisteredPluginView | null => {
        const views = this.viewsFor(slot);
        const activeKey = this.activeViews[slot];
        return views.find((view) => view.key === activeKey) ?? views[0] ?? null;
      };
    },

    /** 底部面板实际选中的标签；插件被停用后自动回落到操作日志。 */
    bottomDockTabResolved(state): string {
      if (state.bottomDockTab === 'log') {
        return 'log';
      }
      return this.viewsFor('bottomDock').some((view) => view.key === state.bottomDockTab)
        ? state.bottomDockTab
        : 'log';
    },
  },

  actions: {
    setNotifier(fn: (message: string, type: PluginNotificationType) => void): void {
      notifier = fn;
    },

    ensureRegistrySubscription(): void {
      if (registrySubscribed) {
        return;
      }
      registrySubscribed = true;
      pluginRegistry.subscribe(() => {
        this.registryVersion += 1;
      });
    },

    async load(force = false): Promise<void> {
      if (this.loading || (this.loaded && !force)) {
        return;
      }
      this.ensureRegistrySubscription();
      this.loading = true;
      this.error = null;
      try {
        const list = await pluginsApi.list();
        this.applyList(list);
        await this.activateEnabled();
      } catch (error) {
        this.error = messageOf(error);
      } finally {
        this.loading = false;
        this.loaded = true;
      }
    },

    async reload(): Promise<void> {
      this.ensureRegistrySubscription();
      this.loading = true;
      this.error = null;
      try {
        await this.deactivateAll();
        const list = await pluginsApi.reload();
        this.applyList(list);
        await this.activateEnabled();
      } catch (error) {
        this.error = messageOf(error);
      } finally {
        this.loading = false;
        this.loaded = true;
      }
    },

    applyList(list: PluginList): void {
      this.items = list.plugins;
      this.developerMode = list.developerMode;
      this.pluginsDir = list.pluginsDir;
      this.activeViews = list.activeViews ?? {};
    },

    async activateEnabled(): Promise<void> {
      const enabledIds = this.items
        .filter((item) => item.enabled && item.compatible)
        .map((item) => item.id);
      for (const id of Array.from(activePlugins.keys())) {
        if (!enabledIds.includes(id)) {
          await this.deactivate(id);
        }
      }
      for (const item of this.items) {
        if (item.enabled && item.compatible && !activePlugins.has(item.id)) {
          await this.activate(item);
        }
      }
    },

    async activate(info: PluginInfo): Promise<void> {
      if (activePlugins.has(info.id) || !info.enabled || !info.compatible) {
        return;
      }
      const manifest = info.manifest;
      if (!manifest) {
        this.runtimeErrors[info.id] = 'manifest 缺失，无法加载插件';
        return;
      }
      if (!manifest.entry) {
        // 纯后端插件：P0 不启动 sidecar，前端没有可加载的入口
        return;
      }

      const disposables: Disposable[] = [];
      const style = appendStyle(info);
      try {
        const entryUrl = joinAsset(info.assetBase, manifest.entry);
        const module = (await import(/* @vite-ignore */ entryUrl)) as {
          default?: PluginDefinition;
          plugin?: PluginDefinition;
        };
        const definition = module.default ?? module.plugin;
        if (!definition || typeof definition.activate !== 'function') {
          throw new Error('插件入口必须默认导出 definePlugin({ activate })');
        }

        const context: PluginContext = {
          pluginId: info.id,
          manifest,
          views: {
            register: (view) => {
              const disposable = pluginRegistry.registerView(info.id, view);
              disposables.push(disposable);
              return disposable;
            },
          },
          commands: {
            register: (command) => {
              const disposable = pluginRegistry.registerCommand(info.id, command);
              disposables.push(disposable);
              return disposable;
            },
          },
          services: {
            repos: {
              activeId: () => useTabsStore().activeId,
              openIds: () => repoEvents.openIds(),
              onDidChangeActive: (listener) => {
                const subscription = repoEvents.onDidChangeActive(listener);
                disposables.push(subscription);
                return subscription;
              },
              onDidChangeOpen: (listener) => {
                const subscription = repoEvents.onDidChangeOpen(listener);
                disposables.push(subscription);
                return subscription;
              },
            },
            ui: {
              notify: (message, type = 'info') => notifier(message, type),
            },
          },
          subscriptions: disposables,
        };

        await definition.activate(context);
        activePlugins.set(info.id, { info, definition, disposables, style });
        if (!this.activeIds.includes(info.id)) {
          this.activeIds = [...this.activeIds, info.id];
        }
        delete this.runtimeErrors[info.id];
      } catch (error) {
        disposables.slice().reverse().forEach((disposable) => {
          try {
            disposable.dispose();
          } catch {
            // 插件已经失败，释放阶段的异常不再向上抛
          }
        });
        pluginRegistry.removePlugin(info.id);
        style?.remove();
        this.runtimeErrors[info.id] = messageOf(error);
      }
    },

    async deactivate(id: string): Promise<void> {
      const active = activePlugins.get(id);
      if (!active) {
        pluginRegistry.removePlugin(id);
        return;
      }
      try {
        await active.definition.deactivate?.();
      } catch (error) {
        console.warn(`插件 ${id} deactivate 失败`, error);
      }
      active.disposables.slice().reverse().forEach((disposable) => {
        try {
          disposable.dispose();
        } catch (error) {
          console.warn(`插件 ${id} 释放资源失败`, error);
        }
      });
      pluginRegistry.removePlugin(id);
      active.style?.remove();
      activePlugins.delete(id);
      this.activeIds = this.activeIds.filter((activeId) => activeId !== id);
      delete this.runtimeErrors[id];
    },

    async deactivateAll(): Promise<void> {
      for (const id of Array.from(activePlugins.keys())) {
        await this.deactivate(id);
      }
      pluginRegistry.clear();
    },

    replaceItem(info: PluginInfo): void {
      const index = this.items.findIndex((item) => item.id === info.id);
      if (index >= 0) {
        this.items.splice(index, 1, info);
      } else {
        this.items.push(info);
      }
    },

    async setEnabled(id: string, enabled: boolean): Promise<void> {
      const info = await pluginsApi.setEnabled(id, enabled);
      this.replaceItem(info);
      if (enabled) {
        await this.activate(info);
      } else {
        await this.deactivate(id);
      }
    },

    async setDeveloperMode(enabled: boolean): Promise<void> {
      const result = await pluginsApi.setDeveloperMode(enabled);
      this.developerMode = result.enabled;
      // 后端可能因关闭开发者模式自动停用 shell:any 插件，必须重新扫描
      await this.reload();
    },

    setActiveView(slot: PluginSlotName, key: string): void {
      this.activeViews = { ...this.activeViews, [slot]: key };
      void pluginsApi.setActiveViews(this.activeViews).catch((error) => {
        console.warn('插件视图选择持久化失败', error);
      });
    },

    setRightDockOpen(open: boolean): void {
      this.rightDockOpen = open;
      writeLocal(RIGHT_DOCK_OPEN_KEY, String(open));
    },

    setBottomDockOpen(open: boolean): void {
      this.bottomDockOpen = open;
      writeLocal(BOTTOM_DOCK_OPEN_KEY, String(open));
    },

    setBottomDockCollapsed(collapsed: boolean): void {
      this.bottomDockCollapsed = collapsed;
      writeLocal(BOTTOM_DOCK_COLLAPSED_KEY, String(collapsed));
    },

    setBottomDockTab(key: string): void {
      this.bottomDockTab = key;
      writeLocal(BOTTOM_DOCK_TAB_KEY, key);
      if (key !== 'log') {
        this.setActiveView('bottomDock', key);
      }
    },

    openBottomDock(key?: string): void {
      this.setBottomDockOpen(true);
      this.setBottomDockCollapsed(false);
      if (key) {
        this.setBottomDockTab(key);
      }
    },
  },
});
