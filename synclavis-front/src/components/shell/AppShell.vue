<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import { NButton, useDialog, useMessage } from 'naive-ui';
import { FolderGit2 } from 'lucide-vue-next';
import { gitApi } from '@/api/git';
import { operationsApi, type PushBody } from '@/api/operations';
import QuickActionBar from '@/components/actions/QuickActionBar.vue';
import CloneRepoDialog from '@/components/dialogs/CloneRepoDialog.vue';
import NewBranchDialog from '@/components/dialogs/NewBranchDialog.vue';
import OpenRepoDialog from '@/components/dialogs/OpenRepoDialog.vue';
import PushPreviewDialog from '@/components/dialogs/PushPreviewDialog.vue';
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
import type { BranchInfo } from '@/types/repo';
import { extractConflictFiles } from '@/utils/branches';
import { LAYOUT_BOUNDS, resolvePanelSize } from '@/utils/layout';
import { shortRemoteBranchName } from '@/utils/remotes';

const tabs = useTabsStore();
const repos = useReposStore();
const operations = useOperationsStore();
const settings = useSettingsStore();
const message = useMessage();
const dialog = useDialog();

const showOpenDialog = ref(false);
const showCloneDialog = ref(false);
const showSettingsDialog = ref(false);
const showNewBranchDialog = ref(false);
const showPushDialog = ref(false);
/** 预览弹窗要执行的推送意图（`tags` 不进弹窗，由 PushButton 直接推） */
const pushBody = ref<PushBody>({});
/** 从远程分支检出时的预填（起点 = 远程跟踪名，名字 = 去掉 remote 前缀的短名） */
const newBranchPreset = ref<{ startPoint: string; name: string } | null>(null);
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

/* ---------------------------------------------------------------- 分支切换 */

/**
 * 双击本地分支：直接尝试切换。
 *
 * 不做「脏工作区」预判 —— 与 `git checkout` 一致，能带走的改动会被带走，
 * 只有会被覆盖的文件才失败（后端返回 `CHECKOUT_CONFLICT` + `details.files`）。
 */
async function onCheckout(branch: BranchInfo): Promise<void> {
  const repoId = tabs.activeId;
  if (!repoId) {
    return;
  }
  if (operations.isRepoBusy(repoId)) {
    message.warning('该仓库正在执行其他操作');
    return;
  }
  try {
    const status = await gitApi.checkout(repoId, { branch: branch.name });
    // runSync 只推 status、没有 done：branches.current 与提交历史要自己刷
    tabs.upsertStatus(repoId, status);
    message.success(`已切换到 ${branch.name}`);
    await repos.refreshAll(repoId);
  } catch (error) {
    reportCheckoutError(branch.name, error);
  }
}

/** 双击远程分支：打开对话框，把它检出为同名本地跟踪分支并切换。 */
function onCheckoutRemote(payload: { name: string; remote: string }): void {
  newBranchPreset.value = {
    startPoint: payload.name,
    name: shortRemoteBranchName(payload.name, payload.remote),
  };
  showNewBranchDialog.value = true;
}

/** 「本地分支」标题旁的 `+`：新建分支并切换。 */
function onNewBranch(): void {
  newBranchPreset.value = null;
  showNewBranchDialog.value = true;
}

/** 切换失败的统一提示：会被覆盖的文件用对话框列出，其余走 toast。 */
function reportCheckoutError(branch: string, error: unknown): void {
  const files = extractConflictFiles(error);
  if (files.length > 0) {
    dialog.warning({
      title: `无法切换到 ${branch}`,
      content: () =>
        h('div', { 'data-testid': 'checkout-conflict' }, [
          h('p', '以下本地改动会被覆盖，请先提交或 Stash：'),
          h(
            'ul',
            { style: 'margin: 6px 0 0 18px; list-style: disc;' },
            files.map((file) => h('li', file)),
          ),
        ]),
      positiveText: '知道了',
    });
    return;
  }
  message.error(error instanceof Error ? error.message : String(error));
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
    // 快捷键与工具栏走同一入口：先预览本次要推的提交，确认后再推（见《详细设计文档》§6.2）
    onPush({});
  },
});

/** 打开「推送预览」弹窗（PushButton / 快捷键共用） */
function onPush(body: PushBody): void {
  if (!tabs.activeId) {
    return;
  }
  pushBody.value = body;
  showPushDialog.value = true;
}

async function runOperation(
  kind: 'fetch',
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
      @push="onPush"
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
        @checkout="onCheckout"
        @checkout-remote="onCheckoutRemote"
        @new-branch="onNewBranch"
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
    <NewBranchDialog
      v-if="tabs.activeId"
      v-model:show="showNewBranchDialog"
      :repo-id="tabs.activeId"
      :mode="newBranchPreset ? 'checkout' : 'create'"
      :start-point="newBranchPreset?.startPoint"
      :suggested-name="newBranchPreset?.name"
    />
    <PushPreviewDialog
      v-if="tabs.activeId"
      v-model:show="showPushDialog"
      :repo-id="tabs.activeId"
      :body="pushBody"
    />
    <RepoSettingsDialog
      v-if="tabs.activeId"
      v-model:show="showSettingsDialog"
      :repo-id="tabs.activeId"
    />
  </div>
</template>
