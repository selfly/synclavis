<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { NButton, useMessage } from 'naive-ui';
import { ChevronDown, ChevronUp, Copy, Trash2 } from 'lucide-vue-next';
import ResizeHandle from '@/components/common/ResizeHandle.vue';
import { useOperationsStore } from '@/stores/operations';

const props = defineProps<{ repoId: string; height: number; maxHeight: number }>();

const emit = defineEmits<{
  (e: 'update:height', value: number): void;
  (e: 'commit-height', value: number): void;
}>();

const operations = useOperationsStore();
const message = useMessage();

const MIN_HEIGHT = 80;

const collapsed = ref(true);
const autoScroll = ref(true);
const scroller = ref<HTMLElement | null>(null);

const lines = computed(() => operations.log[props.repoId] ?? []);

watch(
  () => lines.value.length,
  async () => {
    if (!autoScroll.value || collapsed.value) {
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
  <section class="flex shrink-0 flex-col border-t border-border bg-bg-elev">
    <!-- 拖拽调高（仅展开时可用）；invert：向上拖变大 -->
    <ResizeHandle
      v-if="!collapsed"
      axis="y"
      invert
      :size="height"
      :min="MIN_HEIGHT"
      :max="maxHeight"
      label="调整日志面板高度"
      @update:size="emit('update:height', $event)"
      @change="emit('commit-height', $event)"
    />

    <div class="flex h-[26px] shrink-0 items-center gap-2 px-2 text-xxs text-text-dim">
      <button
        type="button"
        class="flex items-center gap-1 rounded px-1 hover:bg-bg-hover"
        @click="collapsed = !collapsed"
      >
        <component :is="collapsed ? ChevronUp : ChevronDown" :size="11" />
        操作日志（{{ lines.length }}）
      </button>

      <label class="ml-2 flex items-center gap-1">
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
      v-show="!collapsed"
      ref="scroller"
      class="overflow-y-auto border-t border-border bg-bg px-2 py-1 font-mono text-xxs leading-[17px]"
      data-testid="operation-log"
      :style="{ height: `${height}px` }"
    >
      <p v-if="lines.length === 0" class="text-text-dim">暂无输出</p>
      <p v-for="line in lines" :key="line.id" :class="levelClass[line.level]">
        <span class="text-text-dim">[{{ line.time }}]</span>
        {{ line.text }}
      </p>
    </div>
  </section>
</template>
