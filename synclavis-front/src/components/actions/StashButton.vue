<script setup lang="ts">
import { computed, ref } from 'vue';
import { NButton, NDropdown, useMessage } from 'naive-ui';
import { Archive, ChevronDown } from 'lucide-vue-next';
import { gitApi } from '@/api/git';
import { useOperationsStore } from '@/stores/operations';
import { useReposStore } from '@/stores/repos';
import { useTabsStore } from '@/stores/tabs';

const props = defineProps<{ repoId: string }>();

const operations = useOperationsStore();
const repos = useReposStore();
const tabs = useTabsStore();
const message = useMessage();

const includeUntracked = ref(true);
const loading = ref(false);

const status = computed(
  () => tabs.tabById(props.repoId)?.status ?? repos.detail(props.repoId).status,
);
const stashes = computed(() => repos.detail(props.repoId).stashes);
const clean = computed(() => {
  const current = status.value;
  if (!current) {
    return true;
  }
  const hasTrackedChange =
    current.staged.length > 0 || current.unstaged.length > 0;
  const hasUntracked = includeUntracked.value && current.untracked.length > 0;
  return !hasTrackedChange && !hasUntracked;
});

const options = computed(() => {
  const base: Array<Record<string, unknown>> = [
    { label: 'Stash all（含已跟踪变更）', key: 'stash' },
    {
      label: includeUntracked.value ? '☑ 包含未跟踪文件' : '☐ 包含未跟踪文件',
      key: 'toggle-untracked',
    },
  ];
  if (stashes.value.length > 0) {
    base.push({ type: 'divider', key: 'd1' });
    for (const stash of stashes.value.slice(0, 10)) {
      base.push({
        label: `stash@{${stash.index}}`,
        key: `stash-${stash.index}`,
        children: [
          { label: 'Apply', key: `apply-${stash.index}` },
          { label: 'Pop', key: `pop-${stash.index}` },
          { label: 'Drop', key: `drop-${stash.index}` },
        ],
      });
    }
  } else {
    base.push({ label: '暂无 Stash', key: 'empty', disabled: true });
  }
  return base;
});

async function refresh(): Promise<void> {
  await repos.refreshAfterOperation(props.repoId);
}

async function create(): Promise<void> {
  if (!operations.canRun(props.repoId, 'stash')) {
    message.warning('该仓库正在执行其他操作');
    return;
  }
  if (clean.value) {
    message.warning('工作区没有可 Stash 的变更');
    return;
  }
  loading.value = true;
  try {
    await gitApi.stashCreate(props.repoId, { includeUntracked: includeUntracked.value });
    message.success('已创建 Stash');
    await refresh();
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  } finally {
    loading.value = false;
  }
}

async function act(action: 'apply' | 'pop' | 'drop', index: number): Promise<void> {
  loading.value = true;
  try {
    if (action === 'apply') {
      await gitApi.stashApply(props.repoId, index);
      message.success(`已应用 stash@{${index}}`);
    } else if (action === 'pop') {
      await gitApi.stashPop(props.repoId, index);
      message.success(`已 Pop stash@{${index}}`);
    } else {
      await gitApi.stashDrop(props.repoId, index);
      message.success(`已删除 stash@{${index}}`);
    }
    await refresh();
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  } finally {
    loading.value = false;
  }
}

function onSelect(key: string): void {
  if (key === 'toggle-untracked') {
    includeUntracked.value = !includeUntracked.value;
    return;
  }
  const match = /^(apply|pop|drop)-(\d+)$/.exec(key);
  if (match) {
    void act(match[1] as 'apply' | 'pop' | 'drop', Number(match[2]));
    return;
  }
  if (key === 'stash') {
    void create();
  }
}
</script>

<template>
  <div class="flex items-center">
    <n-button
      size="tiny"
      :loading="loading"
      :disabled="clean"
      :title="clean ? '工作区没有可 Stash 的变更' : 'Stash（含未跟踪文件）'"
      @click="create"
    >
      <template #icon><Archive :size="13" /></template>
      Stash
      <span v-if="stashes.length > 0" class="ml-1 text-xxs opacity-70">({{ stashes.length }})</span>
    </n-button>
    <n-dropdown trigger="click" :options="options" @select="onSelect">
      <n-button size="tiny" class="!px-1">
        <template #icon><ChevronDown :size="12" /></template>
      </n-button>
    </n-dropdown>
  </div>
</template>
