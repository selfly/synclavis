import { defineStore } from 'pinia';
import { http } from '@/api/http';
import {
  clampLayout,
  readLayoutCache,
  resolveLayout,
  writeLayoutCache,
  type LayoutSettings,
} from '@/utils/layout';

export type ThemeName = 'dark' | 'light';

export interface Shortcuts {
  refresh: string;
  fetch: string;
  push: string;
}

export interface RecentRepo {
  path: string;
  lastOpenedAt: string;
}

/** 全局设置：后端持久化，localStorage 仅作瞬时缓存 */
export interface GlobalSettings {
  theme: ThemeName;
  shortcuts: Shortcuts;
  recentRepos: RecentRepo[];
  layout: LayoutSettings;
}

const THEME_CACHE_KEY = 'synclavis.theme';

const DEFAULT_SHORTCUTS: Shortcuts = {
  refresh: 'F5',
  fetch: 'Ctrl+Shift+F',
  push: 'Ctrl+Shift+P',
};

export const useSettingsStore = defineStore('settings', {
  state: () => ({
    theme: 'dark' as ThemeName,
    shortcuts: { ...DEFAULT_SHORTCUTS } as Shortcuts,
    recentRepos: [] as RecentRepo[],
    // 缓存优先（与主题同理）：首屏即用上次尺寸，随后由后端校正，避免尺寸跳动
    layout: readLayoutCache() as LayoutSettings,
    loaded: false,
  }),

  actions: {
    applyTheme(): void {
      document.documentElement.setAttribute('data-theme', this.theme);
    },

    async load(): Promise<void> {
      const settings = await http.get<GlobalSettings>('/api/settings');
      this.theme = settings.theme;
      this.shortcuts = settings.shortcuts;
      this.recentRepos = settings.recentRepos;
      this.layout = resolveLayout(settings.layout);
      this.loaded = true;
      localStorage.setItem(THEME_CACHE_KEY, this.theme);
      writeLayoutCache(this.layout);
      this.applyTheme();
    },

    async setTheme(theme: ThemeName): Promise<void> {
      this.theme = theme;
      this.applyTheme();
      localStorage.setItem(THEME_CACHE_KEY, theme);
      const settings = await http.put<GlobalSettings>('/api/settings', { theme });
      this.theme = settings.theme;
      this.applyTheme();
    },

    /**
     * 更新布局尺寸（部分更新，后端为准）。
     *
     * <p>先本地生效并写缓存（拖拽要跟手），再 PUT；失败只记录不打断交互，
     * 下次拖动或刷新时仍以本地缓存与服务端返回值为准。</p>
     */
    async setLayout(patch: Partial<LayoutSettings>): Promise<void> {
      const clamped = clampLayout(patch);
      if (Object.keys(clamped).length === 0) {
        return;
      }
      const applied = { ...this.layout, ...clamped };
      this.layout = applied;
      writeLayoutCache(applied);

      try {
        const settings = await http.put<GlobalSettings>('/api/settings', { layout: clamped });
        // 期间若又拖了一次，本地更新更新，不能被这次响应回滚
        if (this.layout === applied) {
          this.layout = resolveLayout(settings.layout);
          writeLayoutCache(this.layout);
        }
      } catch (error) {
        console.warn('布局尺寸持久化失败，保留本地值', error);
      }
    },
  },
});

