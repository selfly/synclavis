<script setup lang="ts">
import { AlertTriangle, GitBranch, Loader2, X } from 'lucide-vue-next';
import { useOperationsStore } from '@/stores/operations';
import type { RepoTabState } from '@/stores/tabs';

defineProps<{ tab: RepoTabState; active: boolean }>();

const emit = defineEmits<{
  (e: 'activate', id: string): void;
  (e: 'close', id: string): void;
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
</script>

<template>
  <div
    class="group flex h-[28px] min-w-[140px] max-w-[260px] cursor-pointer items-center gap-1.5 rounded-t border-r border-border px-2 text-xs transition-colors"
    :class="active ? 'bg-bg text-text' : 'bg-bg-elev text-text-dim hover:bg-bg-hover'"
    :title="tab.path"
    @click="emit('activate', tab.id)"
    @auxclick="onAuxClick($event, tab.id)"
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
