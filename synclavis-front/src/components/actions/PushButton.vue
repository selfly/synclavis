<script setup lang="ts">
import { computed } from 'vue';
import { NButton, NDropdown, useDialog, useMessage } from 'naive-ui';
import { ChevronDown, Upload } from 'lucide-vue-next';
import { operationsApi, type PushBody } from '@/api/operations';
import { useOperationsStore } from '@/stores/operations';
import { useReposStore } from '@/stores/repos';
import { useTabsStore } from '@/stores/tabs';

const props = defineProps<{ repoId: string }>();

const operations = useOperationsStore();
const repos = useReposStore();
const tabs = useTabsStore();
const message = useMessage();
const dialog = useDialog();

const loading = computed(() => operations.isRunning(props.repoId, 'push'));
const status = computed(
  () => tabs.tabById(props.repoId)?.status ?? repos.detail(props.repoId).status,
);
const enabled = computed(() => (status.value?.remotes.length ?? 0) > 0);
const hasUpstream = computed(() => status.value?.hasUpstream ?? false);
const conflicted = computed(() => (status.value?.conflicts.length ?? 0) > 0);

const options = [
  { label: 'Push current branch', key: 'current' },
  { label: 'Set upstream 并推送', key: 'upstream' },
  { label: 'Push tags', key: 'tags' },
];

async function push(body: PushBody): Promise<void> {
  if (!operations.canRun(props.repoId, 'push')) {
    message.warning('该仓库正在执行其他操作');
    return;
  }
  try {
    const handle = await operationsApi.push(props.repoId, body);
    operations.begin(handle.operationId, props.repoId, 'push');
    operations.appendLog(props.repoId, { level: 'info', text: `开始 push ${JSON.stringify(body)}` });
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  }
}

/** 无 upstream 时给出明确引导（需求：Push 无 upstream 时给出设置引导） */
function runDefault(): void {
  if (!enabled.value) {
    return;
  }
  if (conflicted.value) {
    message.warning('存在未解决的冲突，请先处理后再推送');
    return;
  }
  if (hasUpstream.value) {
    void push({});
    return;
  }
  dialog.warning({
    title: '未设置上游分支',
    content: '当前分支没有 upstream。是否设置 upstream 到 origin 并立即推送？',
    positiveText: '设置并推送',
    negativeText: '取消',
    onPositiveClick: () => push({ setUpstream: true }),
  });
}

function onSelect(key: string): void {
  if (key === 'upstream') {
    void push({ setUpstream: true });
  } else if (key === 'tags') {
    void push({ tags: true });
  } else {
    void push({});
  }
}
</script>

<template>
  <div class="flex items-center">
    <n-button
      size="tiny"
      :loading="loading"
      :disabled="!enabled"
      :title="enabled ? 'Push' : '无远程仓库'"
      @click="runDefault"
    >
      <template #icon><Upload :size="13" /></template>
      Push
      <span v-if="enabled && !hasUpstream" class="ml-1 text-xxs text-warn">无 upstream</span>
    </n-button>
    <n-dropdown trigger="click" :options="options" :disabled="!enabled" @select="onSelect">
      <n-button size="tiny" :disabled="!enabled" class="!px-1">
        <template #icon><ChevronDown :size="12" /></template>
      </n-button>
    </n-dropdown>
  </div>
</template>
