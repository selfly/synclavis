<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import CommitGraph from './CommitGraph.vue';
import CommitPanel from './CommitPanel.vue';
import DiffViewer from './DiffViewer.vue';
import RepoSidebar from './RepoSidebar.vue';
import ResizeHandle from '@/components/common/ResizeHandle.vue';
import RebaseStatusBar from '@/components/rebase/RebaseStatusBar.vue';
import { gitApi } from '@/api/git';
import { useElementHeight, useElementWidth } from '@/composables/useElementHeight';
import { useReposStore } from '@/stores/repos';
import { useSettingsStore } from '@/stores/settings';
import { useTabsStore } from '@/stores/tabs';
import {
  COMMIT_MESSAGE_MIN_WIDTH,
  DIFF_MIN_HEIGHT,
  LAYOUT_BOUNDS,
  RIGHT_COLUMN_MIN_WIDTH,
  resolvePanelSize,
} from '@/utils/layout';

const props = defineProps<{ repoId: string }>();
const emit = defineEmits<{ (e: 'settings'): void }>();

const repos = useReposStore();
const tabs = useTabsStore();
const settings = useSettingsStore();
const errorText = ref<string | null>(null);

const status = computed(
  () => tabs.tabById(props.repoId)?.status ?? repos.detail(props.repoId).status,
);
const rebasing = computed(() => status.value?.rebaseInProgress ?? false);

/** 分隔条视觉厚度；用来从容器宽度里扣掉被分隔条占掉的部分 */
const HANDLE_SIZE = 5;

const rightColumn = ref<HTMLElement | null>(null);
const rightColumnHeight = useElementHeight(rightColumn);
const rightColumnWidth = useElementWidth(rightColumn);

/**
 * 拖拽期间的实时尺寸放在本地 preview，不写 store：
 * 避免拖拽过程产生大量状态变更，也避免与持久化响应互相回滚。
 *
 * 渲染值一律走 `resolvePanelSize`（preview 优先 + 钳到动态上限），
 * 这样窗口变小/换显示器时已存的大尺寸不会把对侧内容压没；
 * store 里的存值保持不动，窗口恢复后自动回到用户偏好。
 */
const commitPanelPreview = ref<number | null>(null);
const commitPanelMax = computed(() => {
  // 扣掉分隔条本身，DIFF_MIN_HEIGHT 才是 Diff 区的净高度
  const available = rightColumnHeight.value - DIFF_MIN_HEIGHT - HANDLE_SIZE;
  return Math.min(
    LAYOUT_BOUNDS.commitPanelHeight.max,
    Math.max(LAYOUT_BOUNDS.commitPanelHeight.min, available),
  );
});
const commitPanelHeight = computed(() =>
  resolvePanelSize({
    preview: commitPanelPreview.value,
    stored: settings.layout.commitPanelHeight,
    min: LAYOUT_BOUNDS.commitPanelHeight.min,
    max: commitPanelMax.value,
  }),
);

/** 提交区内部：文件列表 + 分隔条 + 提交信息框 */
const commitFileListPreview = ref<number | null>(null);
const commitFileListMax = computed(() => {
  const available = rightColumnWidth.value - COMMIT_MESSAGE_MIN_WIDTH - HANDLE_SIZE;
  return Math.min(
    LAYOUT_BOUNDS.commitFileListWidth.max,
    Math.max(LAYOUT_BOUNDS.commitFileListWidth.min, available),
  );
});
const commitFileListWidth = computed(() =>
  resolvePanelSize({
    preview: commitFileListPreview.value,
    stored: settings.layout.commitFileListWidth,
    min: LAYOUT_BOUNDS.commitFileListWidth.min,
    max: commitFileListMax.value,
  }),
);

/**
 * 三栏总宽（含两条分隔条）：用它给横向分隔条算动态上限，
 * 保证任何拖拽组合下右侧 Diff 区都还能留下 RIGHT_COLUMN_MIN_WIDTH。
 */
const workspaceRow = ref<HTMLElement | null>(null);
const rowWidth = useElementWidth(workspaceRow);
const horizontalBudget = computed(() =>
  Math.max(0, rowWidth.value - RIGHT_COLUMN_MIN_WIDTH - 2 * HANDLE_SIZE),
);

// 先算侧栏、再算提交列表：单向依赖，避免两者互相引用造成的抖动
const sidebarPreview = ref<number | null>(null);
const sidebarMax = computed(() =>
  Math.min(
    LAYOUT_BOUNDS.sidebarWidth.max,
    Math.max(LAYOUT_BOUNDS.sidebarWidth.min, horizontalBudget.value - LAYOUT_BOUNDS.commitListWidth.min),
  ),
);
const sidebarWidth = computed(() =>
  resolvePanelSize({
    preview: sidebarPreview.value,
    stored: settings.layout.sidebarWidth,
    min: LAYOUT_BOUNDS.sidebarWidth.min,
    max: sidebarMax.value,
  }),
);

