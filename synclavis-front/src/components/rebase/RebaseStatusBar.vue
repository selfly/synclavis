<script setup lang="ts">
import { computed, ref } from 'vue';
import { NButton, useDialog, useMessage } from 'naive-ui';
import { AlertTriangle, Check, SkipForward, XCircle } from 'lucide-vue-next';
import { gitApi } from '@/api/git';
import { useReposStore } from '@/stores/repos';
import { useTabsStore } from '@/stores/tabs';

const props = defineProps<{ repoId: string }>();

const repos = useReposStore();
const tabs = useTabsStore();
const message = useMessage();
const dialog = useDialog();

const busy = ref(false);

const status = computed(
  () => tabs.tabById(props.repoId)?.status ?? repos.detail(props.repoId).status,
);
const stateLabel = computed(() => {
  const state = status.value?.repositoryState ?? '';
  if (state === 'REBASING_INTERACTIVE') {
    return 'interactive rebase';
  }
  if (state === 'REBASING_MERGE') {
    return 'merge 后端';
  }
  if (state === 'REBASING_REBASING') {
    return 'rebase 后端';
  }
  return 'rebase';
});
const conflicts = computed(() => status.value?.conflicts ?? []);

async function act(action: 'continue' | 'skip' | 'abort'): Promise<void> {
  busy.value = true;
  try {
    if (action === 'continue') {
      await gitApi.rebaseContinue(props.repoId);
      message.success('已 Continue');
    } else if (action === 'skip') {
      await gitApi.rebaseSkip(props.repoId);
      message.success('已 Skip 当前提交');
    } else {
      await gitApi.rebaseAbort(props.repoId);
      message.success('已 Abort，回到 rebase 前状态');
    }
    await repos.refreshAfterOperation(props.repoId);
    const next = repos.detail(props.repoId).status;
    if (next) {
      tabs.upsertStatus(props.repoId, next);
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  } finally {
    busy.value = false;
  }
}

function confirmAbort(): void {
  dialog.warning({
    title: 'Abort rebase',
    content: '将丢弃本次 rebase 的全部进度并回到 rebase 之前的状态，确定继续？',
    positiveText: 'Abort',
    negativeText: '取消',
    onPositiveClick: () => act('abort'),
  });
}
</script>

<template>
  <div
    class="flex h-[34px] shrink-0 items-center gap-2 border-b border-warn/40 bg-warn/10 px-2 text-xs"
  >
    <AlertTriangle :size="13" class="shrink-0 text-warn" />
    <span class="font-medium text-warn">Rebase in progress</span>
    <span class="text-text-dim">（{{ stateLabel }}）</span>

    <span v-if="conflicts.length > 0" class="text-text-dim">
      · 冲突 {{ conflicts.length }} 个，解决后点击文件右侧 <span class="text-text">+</span> 暂存，再 Continue
    </span>
    <span v-else class="text-text-dim">· 冲突已全部暂存，可 Continue</span>

    <div class="ml-auto flex shrink-0 items-center gap-1.5">
      <n-button size="tiny" :loading="busy" @click="act('continue')">
        <template #icon><Check :size="12" /></template>
        Continue
      </n-button>
      <n-button size="tiny" :loading="busy" @click="act('skip')">
        <template #icon><SkipForward :size="12" /></template>
        Skip
      </n-button>
      <n-button size="tiny" ghost type="error" :loading="busy" @click="confirmAbort">
        <template #icon><XCircle :size="12" /></template>
        Abort
      </n-button>
    </div>
  </div>
</template>
