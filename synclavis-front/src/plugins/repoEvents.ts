import type { Disposable } from './types';

interface RepoEventSource {
  activeId: string | null;
  tabs: Array<{ id: string }>;
  $subscribe(callback: () => void): () => void;
}

/**
 * 把 Pinia 的仓库 Tab 变化分发给插件。
 *
 * <p>只在第一次订阅时挂 `$subscribe`；打开/关闭仓库时回调完整 id 列表，
 * 插件据此释放已关闭仓库的终端等资源。独立成类便于单元测试。</p>
 */
export class RepoEventHub {
  private readonly activeListeners = new Set<(repoId: string | null) => void>();
  private readonly openListeners = new Set<(repoIds: string[]) => void>();
  private subscribed = false;
  private lastActiveId: string | null = null;
  private lastOpenKey = '';

  constructor(private readonly source: () => RepoEventSource) {}

  openIds(): string[] {
    return this.source().tabs.map((tab) => tab.id);
  }

  onDidChangeActive(listener: (repoId: string | null) => void): Disposable {
    this.ensureSubscribed();
    this.activeListeners.add(listener);
    return {
      dispose: () => {
        this.activeListeners.delete(listener);
      },
    };
  }

  onDidChangeOpen(listener: (repoIds: string[]) => void): Disposable {
    this.ensureSubscribed();
    this.openListeners.add(listener);
    return {
      dispose: () => {
        this.openListeners.delete(listener);
      },
    };
  }

  private ensureSubscribed(): void {
    if (this.subscribed) {
      return;
    }
    this.subscribed = true;
    const store = this.source();
    this.lastActiveId = store.activeId;
    this.lastOpenKey = openKeyOf(store);
    store.$subscribe(() => this.emit());
  }

  private emit(): void {
    const store = this.source();
    const nextOpenKey = openKeyOf(store);
    if (nextOpenKey !== this.lastOpenKey) {
      this.lastOpenKey = nextOpenKey;
      const ids = store.tabs.map((tab) => tab.id);
      for (const listener of Array.from(this.openListeners)) {
        try {
          listener(ids);
        } catch (error) {
          console.warn('插件仓库打开状态回调失败', error);
        }
      }
    }

    if (store.activeId !== this.lastActiveId) {
      this.lastActiveId = store.activeId;
      for (const listener of Array.from(this.activeListeners)) {
        try {
          listener(store.activeId);
        } catch (error) {
          console.warn('插件仓库切换回调失败', error);
        }
      }
    }
  }
}

function openKeyOf(store: RepoEventSource): string {
  return JSON.stringify(store.tabs.map((tab) => tab.id));
}