const commitListPreview = ref<number | null>(null);
const commitListMax = computed(() =>
  Math.min(
    LAYOUT_BOUNDS.commitListWidth.max,
    Math.max(LAYOUT_BOUNDS.commitListWidth.min, horizontalBudget.value - sidebarWidth.value),
  ),
);
const commitListWidth = computed(() =>
  resolvePanelSize({
    preview: commitListPreview.value,
    stored: settings.layout.commitListWidth,
    min: LAYOUT_BOUNDS.commitListWidth.min,
    max: commitListMax.value,
  }),
);

function onCommitPanelInput(size: number): void {
  commitPanelPreview.value = size;
}

function onCommitPanelCommit(size: number): void {
  commitPanelPreview.value = null;
  void settings.setLayout({ commitPanelHeight: size });
}

function onCommitFileListInput(size: number): void {
  commitFileListPreview.value = size;
}

function onCommitFileListCommit(size: number): void {
  commitFileListPreview.value = null;
  void settings.setLayout({ commitFileListWidth: size });
}

function onSidebarInput(size: number): void {
  sidebarPreview.value = size;
}

function onSidebarCommit(size: number): void {
  sidebarPreview.value = null;
  void settings.setLayout({ sidebarWidth: size });
}

function onCommitListInput(size: number): void {
  commitListPreview.value = size;
}

function onCommitListCommit(size: number): void {
  commitListPreview.value = null;
  void settings.setLayout({ commitListWidth: size });
}

// 同步确保 store 中存在该仓库的条目：
// 否则首次渲染会读到 getter 里 `?? emptyDetail()` 产生的一次性对象并被缓存，
// 后续 refreshAll 写入真实条目时组件仍停留在那个空对象上（表现为"共 0 个提交"）。
repos.ensure(props.repoId);

async function load(repoId: string): Promise<void> {
  errorText.value = null;
  try {
    await repos.refreshAll(repoId);
    const next = repos.detail(repoId).status;
    if (next) {
      tabs.upsertStatus(repoId, next);
    }
  } catch (error) {
    errorText.value = error instanceof Error ? error.message : String(error);
  }
}

/** 中间态：进工作区时主动校正一次（后端 getRepositoryState 为准） */
async function syncRebaseState(repoId: string): Promise<void> {
  try {
    await gitApi.rebaseStatus(repoId);
  } catch {
    // 忽略：状态刷新失败不影响主流程
  }
}

onMounted(async () => {
  await load(props.repoId);
  await syncRebaseState(props.repoId);
});

watch(
  () => props.repoId,
  async (repoId) => {
    await load(repoId);
    await syncRebaseState(repoId);
  },
);
</script>

<template>
  <div class="flex min-h-0 min-w-0 flex-1 flex-col">
    <RebaseStatusBar v-if="rebasing" :repo-id="repoId" />

    <div v-if="errorText" class="shrink-0 bg-danger/10 px-3 py-1.5 text-xs text-danger">
      {{ errorText }}
    </div>

    <!-- 三栏均在同一行内独立滚动：整页永不出现滚动条（见《详细设计文档》§9.5） -->
    <div ref="workspaceRow" class="flex min-h-0 min-w-0 flex-1">
      <RepoSidebar :repo-id="repoId" :width="sidebarWidth" @settings="emit('settings')" />
      <ResizeHandle
        axis="x"
        :size="sidebarWidth"
        :min="LAYOUT_BOUNDS.sidebarWidth.min"
        :max="sidebarMax"
        label="调整左侧栏宽度"
        @update:size="onSidebarInput"
        @change="onSidebarCommit"
      />
      <CommitGraph :repo-id="repoId" :width="commitListWidth" />
      <ResizeHandle
        axis="x"
        :size="commitListWidth"
        :min="LAYOUT_BOUNDS.commitListWidth.min"
        :max="commitListMax"
        label="调整提交列表宽度"
        @update:size="onCommitListInput"
        @change="onCommitListCommit"
      />
      <div ref="rightColumn" class="flex min-h-0 min-w-0 flex-1 flex-col">
        <DiffViewer :repo-id="repoId" />
        <ResizeHandle
          axis="y"
          invert
          :size="commitPanelHeight"
          :min="LAYOUT_BOUNDS.commitPanelHeight.min"
          :max="commitPanelMax"
          label="调整提交区高度"
          @update:size="onCommitPanelInput"
          @change="onCommitPanelCommit"
        />
        <CommitPanel
          :repo-id="repoId"
          :height="commitPanelHeight"
          :file-list-width="commitFileListWidth"
          :file-list-max="commitFileListMax"
          @update:file-list-width="onCommitFileListInput"
          @commit-file-list-width="onCommitFileListCommit"
        />
      </div>
    </div>
  </div>
</template>
