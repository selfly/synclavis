<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { NButton, useMessage } from 'naive-ui';
import { FolderGit2 } from 'lucide-vue-next';
import { operationsApi } from '@/api/operations';
import QuickActionBar from '@/components/actions/QuickActionBar.vue';
import CloneRepoDialog from '@/components/dialogs/CloneRepoDialog.vue';
import OpenRepoDialog from '@/components/dialogs/OpenRepoDialog.vue';
import RepoSettingsDialog from '@/components/dialogs/RepoSettingsDialog.vue';
import OperationLogPanel from '@/components/log/OperationLogPanel.vue';
import RepoTabBar from '@/components/tabs/RepoTabBar.vue';
import RepoWorkspace from '@/components/workspace/RepoWorkspace.vue';
import { useRepoEvents } from '@/composables/useRepoEvents';
import { useShortcuts } from '@/composables/useShortcuts';
import { useViewportHeight } from '@/composables/useElementHeight';
import { useOperationsStore } from '@/stores/operations';
import { useReposStore } from '@/stores/repos';
import { useSettingsStore } from '@/stores/settings';
import { useTabsStore } from '@/stores/tabs';
import { LAYOUT_BOUNDS, resolvePanelSize } from '@/utils/layout';

const tabs = useTabsStore();
const repos = useReposStore();
const operations = useOperationsStore();
const settings = useSettingsStore();
const message = useMessage();

const showOpenDialog = ref(false);
const showCloneDialog = ref(false);
const showSettingsDialog = ref(false);
const bootError = ref<string | null>(null);
const booting = ref(true);

const activeRepoId = computed(() => tabs.activeId);

/** 日志面板：拖拽中走本地 preview，落盘走全局设置（后端持久化） */
const logPanelPreview = ref<number | null>(null);
const viewportHeight = useViewportHeight();
const logPanelMax = computed(() =>
  Math.min(
    LAYOUT_BOUNDS.logPanelHeight.max,
    Math.max(LAYOUT_BOUNDS.logPanelHeight.min, Math.round(viewportHeight.value * 0.5)),
  ),
);
// 渲染值也钳制：窗口变矮时已存的大高度不能把主区压没
const logPanelHeight = computed(() =>
  resolvePanelSize({
    preview: logPanelPreview.value,
    stored: settings.layout.logPanelHeight,
    min: LAYOUT_BOUNDS.logPanelHeight.min,
    max: logPanelMax.value,
  }),
);

function onLogPanelInput(size: number): void {
  logPanelPreview.value = size;
}

function onLogPanelCommit(size: number): void {
  logPanelPreview.value = null;
  void settings.setLayout({ logPanelHeight: size });
}

// 仅订阅当前激活仓库的事件流（见设计文档 §8.3）
useRepoEvents(activeRepoId);

/** 快捷键：F5 刷新 / Ctrl+Shift+F Fetch / Ctrl+Shift+P Push（可在全局设置改键） */
useShortcuts({
  refresh: () => {
    const repoId = tabs.activeId;
    if (repoId) {
      void repos.refreshAfterOperation(repoId);
    }
  },
  fetch: () => {
    void runOperation('fetch', async (repoId) => {
      const handle = await operationsApi.fetch(repoId, {});
      operations.begin(handle.operationId, repoId, 'fetch');
      operations.appendLog(repoId, { level: 'info', text: '开始 fetch（快捷键）' });
    });
  },
  push: () => {
    void runOperation('push', async (repoId) => {
      const handle = await operationsApi.push(repoId, {});
      operations.begin(handle.operationId, repoId, 'push');
      operations.appendLog(repoId, { level: 'info', text: '开始 push（快捷键）' });
    });
  },
});

async function runOperation(
  kind: 'fetch' | 'push',
  action: (repoId: string) => Promise<void>,
): Promise<void> {
  const repoId = tabs.activeId;
  if (!repoId) {
    return;
  }
  if (!operations.canRun(repoId, kind)) {
    message.warning('该仓库正在执行其他操作');
    return;
  }
  try {
    await action(repoId);
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  }
}

onMounted(async () => {
  try {
    await settings.load();
  } catch {
    // 主题加载失败不阻塞启动（回退 localStorage 缓存）
  }
  try {
    await tabs.hydrate();
  } catch (error) {
    bootError.value = error instanceof Error ? error.message : String(error);
  } finally {
    booting.value = false;
  }
});
</script>

<template>
  <!--
    高度必须锚定视口（h-screen），不能用 h-full：
    Naive UI 的 NConfigProvider 会在 #app 与这里之间插入一层没有高度的
    .n-config-provider 容器，使 #app 的 height:100% 无法穿透，h-full 会退化为
    auto，整条 min-h-0 / flex-1 / overflow-y-auto 高度链随之失效 ——
    表现为提交历史把整页撑高，暂存文件与 Commit 按钮被推出视口（见《详细设计文档》§9.5）。
    overflow-hidden 作为兜底：今后任何内容超高都只在内部滚动，绝不产生整页滚动。
  -->
  <div class="flex h-screen flex-col overflow-hidden bg-bg text-text">
    <RepoTabBar @open="showOpenDialog = true" />

    <QuickActionBar
      v-if="tabs.activeId"
      :repo-id="tabs.activeId"
      @settings="showSettingsDialog = true"
    />

    <main class="flex min-h-0 flex-1">
      <div v-if="booting" class="flex flex-1 items-center justify-center text-xs text-text-dim">
        正在连接后端…
      </div>

      <div v-else-if="bootError" class="flex flex-1 flex-col items-center justify-center gap-2">
        <p class="text-sm text-danger">无法连接后端</p>
        <p class="max-w-[420px] text-center text-xs text-text-dim">{{ bootError }}</p>
        <p class="text-xs text-text-dim">请确认 synclavis4j 已启动（默认 127.0.0.1:18080）</p>
      </div>

      <div
        v-else-if="!tabs.activeId"
        class="flex flex-1 flex-col items-center justify-center gap-3 text-text-dim"
      >
        <FolderGit2 :size="40" />
        <p class="text-sm">还没有打开任何仓库</p>
        <n-button size="small" @click="showOpenDialog = true">打开仓库</n-button>
      </div>

      <RepoWorkspace
        v-else
        :key="tabs.activeId"
        :repo-id="tabs.activeId"
        @settings="showSettingsDialog = true"
      />
    </main>

    <OperationLogPanel
      v-if="tabs.activeId"
      :repo-id="tabs.activeId"
      :height="logPanelHeight"
      :max-height="logPanelMax"
      @update:height="onLogPanelInput"
      @commit-height="onLogPanelCommit"
    />

    <OpenRepoDialog
      v-model:show="showOpenDialog"
      @clone="
        () => {
          showOpenDialog = false;
          showCloneDialog = true;
        }
      "
    />
    <CloneRepoDialog v-model:show="showCloneDialog" />
    <RepoSettingsDialog
      v-if="tabs.activeId"
      v-model:show="showSettingsDialog"
      :repo-id="tabs.activeId"
    />
  </div>
</template>
