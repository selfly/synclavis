<script setup lang="ts">
import { computed } from 'vue';
import { NButton, NDropdown } from 'naive-ui';
import { useMessage } from 'naive-ui';
import { ChevronDown, Download } from 'lucide-vue-next';
import { operationsApi, type FetchBody } from '@/api/operations';
import { useOperationsStore } from '@/stores/operations';
import { useReposStore } from '@/stores/repos';
import { useTabsStore } from '@/stores/tabs';

const props = defineProps<{ repoId: string }>();

const operations = useOperationsStore();
const repos = useReposStore();
const tabs = useTabsStore();
const message = useMessage();

const loading = computed(() => operations.isRunning(props.repoId, 'fetch'));
const enabled = computed(() => {
  const status = tabs.tabById(props.repoId)?.status ?? repos.detail(props.repoId).status;
  return (status?.remotes.length ?? 0) > 0;
});

const options = [
  { label: 'Fetch current remote', key: 'current' },
  { label: 'Fetch all remotes', key: 'all' },
  { label: 'Prune（清理远端已删分支）', key: 'prune' },
  { label: 'Fetch tags', key: 'tags' },
];

async function run(extra: FetchBody): Promise<void> {
  if (!operations.canRun(props.repoId, 'fetch')) {
    message.warning('该仓库正在执行其他操作');
    return;
  }
  try {
    const body: FetchBody = { prune: true, ...extra };
    const handle = await operationsApi.fetch(props.repoId, body);
    operations.begin(handle.operationId, props.repoId, 'fetch');
    operations.appendLog(props.repoId, { level: 'info', text: `开始 fetch ${JSON.stringify(body)}` });
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  }
}

function onSelect(key: string): void {
  if (key === 'all') {
    void run({ all: true });
  } else if (key === 'prune') {
    void run({ prune: true });
  } else if (key === 'tags') {
    void run({ tags: true });
  } else {
    void run({});
  }
}
</script>

<template>
  <div class="flex items-center">
    <n-button
      size="tiny"
      :loading="loading"
      :disabled="!enabled"
      :title="enabled ? 'Fetch（含 prune）' : '无远程仓库'"
      @click="run({})"
    >
      <template #icon><Download :size="13" /></template>
      Fetch
    </n-button>
    <n-dropdown trigger="click" :options="options" :disabled="!enabled" @select="onSelect">
      <n-button size="tiny" :disabled="!enabled" class="!px-1">
        <template #icon><ChevronDown :size="12" /></template>
      </n-button>
    </n-dropdown>
  </div>
</template>
