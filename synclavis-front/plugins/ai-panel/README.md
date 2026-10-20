# AI 面板插件

第一方 AI 面板，落在右侧 Dock。通过宿主公开的 `/api/ai/*` 与
`/ws/ai/sessions/{sessionRef}/events` 工作，不直接连接任何 AI 服务。

## 能力

- 选择 harness、创建/删除会话
- 流式文本、工具调用、权限确认
- 工作区变更摘要
- 停止当前 turn
- WS 断线自动重连，并通过 HTTP 事件接口补齐断档

## 权限

`repo:read`。插件只读取当前仓库 id，不直接读写仓库文件。

## 构建

在 `synclavis-front/` 下：

```bash
pnpm build:plugin:ai-panel
```

输出 `plugins/ai-panel/dist/index.js` 与 `dist/style.css`。构建会 externalize
`vue`、`naive-ui`、`@synclavis/plugin-api`，运行时由宿主的 import map 提供。

## 本地安装

```bash
mkdir -p ~/.synclavis/plugins
rm -rf ~/.synclavis/plugins/synclavis.ai-panel
cp -R plugins/ai-panel ~/.synclavis/plugins/synclavis.ai-panel
```

然后在应用内打开「插件」对话框，找到 `synclavis.ai-panel` 并启用。
插件目录可通过 `SYNCLAVIS_PLUGIN_DIR` 覆盖。

## 前置条件

- 后端已在 18080 运行（开发期由 Vite 代理 `/api`、`/ws`）。
- 至少一个 AI harness 可用；OpenCode 适配器默认从
  `~/.local/state/opencode/service.json` 发现服务。
- 右侧 Dock 已打开；插件视图注册在 `rightDock`。
