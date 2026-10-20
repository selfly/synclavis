<script setup lang="ts">
import { NButton, NTag } from 'naive-ui';
import type { ChatItem } from './useAiPanel';

const props = defineProps<{ item: ChatItem }>();

const emit = defineEmits<{
  (event: 'permission', requestId: string, decision: 'allow' | 'deny'): void;
}>();

function json(value: unknown): string {
  if (value === undefined) {
    return '';
  }
  try {
    const text = JSON.stringify(value, null, 2);
    return text === undefined ? String(value) : text;
  } catch {
    return String(value);
  }
}

</script>

<template>
  <div v-if="props.item.kind === 'user'" class="ai-msg ai-msg-user">
    <div class="ai-msg-role">你</div>
    <pre>{{ props.item.text }}</pre>
  </div>

  <div v-else-if="props.item.kind === 'assistant'" class="ai-msg ai-msg-assistant">
    <div class="ai-msg-role">
      AI
      <span v-if="props.item.model" class="ai-model">{{ props.item.model }}</span>
      <span v-if="props.item.streaming" class="ai-streaming">流式输出中…</span>
    </div>
    <pre>{{ props.item.text || (props.item.streaming ? '思考中…' : '（无文本输出）') }}</pre>
  </div>

  <div v-else-if="props.item.kind === 'tool'" class="ai-card ai-tool-card">
    <div class="ai-card-head">
      <span class="ai-card-title">工具 · {{ props.item.name }}</span>
      <n-tag
        size="tiny"
        :type="props.item.done ? (props.item.ok ? 'success' : 'error') : 'default'"
      >
        {{ props.item.done ? (props.item.ok ? '完成' : '失败') : '运行中' }}
      </n-tag>
    </div>
    <details v-if="props.item.input !== undefined" class="ai-details">
      <summary>输入</summary>
      <pre>{{ json(props.item.input) }}</pre>
    </details>
    <details v-if="props.item.output !== undefined" class="ai-details">
      <summary>输出</summary>
      <pre>{{ json(props.item.output) }}</pre>
    </details>
    <pre v-if="props.item.error" class="ai-tool-error">{{ props.item.error }}</pre>
  </div>

  <div v-else-if="props.item.kind === 'permission'" class="ai-card ai-permission-card">
    <div class="ai-card-head">
      <span class="ai-card-title">需要权限确认</span>
      <n-tag size="tiny" type="warning">{{ props.item.kindLabel }}</n-tag>
    </div>
    <p class="ai-permission-title">{{ props.item.title }}</p>
    <details v-if="props.item.details !== undefined" class="ai-details">
      <summary>查看详情</summary>
      <pre>{{ json(props.item.details) }}</pre>
    </details>
    <div v-if="!props.item.decision" class="ai-permission-actions">
      <n-button
        size="tiny"
        type="primary"
        @click="emit('permission', props.item.requestId, 'allow')"
      >
        允许
      </n-button>
      <n-button
        size="tiny"
        type="error"
        @click="emit('permission', props.item.requestId, 'deny')"
      >
        拒绝
      </n-button>
    </div>
    <n-tag v-else size="tiny" :type="props.item.decision === 'allow' ? 'success' : 'error'">
      {{ props.item.decision === 'allow' ? '已允许' : '已拒绝' }}
    </n-tag>
  </div>

  <div v-else-if="props.item.kind === 'diff'" class="ai-card">
    <div class="ai-card-head">
      <span class="ai-card-title">工作区变更</span>
      <n-tag size="tiny">{{ props.item.files.length }} 个文件</n-tag>
    </div>
    <ul class="ai-file-list">
      <li v-for="file in props.item.files" :key="file.path">
        <span class="ai-file-status" :data-status="file.status">{{ file.status }}</span>
        <span class="ai-file-path">{{ file.path }}</span>
      </li>
    </ul>
  </div>

  <div v-else-if="props.item.kind === 'error'" class="ai-card ai-error-card">
    <div class="ai-card-head">
      <span class="ai-card-title">执行失败</span>
      <n-tag size="tiny" type="error">{{ props.item.code }}</n-tag>
    </div>
    <pre>{{ props.item.message }}</pre>
    <details v-if="props.item.details !== undefined" class="ai-details">
      <summary>错误详情</summary>
      <pre>{{ json(props.item.details) }}</pre>
    </details>
  </div>

  <div v-else-if="props.item.kind === 'notice'" class="ai-notice">
    {{ props.item.text }}
  </div>
</template>
