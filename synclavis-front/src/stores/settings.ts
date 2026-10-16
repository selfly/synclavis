import { defineStore } from 'pinia';
import { http } from '@/api/http';

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
      this.loaded = true;
      localStorage.setItem(THEME_CACHE_KEY, this.theme);
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
  },
});
