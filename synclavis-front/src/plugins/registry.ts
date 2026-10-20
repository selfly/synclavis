import type {
  Disposable,
  PluginCommandRegistration,
  PluginSlotName,
  PluginViewRegistration,
  RegisteredPluginView,
} from './types';

type Listener = () => void;

interface RegisteredCommand extends PluginCommandRegistration {
  pluginId: string;
}

/**
 * 插件贡献点注册表。
 *
 * <p>不持有 Vue 响应式状态：宿主 store 订阅变更后自增版本号，
 * 这样插件注册/注销时插槽能可靠刷新。</p>
 */
class PluginRegistry {
  private readonly views = new Map<string, RegisteredPluginView>();
  private readonly commands = new Map<string, RegisteredCommand>();
  private readonly listeners = new Set<Listener>();

  registerView(pluginId: string, view: PluginViewRegistration): Disposable {
    const key = `${pluginId}:${view.id}`;
    if (this.views.has(key)) {
      throw new Error(`插件视图 id 重复: ${key}`);
    }
    this.views.set(key, { ...view, key, pluginId });
    this.emit();
    return {
      dispose: () => {
        if (this.views.delete(key)) {
          this.emit();
        }
      },
    };
  }

  registerCommand(pluginId: string, command: PluginCommandRegistration): Disposable {
    const key = `${pluginId}:${command.id}`;
    if (this.commands.has(key)) {
      throw new Error(`插件命令 id 重复: ${key}`);
    }
    this.commands.set(key, { ...command, pluginId });
    this.emit();
    return {
      dispose: () => {
        if (this.commands.delete(key)) {
          this.emit();
        }
      },
    };
  }

  /** 停用插件时一次性清掉它注册的所有贡献点 */
  removePlugin(pluginId: string): void {
    let changed = false;
    for (const [key, view] of Array.from(this.views.entries())) {
      if (view.pluginId === pluginId) {
        this.views.delete(key);
        changed = true;
      }
    }
    for (const [key, command] of Array.from(this.commands.entries())) {
      if (command.pluginId === pluginId) {
        this.commands.delete(key);
        changed = true;
      }
    }
    if (changed) {
      this.emit();
    }
  }

  viewsFor(slot: PluginSlotName): RegisteredPluginView[] {
    return Array.from(this.views.values())
      .filter((view) => view.slot === slot)
      .sort((left, right) => {
        const order = (left.order ?? 100) - (right.order ?? 100);
        return order !== 0 ? order : left.title.localeCompare(right.title);
      });
  }

  view(key: string): RegisteredPluginView | undefined {
    return this.views.get(key);
  }

  async execute(commandId: string, ...args: unknown[]): Promise<unknown> {
    const command = this.commands.get(commandId);
    if (!command) {
      throw new Error(`插件命令不存在: ${commandId}`);
    }
    return command.handler(...args);
  }

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  clear(): void {
    if (this.views.size === 0 && this.commands.size === 0) {
      return;
    }
    this.views.clear();
    this.commands.clear();
    this.emit();
  }

  private emit(): void {
    this.listeners.forEach((listener) => listener());
  }
}

export const pluginRegistry = new PluginRegistry();
