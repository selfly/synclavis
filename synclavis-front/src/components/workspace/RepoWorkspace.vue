<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import CommitGraph from './CommitGraph.vue';
import CommitPanel from './CommitPanel.vue';
import DiffViewer from './DiffViewer.vue';
import RepoSidebar from './RepoSidebar.vue';
import RebaseStatusBar from '@/components/rebase/RebaseStatusBar.vue';
import { gitApi } from '@/api/git';
import { useReposStore } from '@/stores/repos';
import { useTabsStore } from '@/stores/tabs';

const props = defineProps<{ repoId: string }>();

const repos = useReposStore();
const tabs = useTabsStore();
const errorText = ref<string | null>(null);

const status = computed(
  () => tabs.tabById(props.repoId)?.status ?? repos.detail(props.repoId).status,
);
const rebasing = computed(() => status.value?.rebaseInProgress ?? false);

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

    <div class="flex min-h-0 flex-1">
      <RepoSidebar :repo-id="repoId" />
      <CommitGraph :repo-id="repoId" />
      <div class="flex min-w-0 flex-1 flex-col">
        <DiffViewer :repo-id="repoId" />
        <CommitPanel :repo-id="repoId" />
      </div>
    </div>
  </div>
</template>
