<script setup lang="ts">
import { ref } from 'vue';
import OpenRepoButton from './OpenRepoButton.vue';
import RepoTabItem from './RepoTabItem.vue';
import BrandLogo from '@/components/common/BrandLogo.vue';
import ThemeToggle from '@/components/common/ThemeToggle.vue';
import { useTabsStore } from '@/stores/tabs';

const emit = defineEmits<{ (e: 'open'): void }>();

const tabs = useTabsStore();

const scroller = ref<HTMLDivElement | null>(null);
const draggingId = ref<string | null>(null);
const dropTarget = ref<{ id: string; after: boolean } | null>(null);

async function onClose(id: string): Promise<void> {
  await tabs.closeRepo(id);
}

function onDragStart(id: string): void {
  draggingId.value = id;
  dropTarget.value = null;
}

function onDragOver(id: string, after: boolean): void {
  // 拖过自己时不显示落点（否则会出现「自己插到自己旁边」的假提示）
  if (draggingId.value === null || draggingId.value === id) {
    dropTarget.value = null;
    return;
  }
  if (dropTarget.value?.id === id && dropTarget.value.after === after) {
    return;
  }
  dropTarget.value = { id, after };
}

/** Tab 溢出时，指针贴近左右边缘自动横向滚动，否则拖不到视口外的位置 */
function autoScroll(event: DragEvent): void {
  const el = scroller.value;
  if (!el || draggingId.value === null) {
    return;
  }
  const rect = el.getBoundingClientRect();
  const edge = 24;
  if (event.clientX < rect.left + edge) {
    el.scrollLeft -= 12;
  } else if (event.clientX > rect.right - edge) {
    el.scrollLeft += 12;
  }
}

/** 落点放在容器上：Tab 之间的空隙也能沿用最近一次计算的插入位置 */
function onDrop(): void {
  const from = draggingId.value === null ? -1 : tabs.tabs.findIndex((tab) => tab.id === draggingId.value);
  const target = dropTarget.value;
  if (from >= 0 && target) {
    const targetIndex = tabs.tabs.findIndex((tab) => tab.id === target.id);
    if (targetIndex >= 0) {
      let to = target.after ? targetIndex + 1 : targetIndex;
      // 移除元素后，右侧目标的下标会整体左移一位
      if (from < to) {
        to -= 1;
      }
      if (to !== from) {
        void tabs.reorder(from, to);
      }
    }
  }
  resetDrag();
}

function resetDrag(): void {
  draggingId.value = null;
  dropTarget.value = null;
}
</script>

<template>
  <div class="flex h-[34px] shrink-0 items-end gap-0.5 border-b border-border bg-bg-elev px-1">
    <BrandLogo class="border-r border-border" />
    <div
      ref="scroller"
      class="flex min-w-0 flex-1 items-end gap-0.5 overflow-x-auto"
      @dragover.prevent="autoScroll"
      @drop.prevent="onDrop"
    >
      <RepoTabItem
        v-for="tab in tabs.tabs"
        :key="tab.id"
        :tab="tab"
        :active="tab.id === tabs.activeId"
        :dragging="tab.id === draggingId"
        :drop-position="
          dropTarget?.id === tab.id ? (dropTarget.after ? 'after' : 'before') : null
        "
        @activate="tabs.setActive($event)"
        @close="onClose"
        @dragstart="onDragStart"
        @dragover="onDragOver"
        @dragend="resetDrag"
      />
    </div>
    <OpenRepoButton @open="emit('open')" />
    <ThemeToggle />
  </div>
</template>
