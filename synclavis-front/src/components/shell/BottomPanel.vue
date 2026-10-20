<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ChevronDown, ChevronUp, X } from 'lucide-vue-next';
import OperationLogView from '@/components/log/OperationLogView.vue';
import PluginViewHost from '@/components/plugins/PluginViewHost.vue';
import ResizeHandle from '@/components/common/ResizeHandle.vue';
import { useOperationsStore } from '@/stores/operations';
import { usePluginsStore } from '@/stores/plugins';
import type { RegisteredPluginView } from '@/plugins/types';
import { LAYOUT_BOUNDS } from '@/utils/layout';

const props = defineProps<{ repoId: string; height: number; maxHeight: number }>();

const emit = defineEmits<{
  (e: 'update:height', value: number): void;
  (e: 'commit-height', value: number): void;
}>();

interface BottomTab {
  key: string;
  title: string;
  view?: RegisteredPluginView;
}

const operations = useOperationsStore();
const plugins = usePluginsStore();

const views = computed(() => plugins.viewsFor('bottomDock'));
const logCount = computed(() => (operations.log[props.repoId] ?? []).length);
const tabs = computed<BottomTab[]>(() => [
  { key: 'log', title: `操作日志（${logCount.value}）` },
  ...views.value.map((view) => ({ key: view.key, title: view.title, view })),
]);

const activeKey = computed(() => plugins.bottomDockTabResolved);

const activeTab = computed(
  () => tabs.value.find((tab) => tab.key === activeKey.value) ?? tabs.value[0],
);

/** 插件视图首次选中后才挂载；之后只用 v-show 隐藏，终端进程/滚动状态因此保活。 */
const visitedKeys = ref<string[]>([]);
const visitedViews = computed(() =>
  views.value.filter((view) => visitedKeys.value.includes(view.key)),
);

function rememberActiveView(): void {
  const view = activeTab.value?.view;
  if (view && !visitedKeys.value.includes(view.key)) {
    visitedKeys.value = [...visitedKeys.value, view.key];
  }
}

watch(
  views,
  (list) => {
    // 插件停用/重载后移除已不存在的缓存视图
    visitedKeys.value = visitedKeys.value.filter((key) =>
      list.some((view) => view.key === key),
    );
    rememberActiveView();
  },
  { immediate: true },
);

watch(activeTab, rememberActiveView);

function activate(key: string): void {
  plugins.setBottomDockTab(key);
  if (plugins.bottomDockCollapsed) {
    plugins.setBottomDockCollapsed(false);
  }
  if (!plugins.bottomDockOpen) {
    plugins.setBottomDockOpen(true);
  }
}

const panelStyle = computed(() => ({
  height: plugins.bottomDockCollapsed ? '26px' : `${props.height + 31}px`,
}));
</script>

<template>
  <section
    class="flex shrink-0 flex-col border-t border-border bg-bg-elev"
    :style="panelStyle"
    data-testid="bottom-panel"
  >
    <ResizeHandle
      v-if="!plugins.bottomDockCollapsed"
      axis="y"
      invert
      :size="height"
      :min="LAYOUT_BOUNDS.logPanelHeight.min"
      :max="maxHeight"
      label="调整底部面板高度"
      @update:size="emit('update:height', $event)"
      @change="emit('commit-height', $event)"
    />

    <div class="flex h-[26px] shrink-0 items-center gap-1 px-2 text-xxs text-text-dim">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        type="button"
        class="flex max-w-[180px] items-center gap-1 truncate rounded px-1.5 py-0.5 hover:bg-bg-hover"
        :class="tab.key === activeKey ? 'bg-bg-hover text-text' : ''"
        :data-testid="`bottom-tab-${tab.key}`"
        :title="tab.title"
        @click="activate(tab.key)"
      >
        {{ tab.title }}
      </button>

      <div class="ml-auto flex items-center gap-1">
        <button
          type="button"
          class="rounded p-0.5 hover:bg-bg-hover"
          :title="plugins.bottomDockCollapsed ? '展开底部面板' : '折叠底部面板'"
          data-testid="bottom-panel-toggle"
          @click="plugins.setBottomDockCollapsed(!plugins.bottomDockCollapsed)"
        >
          <component :is="plugins.bottomDockCollapsed ? ChevronUp : ChevronDown" :size="12" />
        </button>
        <button
          type="button"
          class="rounded p-0.5 hover:bg-bg-hover"
          title="关闭底部面板（会终止终端）"
          data-testid="bottom-panel-close"
          @click="plugins.setBottomDockOpen(false)"
        >
          <X :size="12" />
        </button>
      </div>
    </div>

    <div v-show="!plugins.bottomDockCollapsed" class="min-h-0 flex-1 overflow-hidden">
      <div v-show="activeKey === 'log'" class="h-full min-h-0">
        <OperationLogView :repo-id="repoId" />
      </div>
      <div
        v-for="view in visitedViews"
        v-show="activeKey === view.key"
        :key="view.key"
        class="h-full min-h-0"
      >
        <PluginViewHost :view="view" />
      </div>
    </div>
  </section>
</template>
