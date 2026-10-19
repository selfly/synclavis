<script setup lang="ts">
import { computed } from 'vue';
import { NButton, NDropdown, useMessage } from 'naive-ui';
import { ChevronDown, GitMerge, History, Rewind } from 'lucide-vue-next';
import type { Component } from 'vue';
import { operationsApi, type PullStrategyName } from '@/api/operations';
import { reposApi } from '@/api/repos';
import { useOperationsStore } from '@/stores/operations';
import { useReposStore } from '@/stores/repos';
import { useTabsStore } from '@/stores/tabs';

const props = defineProps<{ repoId: string }>();

const operations = useOperationsStore();
const repos = useReposStore();
const tabs = useTabsStore();
const message = useMessage();

const loading = computed(() => operations.isRunning(props.repoId, 'pull'));
const enabled = computed(() => {
  const status = tabs.tabById(props.repoId)?.status ?? repos.detail(props.repoId).status;
  return (status?.remotes.length ?? 0) > 0 && !(status?.rebaseInProgress ?? false);
});
const strategy = computed<PullStrategyName>(
  () => tabs.tabById(props.repoId)?.pullStrategy ?? 'merge',
);
const isDirty = computed(() => {
  const status = tabs.tabById(props.repoId)?.status ?? repos.detail(props.repoId).status;
  if (!status) {
    return false;
  }
  return status.staged.length > 0 || status.unstaged.length > 0;
});

const strategyLabel: Record<PullStrategyName, string> = {
  merge: 'Merge',
  rebase: 'Rebase',
  'ff-only': 'FF-only',
};

const options = [
  { key: 'merge', label: 'Pull (Merge)', hint: '--no-rebase' },
  { key: 'rebase', label: 'Pull (Rebase)', hint: '--rebase' },
  { key: 'ff-only', label: 'Pull (Fast-forward only)', hint: '--ff-only' },
];

async function run(target: PullStrategyName): Promise<void> {
  if (!operations.canRun(props.repoId, 'pull')) {
    message.warning('该仓库正在执行其他操作');
    return;
  }
  if (target === 'rebase' && isDirty.value) {
    message.warning('rebase 需要干净的工作区，请先 commit 或 stash');
    return;
  }
  try {
    const handle = await operationsApi.pull(props.repoId, { strategy: target });
    operations.begin(handle.operationId, props.repoId, 'pull');
    operations.appendLog(props.repoId, { level: 'info', text: `开始 pull（${target}）` });
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  }
}

/** 选择策略：写回后端设置（后端为唯一真相），并立即以该策略执行 */
async function choose(key: string): Promise<void> {
  const target = key as PullStrategyName;
  try {
    await reposApi.updateSettings(props.repoId, { pullStrategy: target });
    tabs.patchTab(props.repoId, { pullStrategy: target });
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
    return;
  }
  await run(target);
}

const iconOf: Record<string, Component> = {
  merge: GitMerge,
  rebase: History,
  'ff-only': Rewind,
};
</script>

<template>
  <div class="flex items-center">
    <n-button
      size="tiny"
      :loading="loading"
      :disabled="!enabled"
      :title="enabled ? `Pull（${strategyLabel[strategy]}）` : '不可用：无远程或处于 rebase 中间态'"
      @click="run(strategy)"
    >
      <template #icon><component :is="iconOf[strategy]" :size="13" /></template>
      Pull
      <span class="ml-1 text-xxs opacity-70">{{ strategyLabel[strategy] }}</span>
    </n-button>
    <n-dropdown trigger="click" :options="options" :disabled="!enabled" @select="choose">
      <n-button size="tiny" :disabled="!enabled" class="!px-1">
        <template #icon><ChevronDown :size="12" /></template>
      </n-button>
    </n-dropdown>
  </div>
</template>
