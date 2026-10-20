/**
 * 外部插件唯一的宿主 API 入口。
 *
 * <p>运行时由 import map 映射到 `/plugin-runtime/plugin-api.js`，
 * 与宿主共享同一份 `definePlugin` 与类型；不要在插件包里再打包一份。</p>
 */
export * from './types';
