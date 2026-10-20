import type { Component } from 'vue';

/** 插件视图可落位的插槽；activitybar 预留给后续版本 */
export type PluginSlotName = 'rightDock' | 'bottomDock';

export type PluginSource = 'local' | 'bundled' | 'downloaded';

export type PluginPermission =
  | 'repo:read'
  | 'repo:write'
  | 'ui:notify'
  | 'shell:repo'
  | 'shell:any'
  | 'terminal';

export type PluginNotificationType = 'info' | 'success' | 'warning' | 'error';

export interface PluginEngines {
  synclavis?: string;
}

export interface PluginUi {
  isolation?: 'in-process' | 'sandbox';
}

export interface PluginManifestView {
  id: string;
  title: string;
  slot: PluginSlotName;
  icon?: string;
  order?: number;
}

export interface PluginManifestBackend {
  protocol?: number;
  transport?: 'stdio' | 'http';
  command?: string;
  args?: string[];
}

/** 与后端 PluginManifest 对应；未知字段忽略 */
export interface PluginManifest {
  id: string;
  name: string;
  version: string;
  apiVersion: number;
  publisher?: string;
  description?: string;
  engines?: PluginEngines;
  entry?: string;
  style?: string;
  ui?: PluginUi;
  permissions?: PluginPermission[];
  capabilities?: string[];
  contributes?: {
    views?: PluginManifestView[];
  };
  backend?: PluginManifestBackend;
}

export interface PluginInfo {
  id: string;
  name: string;
  version: string;
  apiVersion: number | null;
  publisher?: string;
  description?: string;
  source: PluginSource;
  enabled: boolean;
  compatible: boolean;
  error: string | null;
  permissions: PluginPermission[];
  capabilities: string[];
  assetBase: string;
  manifest: PluginManifest | null;
}

export interface PluginList {
  developerMode: boolean;
  pluginsDir: string;
  activeViews: Record<string, string>;
  plugins: PluginInfo[];
}

export interface Capabilities {
  plugins: {
    local: boolean;
    developerMode: boolean;
    signed: boolean;
  };
  ai: {
    panel: boolean;
  };
  terminal: {
    panel: boolean;
  };
}

export interface Disposable {
  dispose(): void;
}

/** 插件注册的视图；component 必须是异步 loader，便于宿主统一做错误边界 */
export interface PluginViewRegistration {
  id: string;
  title: string;
  slot: PluginSlotName;
  icon?: string;
  order?: number;
  component: () => Promise<Component>;
}

export interface RegisteredPluginView extends PluginViewRegistration {
  /** 全局唯一 key：pluginId + ':' + view.id */
  key: string;
  pluginId: string;
}

export interface PluginCommandRegistration {
  id: string;
  handler: (...args: unknown[]) => unknown;
}

export interface PluginContext {
  pluginId: string;
  manifest: PluginManifest;
  views: {
    register(view: PluginViewRegistration): Disposable;
  };
  commands: {
    register(command: PluginCommandRegistration): Disposable;
  };
  services: {
    repos: {
      activeId(): string | null;
      onDidChangeActive(listener: (repoId: string | null) => void): Disposable;
      /** 当前已打开的仓库 id 列表（按 Tab 顺序）。 */
      openIds(): string[];
      /** 打开/关闭仓库后回调完整 id 列表；用于释放已关闭仓库的插件资源。 */
      onDidChangeOpen(listener: (repoIds: string[]) => void): Disposable;
    };
    ui: {
      notify(message: string, type?: PluginNotificationType): void;
    };
  };
  /** 插件自己的订阅集合；宿主在停用时统一 dispose */
  subscriptions: Disposable[];
}

export interface PluginDefinition {
  activate(context: PluginContext): void | Promise<void>;
  deactivate?(): void | Promise<void>;
}

/** 外部插件入口约定：`export default definePlugin({ ... })` */
export function definePlugin(definition: PluginDefinition): PluginDefinition {
  return definition;
}
