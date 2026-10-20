# Hello 插件（P0 示例）

验证本地插件的最小包：`manifest.json` + `dist/index.js` + `dist/style.css`。

## 安装

```bash
mkdir -p ~/.synclavis/plugins
cp -R synclavis-front/examples/hello-plugin ~/.synclavis/plugins/synclavis.hello
```

然后在界面右上角的插件按钮里：

1. 点击「重新扫描」；
2. 启用「Hello 插件」；
3. 点击「打开视图」，右侧 Dock 会出现 Hello 面板。

本地未签名插件默认禁用。该示例只声明 `repo:read` / `ui:notify`，不需要开发者模式。

## 说明

- `dist/index.js` 没有打包 Vue，`import 'vue'` 由宿主的 import map 提供；
- 视图通过 `ctx.views.register()` 注册，组件异步加载，崩溃不会影响工作台；
- 这是 P0 的本地目录安装；下载/签名/iframe 隔离在后续版本。
