# 终端插件（第一方示例）

- 插件 id：`synclavis.terminal`
- 视图：`terminal.main` → `bottomDock`
- 权限：`repo:read`、`terminal`（启用时会显示高风险确认）
- 后端：宿主 `/api/terminal/**` + `/ws/terminal/**`，插件不直接创建进程

## 构建与安装

```bash
pnpm build:plugin:terminal
cp -R plugins/terminal ~/.synclavis/plugins/synclavis.terminal
```

然后在「插件」对话框中启用。终端会出现在底部面板的「终端」标签；首次选中时才创建 PTY。
折叠底部面板或切换到操作日志不会终止进程，关闭面板/关闭仓库/停用插件会终止。
