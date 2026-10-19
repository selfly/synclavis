<script setup lang="ts">
import { computed } from 'vue';
import { DEFAULT_LANE_WIDTH, DEFAULT_ROW_HEIGHT } from '@/utils/gitGraph';
import type { GraphRow, GraphSegment } from '@/utils/gitGraph';

/**
 * 单行泳道图（见《详细设计文档》§9.5.4）。
 *
 * 只画本行的线段与圆点：不访问 store、不做测量，因此与窗口化渲染完全解耦
 * （相邻行没被渲染也不影响本行；y 坐标由行高常量推导，与 §9.5.2 同源）。
 */
const props = defineProps<{
  row: GraphRow;
  laneCount: number;
  laneWidth?: number;
  height?: number;
}>();

const laneWidth = computed(() => props.laneWidth ?? DEFAULT_LANE_WIDTH);
const height = computed(() => props.height ?? DEFAULT_ROW_HEIGHT);
const width = computed(() => props.laneCount * laneWidth.value);

/** 直线直接连；跨列用三次贝塞尔（控制点取中点），避免出现视觉折角 */
function toPath(segment: GraphSegment): string {
  if (!segment.curve) {
    return `M ${segment.x1} ${segment.y1} L ${segment.x2} ${segment.y2}`;
  }
  const mid = (segment.y1 + segment.y2) / 2;
  return `M ${segment.x1} ${segment.y1} C ${segment.x1} ${mid} ${segment.x2} ${mid} ${segment.x2} ${segment.y2}`;
}
</script>

<template>
  <svg
    class="shrink-0"
    :width="width"
    :height="height"
    :viewBox="`0 0 ${width} ${height}`"
    :data-lane="row.lane"
    :data-overflow="row.overflow ? row.overflowCount : undefined"
    aria-hidden="true"
  >
    <path
      v-for="(segment, index) in row.segments"
      :key="index"
      :d="toPath(segment)"
      fill="none"
      :stroke="`var(--sl-graph-${segment.color})`"
      stroke-width="1.5"
      stroke-linecap="round"
    />
    <circle :cx="row.x" :cy="row.y" r="3.5" :fill="`var(--sl-graph-${row.color})`" />
    <!-- 超出 MAX_LANES 的列被并入哨兵列：用一个 +N 提示（不额外占宽，见 §9.5.4） -->
    <text
      v-if="row.overflow"
      :x="width - 1"
      y="9"
      text-anchor="end"
      font-size="9"
      fill="rgb(var(--sl-text-dim-rgb))"
    >
      +{{ row.overflowCount }}
    </text>
  </svg>
</template>
