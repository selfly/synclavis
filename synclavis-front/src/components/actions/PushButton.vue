<script setup lang="ts">
import { computed } from 'vue';
import { NButton, NDropdown, useMessage } from 'naive-ui';
import { ChevronDown, Upload } from 'lucide-vue-next';
import { operationsApi, type PushBody } from '@/api/operations';
import { useOperationsStore } from '@/stores/operations';
import { useReposStore } from '@/stores/repos';
import { useTabsStore } from '@/stores/tabs';

const props = defineProps<{ repoId: string }>();
const emit = defineEmits<{
  (e: 'open-settings'): void;
  /** 交给 AppShell 打开「推送预览」弹窗（tags 除外，那条与提交内容无关，直接推） */
  (e: 'push', body: PushBody): void;
}>();

const operations = useOperationsStore();
const repos = useReposStore();
const tabs = useTabsStore();
const message = useMessage();

const loading = computed(() => operations.isRunning(props.repoId, 'push'));
const status = computed(
  () => tabs.tabById(props.repoId)?.status ?? repos.detail(props.repoId).status,
);
/** 有远程才能 Push；没有时不置灰，点击引导去「仓库设置 → 远程仓库」添加 */
const enabled = computed(() => (status.value?.remotes.length ?? 0) > 0);
const hasUpstream = computed(() => status.value?.hasUpstream ?? false);
const conflicted = computed(() => (status.value?.conflicts.length ?? 0) > 0);

const options = [
  { label: 'Push current branch', key: 'current' },
  { label: 'Set upstream 并推送', key: 'upstream' },
  { label: 'Push tags', key: 'tags' },
];

/** Push tags 只有标签，没有「待推送提交」可预览（JGit 的 tag refspec 不含当前分支） */
async function pushTags(): Promise<void> {
  if (!operations.canRun(props.repoId, 'push')) {
    message.warning('该仓库正在执行其他操作');
    return;
  }
  try {
    const handle = await operationsApi.push(props.repoId, { tags: true });
    operations.begin(handle.operationId, props.repoId, 'push');
    operations.appendLog(props.repoId, { level: 'info', text: '开始 push tags' });
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  }
}

/** 无 remote 时引导去添加；无 upstream 时把 setUpstream 一起交给预览弹窗（不再单独弹一次） */
function runDefault(): void {
  if (!enabled.value) {
    message.info('还没有远程仓库，先添加一个再推送');
    emit('open-settings');
    return;
  }
  if (conflicted.value) {
    message.warning('存在未解决的冲突，请先处理后再推送');
    return;
  }
  emit('push', hasUpstream.value ? {} : { setUpstream: true });
}

function onSelect(key: string): void {
  if (key === 'tags') {
    void pushTags();
    return;
  }
  emit('push', key === 'upstream' ? { setUpstream: true } : {});
}
</script>

<template>
  <div class="flex items-center">
    <n-button
      size="tiny"
      data-testid="push-button"
      :loading="loading"
      :title="enabled ? 'Push' : '还没有远程仓库，点击添加'"
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
