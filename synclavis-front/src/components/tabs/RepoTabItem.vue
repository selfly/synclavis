<script setup lang="ts">
import { AlertTriangle, GitBranch, Loader2, X } from 'lucide-vue-next';
import { useOperationsStore } from '@/stores/operations';
import type { RepoTabState } from '@/stores/tabs';

const props = defineProps<{
  tab: RepoTabState;
  active: boolean;
  /** 正在被拖拽的 Tab（半透明） */
  dragging?: boolean;
  /** 拖拽落点提示：落在本 Tab 的前/后 */
  dropPosition?: 'before' | 'after' | null;
}>();

const emit = defineEmits<{
  (e: 'activate', id: string): void;
  (e: 'close', id: string): void;
  (e: 'dragstart', id: string): void;
  (e: 'dragover', id: string, after: boolean): void;
  (e: 'dragend'): void;
}>();

const operations = useOperationsStore();

function busy(id: string): boolean {
  return operations.isRepoBusy(id);
}

function isDirty(tab: RepoTabState): boolean {
  const status = tab.status;
  if (!status) {
    return false;
  }
  return status.staged.length > 0 || status.unstaged.length > 0 || status.untracked.length > 0;
}

function aheadBehind(tab: RepoTabState): string {
  const status = tab.status;
  if (!status) {
    return '';
  }
  const parts: string[] = [];
  if (status.ahead > 0) {
    parts.push(`↑${status.ahead}`);
  }
  if (status.behind > 0) {
    parts.push(`↓${status.behind}`);
  }
  return parts.join(' ');
}

/** 中键关闭 */
function onAuxClick(event: MouseEvent, id: string): void {
  if (event.button === 1) {
    event.preventDefault();
    emit('close', id);
  }
}

/** 拖拽起始：必须写入 dataTransfer，否则 Firefox 不触发拖拽 */
function onDragStart(event: DragEvent): void {
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', props.tab.id);
  }
  emit('dragstart', props.tab.id);
}

/**
 * 拖拽经过：按指针在中线左侧还是右侧，决定落在本 Tab 前还是后。
 *
 * 真正落地在 `RepoTabBar` 的容器上，这样落在 Tab 之间的空隙也能用最近一次的落点。
 */
function onDragOver(event: DragEvent): void {
  const target = event.currentTarget as HTMLElement | null;
  if (!target) {
    return;
  }
  const rect = target.getBoundingClientRect();
  emit('dragover', props.tab.id, event.clientX > rect.left + rect.width / 2);
}
</script>

<template>
  <div
    class="group relative flex h-[28px] min-w-[140px] max-w-[260px] cursor-pointer items-center gap-1.5 rounded-t border-r border-border px-2 text-xs transition-colors"
    :class="[
      active ? 'bg-bg text-text' : 'bg-bg-elev text-text-dim hover:bg-bg-hover',
      dragging ? 'opacity-40' : '',
      dropPosition === 'before'
        ? 'before:absolute before:inset-y-0 before:-left-px before:w-0.5 before:bg-accent'
        : '',
      dropPosition === 'after'
        ? 'after:absolute after:inset-y-0 after:-right-px after:w-0.5 after:bg-accent'
        : '',
    ]"
    :title="tab.path"
    draggable="true"
    data-testid="repo-tab"
    :data-repo-id="tab.id"
    @click="emit('activate', tab.id)"
    @auxclick="onAuxClick($event, tab.id)"
    @dragstart="onDragStart"
    @dragover.prevent="onDragOver"
    @dragend="emit('dragend')"
  >
    <Loader2 v-if="busy(tab.id)" :size="12" class="shrink-0 animate-spin text-accent" />
    <GitBranch v-else :size="12" class="shrink-0" />

    <AlertTriangle
      v-if="tab.status && tab.status.conflicts.length > 0"
      :size="12"
      class="shrink-0 text-warn"
    />

    <span class="truncate">{{ tab.name }}</span>

    <span v-if="tab.status?.branch" class="shrink-0 text-text-dim">{{ tab.status.branch }}</span>
    <span v-if="isDirty(tab)" class="shrink-0 text-warn">*</span>
    <span v-if="aheadBehind(tab)" class="shrink-0 text-text-dim">{{ aheadBehind(tab) }}</span>

    <button
      type="button"
      class="ml-auto shrink-0 rounded p-0.5 opacity-0 transition-opacity hover:bg-bg-hover group-hover:opacity-100"
      title="关闭"
      @click.stop="emit('close', tab.id)"
    >
      <X :size="12" />
    </button>
  </div>
</template>
