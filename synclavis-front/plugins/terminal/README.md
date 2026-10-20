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

然后在「插件」对话框中启用。终端会出现在底部面板的「终端」标签；每个仓库首次选中时才创建 PTY。
切换仓库、切换底部标签或折叠底部面板都不会终止进程，切回时恢复原终端缓冲区；
关闭仓库只释放该仓库的终端，关闭底部面板/停用插件会终止全部。
