import { http } from './http';
import type { Capabilities, PluginInfo, PluginList } from '@/plugins/types';

/** 插件管理接口（见《详细设计文档》§6.4.3） */
export const pluginsApi = {
  list: (): Promise<PluginList> => http.get<PluginList>('/api/plugins'),

  reload: (): Promise<PluginList> => http.post<PluginList>('/api/plugins/reload'),

  setEnabled: (id: string, enabled: boolean): Promise<PluginInfo> =>
    http.put<PluginInfo>(`/api/plugins/${encodeURIComponent(id)}/enabled`, { enabled }),

  setDeveloperMode: (enabled: boolean): Promise<{ enabled: boolean }> =>
    http.put<{ enabled: boolean }>('/api/plugins/developer-mode', { enabled }),

  setActiveViews: (activeViews: Record<string, string>): Promise<{ activeViews: Record<string, string> }> =>
    http.put<{ activeViews: Record<string, string> }>('/api/plugins/active-views', { activeViews }),

  capabilities: (): Promise<Capabilities> => http.get<Capabilities>('/api/capabilities'),
};
