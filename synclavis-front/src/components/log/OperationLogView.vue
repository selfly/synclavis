<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { NButton, useMessage } from 'naive-ui';
import { Copy, Trash2 } from 'lucide-vue-next';
import { useOperationsStore } from '@/stores/operations';

const props = defineProps<{ repoId: string }>();

const operations = useOperationsStore();
const message = useMessage();

const autoScroll = ref(true);
const scroller = ref<HTMLElement | null>(null);

const lines = computed(() => operations.log[props.repoId] ?? []);

watch(
  () => lines.value.length,
  async () => {
    if (!autoScroll.value) {
      return;
    }
    await nextTick();
    if (scroller.value) {
      scroller.value.scrollTop = scroller.value.scrollHeight;
    }
  },
);

const levelClass: Record<string, string> = {
  info: 'text-text',
  warn: 'text-warn',
  error: 'text-danger',
};

async function copyAll(): Promise<void> {
  const text = lines.value
    .map((line) => `[${line.time}] ${line.level.toUpperCase()} ${line.text}`)
    .join('\n');
  try {
    await navigator.clipboard.writeText(text);
    message.success('日志已复制');
  } catch {
    message.error('复制失败');
  }
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col">
    <div class="flex h-[24px] shrink-0 items-center gap-2 border-b border-border px-2 text-xxs text-text-dim">
      <label class="flex items-center gap-1">
        <input v-model="autoScroll" type="checkbox" class="h-3 w-3" />
        自动滚动
      </label>

      <div class="ml-auto flex items-center gap-1">
        <n-button size="tiny" quaternary :disabled="lines.length === 0" @click="copyAll">
          <template #icon><Copy :size="11" /></template>
          复制
        </n-button>
        <n-button
          size="tiny"
          quaternary
          :disabled="lines.length === 0"
          @click="operations.clearLog(repoId)"
        >
          <template #icon><Trash2 :size="11" /></template>
          清空
        </n-button>
      </div>
    </div>

    <div
      ref="scroller"
      class="min-h-0 flex-1 overflow-y-auto bg-bg px-2 py-1 font-mono text-xxs leading-[17px]"
      data-testid="operation-log"
    >
      <p v-if="lines.length === 0" class="text-text-dim">暂无输出</p>
      <p v-for="line in lines" :key="line.id" :class="levelClass[line.level]">
        <span class="text-text-dim">[{{ line.time }}]</span>
        {{ line.text }}
      </p>
    </div>
  </div>
</template>
