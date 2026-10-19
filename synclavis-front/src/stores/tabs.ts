import { defineStore } from 'pinia';
import { reposApi, type SessionTabDto } from '@/api/repos';
import type { PullStrategy, RepoStatus } from '@/types/repo';

/** 运行时 Tab（易变数据不进入 localStorage 缓存） */
export interface RepoTabState {
  id: string;
  path: string;
  name: string;
  sshKeyPath: string | null;
  pullStrategy: PullStrategy;
  order: number;
  status: RepoStatus | null;
}

const SESSION_CACHE_KEY = 'synclavis.session.v1';

function toRuntime(tab: SessionTabDto): RepoTabState {
  return { ...tab, status: null };
}

/**
 * 已打开仓库 Tab。
 *
 * 持久化以后端为唯一真相；localStorage 仅作启动时的瞬时缓存（先渲染，再以后端覆盖）。
 */
export const useTabsStore = defineStore('tabs', {
  state: () => ({
    tabs: [] as RepoTabState[],
    activeId: null as string | null,
    hydratedFromServer: false,
  }),

  getters: {
    activeTab(state): RepoTabState | null {
      return state.tabs.find((tab) => tab.id === state.activeId) ?? null;
    },
    tabById(state) {
      return (id: string): RepoTabState | undefined => state.tabs.find((tab) => tab.id === id);
    },
  },

  actions: {
    /** 1) 读缓存立即渲染（乐观），2) 拉后端会话覆盖（后端为准） */
    async hydrate(): Promise<void> {
      this.readCache();
      const session = await reposApi.session();
      this.tabs = session.tabs.map(toRuntime);
      this.activeId = session.activeId;
      this.hydratedFromServer = true;
      this.writeCache();
    },

    async openRepo(path: string, sshKeyPath?: string | null): Promise<RepoTabState> {
      const tab = await reposApi.open(path, sshKeyPath);
      const existingIndex = this.tabs.findIndex((item) => item.id === tab.id);
      if (existingIndex >= 0) {
        this.tabs[existingIndex] = { ...toRuntime(tab), status: this.tabs[existingIndex].status };
      } else {
        this.tabs.push(toRuntime(tab));
      }
      this.activeId = tab.id;
      this.writeCache();
      const created = this.tabById(tab.id);
      return created ?? toRuntime(tab);
    },

    async closeRepo(repoId: string): Promise<void> {
      await reposApi.close(repoId);
      this.tabs = this.tabs.filter((tab) => tab.id !== repoId);
      this.tabs.forEach((tab, index) => {
        tab.order = index;
      });
      if (this.activeId === repoId) {
        this.activeId = this.tabs.length > 0 ? this.tabs[0].id : null;
      }
      this.writeCache();
    },

    async setActive(repoId: string | null): Promise<void> {
      this.activeId = repoId;
      this.writeCache();
      await reposApi.setActive(repoId);
    },

    /**
     * 拖拽排序：本地立即生效（拖拽要跟手），再写回后端。
     *
     * <p>失败只告警、保留本地顺序，下次 `hydrate()` 以后端为准（见《详细设计文档》§4.2）。</p>
     */
    async reorder(from: number, to: number): Promise<void> {
      const size = this.tabs.length;
      if (from < 0 || from >= size || to < 0 || to >= size || from === to) {
        return;
      }
      const [moved] = this.tabs.splice(from, 1);
      this.tabs.splice(to, 0, moved);
      this.tabs.forEach((tab, index) => {
        tab.order = index;
      });
      this.writeCache();

      try {
        await reposApi.reorder(this.tabs.map((tab) => tab.id));
      } catch (error) {
        console.warn('Tab 顺序持久化失败，保留本地顺序', error);
      }
    },

    upsertStatus(repoId: string, status: RepoStatus): void {
      const tab = this.tabs.find((item) => item.id === repoId);
      if (tab) {
        tab.status = status;
      }
    },

    patchTab(repoId: string, patch: Partial<RepoTabState>): void {
      const tab = this.tabs.find((item) => item.id === repoId);
      if (tab) {
        Object.assign(tab, patch);
        this.writeCache();
      }
    },

    readCache(): void {
      const raw = localStorage.getItem(SESSION_CACHE_KEY);
      if (!raw) {
        return;
      }
      try {
        const cached = JSON.parse(raw) as { activeId: string | null; tabs: SessionTabDto[] };
        this.tabs = cached.tabs.map(toRuntime);
        this.activeId = cached.activeId;
      } catch {
        localStorage.removeItem(SESSION_CACHE_KEY);
      }
    },

    writeCache(): void {
      const payload = {
        activeId: this.activeId,
        tabs: this.tabs.map((tab) => ({
          id: tab.id,
          path: tab.path,
          name: tab.name,
          sshKeyPath: tab.sshKeyPath,
          pullStrategy: tab.pullStrategy,
          order: tab.order,
        })),
      };
      localStorage.setItem(SESSION_CACHE_KEY, JSON.stringify(payload));
    },
  },
});
