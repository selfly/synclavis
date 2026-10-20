<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue';
import { NAlert, NButton, NEmpty, NInput, NPopconfirm, NSelect, NSpin, NTag } from 'naive-ui';
import type { PluginContext } from '@synclavis/plugin-api';
import ChatItemView from './ChatItemView.vue';
import { useAiPanel } from './useAiPanel';

const props = defineProps<{ context: PluginContext }>();

const {
  repoId,
  selectedHarness,
  selectedHarnessId,
  harnessOptions,
  sessionOptions,
  sessionRef,
  sessionHarness,
  sessionHarnessAvailable,
  items,
  revision,
  busy,
  loading,
  creating,
  deleting,
  sending,
  promptText,
  error,
  historyTruncated,
  wsLabel,
  wsTagType,
  sessionError,
  canCreate,
  canSend,
  selectHarness,
  selectSession,
  newSession,
  deleteSession,
  send,
  cancel,
  respondPermission,
  clearError,
} = useAiPanel(props.context);

const feedRef = ref<HTMLElement | null>(null);
let stickToBottom = true;

function onFeedScroll(): void {
  const element = feedRef.value;
  if (!element) {
    return;
  }
  stickToBottom =
    element.scrollHeight - element.scrollTop - element.clientHeight < 120;
}

async function scrollToBottom(): Promise<void> {
  await nextTick();
  const element = feedRef.value;
  if (element && stickToBottom) {
    element.scrollTop = element.scrollHeight;
  }
}

watch(revision, () => {
  void scrollToBottom();
});

watch(sessionRef, () => {
  stickToBottom = true;
  void scrollToBottom();
});

onMounted(() => {
  void scrollToBottom();
});

function onHarnessChange(value: string | number | null): void {
  void selectHarness(value);
}

function onSessionChange(value: string | number | null): void {
  selectSession(value);
}

function onPromptInput(value: string): void {
  promptText.value = value;
}

async function onSend(): Promise<void> {
  stickToBottom = true;
  await send();
}
</script>

<template>
  <div class="ai-panel">
    <div class="ai-toolbar">
      <div class="ai-row">
        <n-select
          class="ai-flex"
          size="small"
          :options="harnessOptions"
          :value="selectedHarnessId"
          placeholder="选择 AI harness"
          @update:value="onHarnessChange"
        />
        <n-tag size="tiny" :type="wsTagType">{{ wsLabel }}</n-tag>
      </div>
      <div v-if="selectedHarness && !selectedHarness.available" class="ai-hint ai-warn">
        {{ selectedHarness.reason || '默认 harness 不可用，暂时无法新建会话' }}
      </div>
      <div v-if="sessionRef && sessionHarness && !sessionHarness.available" class="ai-hint ai-warn">
        当前会话的 harness 不可用：{{ sessionHarness.reason || '未知原因' }}
      </div>
      <div
        v-else-if="sessionHarness && sessionHarness.id !== selectedHarnessId"
        class="ai-hint"
      >
        当前会话使用 {{ sessionHarness.name }}；上方选择只影响新建会话。
      </div>
      <div class="ai-row">
        <n-select
          class="ai-flex"
          size="small"
          :options="sessionOptions"
          :value="sessionRef"
          placeholder="选择会话"
          @update:value="onSessionChange"
        />
        <n-button size="small" :loading="creating" :disabled="!canCreate" @click="newSession">
          新建
        </n-button>
        <n-popconfirm v-if="sessionRef" @positive-click="deleteSession">
          <template #trigger>
            <n-button size="small" type="error" quaternary :loading="deleting">
              删除
            </n-button>
          </template>
          删除当前 AI 会话？
        </n-popconfirm>
      </div>
    </div>

    <n-alert
      v-if="error"
      class="ai-alert"
      type="error"
      :title="error"
      closable
      @close="clearError"
    />
    <n-alert
      v-else-if="sessionError && !busy"
      class="ai-alert"
      type="warning"
      :title="`上次执行失败：${sessionError}`"
    />
    <div v-if="historyTruncated" class="ai-hint ai-warn ai-history-hint">
      部分历史事件已超出后端缓冲，仅显示最近事件。
    </div>

    <div v-if="!repoId" class="ai-empty">
      <n-empty description="打开仓库后使用 AI 面板" />
    </div>
    <div v-else-if="loading" class="ai-empty">
      <n-spin size="small" />
      <span class="ai-hint">加载中…</span>
    </div>
    <div v-else-if="!sessionRef" class="ai-empty">
      <n-empty description="还没有 AI 会话" />
      <n-button size="small" type="primary" :disabled="!canCreate" @click="newSession">
        新建会话
      </n-button>
    </div>

    <template v-else>
      <div ref="feedRef" class="ai-feed" @scroll="onFeedScroll">
        <div v-if="items.length === 0" class="ai-hint">发送第一条消息开始。</div>
        <ChatItemView
          v-for="item in items"
          :key="item.id"
          :item="item"
          @permission="respondPermission"
        />
      </div>

      <div class="ai-composer">
        <n-input
          type="textarea"
          size="small"
          :value="promptText"
          :autosize="{ minRows: 2, maxRows: 6 }"
          placeholder="输入指令，Enter 发送，Shift+Enter 换行"
          :disabled="!sessionHarnessAvailable"
          @update:value="onPromptInput"
          @keydown.enter.exact.prevent="onSend"
        />
        <div class="ai-composer-actions">
          <span class="ai-hint">
            {{ busy ? '执行中…' : 'AI 会修改当前仓库，请留意工作区变更' }}
          </span>
          <n-button v-if="busy" size="small" type="warning" @click="cancel">停止</n-button>
          <n-button
            size="small"
            type="primary"
            :loading="sending"
            :disabled="!canSend"
            @click="onSend"
          >
            发送
          </n-button>
        </div>
      </div>
    </template>
  </div>
</template>
