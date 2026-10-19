<script setup lang="ts">
import { usePanelResize } from '@/composables/usePanelResize';

const props = withDefaults(
  defineProps<{
    /** `y` = 水平分隔条（上下拖动改高度）；`x` = 垂直分隔条（左右拖动改宽度） */
    axis: 'x' | 'y';
    size: number;
    min: number;
    max: number;
    /** true = 向上/向左拖变大（分隔条位于目标面板的上方或左侧） */
    invert?: boolean;
    label?: string;
  }>(),
  { invert: false, label: '调整面板尺寸' },
);

const emit = defineEmits<{
  (e: 'update:size', value: number): void;
  (e: 'change', value: number): void;
}>();

const { dragging, onPointerDown, onPointerMove, onPointerUp, onKeydown } = usePanelResize({
  axis: props.axis,
  size: () => props.size,
  min: () => props.min,
  max: () => props.max,
  invert: props.invert,
  onChange: (value) => emit('update:size', value),
  onCommit: (value) => emit('change', value),
});
</script>

<template>
  <div
    class="group relative z-10 flex shrink-0 select-none items-center justify-center"
    :class="axis === 'y' ? 'h-[5px] w-full cursor-row-resize' : 'h-full w-[5px] cursor-col-resize'"
    role="separator"
    :aria-orientation="axis === 'y' ? 'horizontal' : 'vertical'"
    :aria-label="label"
    :aria-valuenow="size"
    :aria-valuemin="min"
    :aria-valuemax="max"
    tabindex="0"
    :title="`${label}（拖拽或方向键调整）`"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
    @keydown="onKeydown"
  >
    <span
      class="bg-border transition-colors group-hover:bg-accent group-focus-visible:bg-accent"
      :class="[
        axis === 'y' ? 'h-[1px] w-full' : 'h-full w-[1px]',
        dragging ? 'bg-accent' : '',
      ]"
    />
  </div>
</template>
