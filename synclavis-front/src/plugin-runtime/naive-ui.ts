/**
 * 插件运行时：宿主共享的 Naive UI 组件子集。
 *
 * <p>不能 `export * from 'naive-ui'`：入口保留全部导出会把整个组件库静态打进主包
 * （实测 vendor-naive 从 ~460KB 涨到 ~2MB）。这里只暴露插件常用组件；
 * 新插件需要未列出的组件时，再按需加入并评估体积。</p>
 */
export {
  NAlert,
  NBadge,
  NButton,
  NCard,
  NCheckbox,
  NCode,
  NCollapse,
  NCollapseItem,
  NDivider,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NList,
  NListItem,
  NModal,
  NPopconfirm,
  NProgress,
  NRadio,
  NRadioGroup,
  NScrollbar,
  NSelect,
  NSkeleton,
  NSpace,
  NSpin,
  NSwitch,
  NTabPane,
  NTabs,
  NTag,
  NText,
  NThing,
  NTooltip,
  useDialog,
  useLoadingBar,
  useMessage,
  useNotification,
} from 'naive-ui';
