import { computed } from 'vue';
import { darkTheme, lightTheme, type GlobalThemeOverrides } from 'naive-ui';
import { useSettingsStore } from '@/stores/settings';

/** Naive UI 主题覆盖：与 CSS Variables token 对齐（见《详细设计文档》§9.4） */
export const naiveThemeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#3b82f6',
    primaryColorHover: '#60a5fa',
    primaryColorPressed: '#2563eb',
    borderRadius: '4px',
    fontSize: '13px',
  },
  DataTable: {
    thHeight: '28px',
    tdHeight: '26px',
  },
  Dialog: {
    borderRadius: '6px',
  },
};

export function useNaiveTheme() {
  const settings = useSettingsStore();
  return computed(() => (settings.theme === 'dark' ? darkTheme : lightTheme));
}
