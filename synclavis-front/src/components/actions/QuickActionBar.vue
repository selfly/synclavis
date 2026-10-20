<script setup lang="ts">
import { computed } from 'vue';
import { NButton, useMessage } from 'naive-ui';
import { Ban, PanelBottom, Settings, Terminal } from 'lucide-vue-next';
import FetchButton from './FetchButton.vue';
import PullButton from './PullButton.vue';
import PushButton from './PushButton.vue';
import StashButton from './StashButton.vue';
import { requestOperationCancel, useOperationsStore } from '@/stores/operations';
import { usePluginsStore } from '@/stores/plugins';
import { useReposStore } from '@/stores/repos';
import { useTabsStore } from '@/stores/tabs';
import type { PushBody } from '@/api/operations';

const props = defineProps<{ repoId: string }>();
const emit = defineEmits<{
  (e: 'settings'): void;
  (e: 'push', body: PushBody): void;
}>();

const operations = useOperationsStore();
const plugins = usePluginsStore();
const repos = useReposStore();
const tabs = useTabsStore();
const message = useMessage();

const bottomViews = computed(() => plugins.viewsFor('bottomDock'));
const terminalView = computed(() => {
  const terminalPlugins = plugins.items
    .filter((item) => item.enabled && item.capabilities.includes('terminal.panel'))
    .map((item) => item.id);
  return bottomViews.value.find((view) => terminalPlugins.includes(view.pluginId)) ?? null;
});
const bottomEntry = computed(() => terminalView.value ?? bottomViews.value[0] ?? null);

/** 有终端插件时优先打开终端；没有则打开其他底部视图或操作日志。 */
function openBottomPanel(): void {
  plugins.openBottomDock(bottomEntry.value?.key ?? 'log');
}

const status = computed(
  () => tabs.tabById(props.repoId)?.status ?? repos.detail(props.repoId).status,
);
const progress = computed(() => operations.progressOf(props.repoId));
const runningId = computed(() => (operations.byRepo[props.repoId] ?? [])[0] ?? null);
const busy = computed(() => operations.isRepoBusy(props.repoId));

const percent = computed(() => {
  const current = progress.value;
  if (!current || current.total <= 0) {
    return null;
  }
  return Math.min(100, Math.round((current.completed / current.total) * 100));
});

function cancel(): void {
  const id = runningId.value;
  if (!id) {
    return;
  }
  if (!requestOperationCancel(id)) {
    message.warning('当前连接不可用，无法取消');
    return;
  }
  operations.appendLog(props.repoId, { level: 'warn', text: '已请求取消，等待当前步骤结束…' });
}
</script>

<template>
  <div class="shrink-0 border-b border-border bg-bg">
    <div class="flex h-[38px] items-center gap-2 px-2">
      <FetchButton :repo-id="repoId" />
      <PullButton :repo-id="repoId" />
      <PushButton
        :repo-id="repoId"
        @open-settings="emit('settings')"
        @push="emit('push', $event)"
      />
      <StashButton :repo-id="repoId" />

      <span class="ml-2 text-xxs text-text-dim">
        <template v-if="status">
          <span class="text-text">{{ status.branch ?? '(detached)' }}</span>
          <span v-if="status.ahead > 0" class="ml-1">↑{{ status.ahead }}</span>
          <span v-if="status.behind > 0" class="ml-1">↓{{ status.behind }}</span>
          <span v-if="status.conflicts.length > 0" class="ml-2 text-warn">
            冲突 {{ status.conflicts.length }}
          </span>
        </template>
      </span>

      <span v-if="progress" class="ml-auto flex items-center gap-2 text-xxs text-accent">
        <span>
          {{ progress.title }}
          <template v-if="progress.total > 0">
            {{ progress.completed }}/{{ progress.total }}
            <template v-if="percent !== null">（{{ percent }}%）</template>
          </template>
        </span>
        <n-button v-if="busy" size="tiny" quaternary type="error" @click="cancel">
          <template #icon><Ban :size="11" /></template>
          取消
        </n-button>
      </span>
      <span v-else-if="busy" class="ml-auto flex items-center gap-2 text-xxs text-accent">
        执行中…
        <n-button size="tiny" quaternary type="error" @click="cancel">
          <template #icon><Ban :size="11" /></template>
          取消
        </n-button>
      </span>

      <n-button
        size="tiny"
        quaternary
        :class="progress || busy ? '' : 'ml-auto'"
        :title="
          terminalView
            ? '打开终端'
            : bottomViews.length > 0
              ? '打开底部面板'
              : '打开操作日志面板'
        "
        data-testid="open-bottom-panel"
        @click="openBottomPanel"
      >
        <template #icon>
          <component :is="terminalView ? Terminal : PanelBottom" :size="13" />
        </template>
      </n-button>

      <n-button
        size="tiny"
        quaternary
        :class="progress || busy ? '' : 'ml-1'"
        title="仓库设置（远程仓库 / SSH 私钥 / HTTPS 凭据 / Pull 策略）"
        @click="emit('settings')"
      >
        <template #icon><Settings :size="13" /></template>
      </n-button>
    </div>

    <!-- 进度条 -->
    <div v-if="busy" class="h-[2px] w-full bg-bg-hover">
      <div
        class="h-full bg-accent transition-[width] duration-150"
        :style="{ width: percent === null ? '100%' : `${percent}%`, opacity: percent === null ? 0.4 : 1 }"
      />
    </div>
  </div>
</template>
