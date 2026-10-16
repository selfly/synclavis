<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import CommitGraph from './CommitGraph.vue';
import CommitPanel from './CommitPanel.vue';
import DiffViewer from './DiffViewer.vue';
import RepoSidebar from './RepoSidebar.vue';
import ResizeHandle from '@/components/common/ResizeHandle.vue';
import RebaseStatusBar from '@/components/rebase/RebaseStatusBar.vue';
import { gitApi } from '@/api/git';
import { useElementHeight } from '@/composables/useElementHeight';
import { useReposStore } from '@/stores/repos';
import { useSettingsStore } from '@/stores/settings';
import { useTabsStore } from '@/stores/tabs';
import { LAYOUT_BOUNDS } from '@/utils/layout';

const props = defineProps<{ repoId: string }>();

const repos = useReposStore();
const tabs = useTabsStore();
const settings = useSettingsStore();
const errorText = ref<string | null>(null);

const status = computed(
  () => tabs.tabById(props.repoId)?.status ?? repos.detail(props.repoId).status,
);
const rebasing = computed(() => status.value?.rebaseInProgress ?? false);

/** Diff 区保底高度：拖提交面板时不允许把上方内容压光 */
const DIFF_MIN_HEIGHT = 120;

const rightColumn = ref<HTMLElement | null>(null);
const rightColumnHeight = useElementHeight(rightColumn);

/**
 * 拖拽期间的实时尺寸放在本地 preview，不写 store：
 * 避免拖拽过程产生大量状态变更，也避免与持久化响应互相回滚。
 */
const commitPanelPreview = ref<number | null>(null);
const commitPanelHeight = computed(
  () => commitPanelPreview.value ?? settings.layout.commitPanelHeight,
);
const commitPanelMax = computed(() => {
  const available = rightColumnHeight.value - DIFF_MIN_HEIGHT;
  return Math.min(
    LAYOUT_BOUNDS.commitPanelHeight.max,
    Math.max(LAYOUT_BOUNDS.commitPanelHeight.min, available),
  );
});

const sidebarPreview = ref<number | null>(null);
const sidebarWidth = computed(() => sidebarPreview.value ?? settings.layout.sidebarWidth);

function onCommitPanelInput(size: number): void {
  commitPanelPreview.value = size;
}

function onCommitPanelCommit(size: number): void {
  commitPanelPreview.value = null;
  void settings.setLayout({ commitPanelHeight: size });
}

function onSidebarInput(size: number): void {
  sidebarPreview.value = size;
}

function onSidebarCommit(size: number): void {
  sidebarPreview.value = null;
  void settings.setLayout({ sidebarWidth: size });
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
  <div class="flex min-h-0 flex-1 flex-col">
    <RebaseStatusBar v-if="rebasing" :repo-id="repoId" />

    <div v-if="errorText" class="shrink-0 bg-danger/10 px-3 py-1.5 text-xs text-danger">
      {{ errorText }}
    </div>

    <!-- 三栏均在同一行内独立滚动：整页永不出现滚动条（见《详细设计文档》§9.5） -->
    <div class="flex min-h-0 flex-1">
      <RepoSidebar :repo-id="repoId" :width="sidebarWidth" />
      <ResizeHandle
        axis="x"
        :size="sidebarWidth"
        :min="LAYOUT_BOUNDS.sidebarWidth.min"
        :max="LAYOUT_BOUNDS.sidebarWidth.max"
        label="调整左侧栏宽度"
        @update:size="onSidebarInput"
        @change="onSidebarCommit"
      />
      <CommitGraph :repo-id="repoId" />
      <div ref="rightColumn" class="flex min-w-0 flex-1 flex-col">
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
        <CommitPanel :repo-id="repoId" :height="commitPanelHeight" />
      </div>
    </div>
  </div>
</template>
