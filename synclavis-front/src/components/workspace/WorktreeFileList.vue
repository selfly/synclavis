<script setup lang="ts">
import { computed, ref } from 'vue';
import { ChevronDown, ChevronRight, Minus, Plus } from 'lucide-vue-next';
import {
  buildFileTree,
  collectFilePaths,
  flattenVisible,
  type FileTreeNode,
  type FileViewMode,
  type VisibleRow,
  type WorktreeFileItem,
} from '@/utils/fileTree';
import type { WorktreeFileRef } from '@/stores/repos';

/**
 * 单个分区的文件列表（树形/列表两种渲染）。
 *
 * <p>纯展示：数据与选中态由父组件传入，暂存/取消暂存/查看差异通过事件上抛，
 * 组件本身不碰 store，便于父组件复用给「已暂存」「未暂存」「未跟踪」三个分区。</p>
 */
const props = defineProps<{
  files: WorktreeFileItem[];
  /** 决定 +/- 语义（staged 只取消暂存，其余只暂存）与文件配色 */
  section: 'staged' | 'unstaged' | 'untracked';
  view: FileViewMode;
  selected: WorktreeFileRef | null;
}>();

const emit = defineEmits<{
  (e: 'inspect', path: string, staged: boolean): void;
  (e: 'stage', paths: string[]): void;
  (e: 'unstage', paths: string[]): void;
}>();

const stagedSection = computed(() => props.section === 'staged');

const fileClass = computed(() => {
  if (props.section === 'staged') {
    return 'text-success';
  }
  return props.section === 'untracked' ? 'text-warn' : 'text-text-dim';
});

const emptyText = computed(() => {
  if (props.section === 'staged') {
    return '无已暂存文件';
  }
  return props.section === 'untracked' ? '无未跟踪文件' : '无未暂存文件';
});

/** 折叠的是目录节点，key 用完整路径；默认全展开（集合为空） */
const collapsed = ref<Set<string>>(new Set());

function isCollapsed(path: string): boolean {
  return collapsed.value.has(path);
}

function toggleDir(path: string): void {
  const next = new Set(collapsed.value);
  if (next.has(path)) {
    next.delete(path);
  } else {
    next.add(path);
  }
  collapsed.value = next;
}

/**
 * 渲染行。
 *
 * <p>列表模式下按传入顺序平铺（与旧实现一致：分区内保持后端返回顺序）；
 * 树形模式走 buildFileTree + flattenVisible，目录在前、文件在后。</p>
 */
const rows = computed<VisibleRow[]>(() => {
  if (props.view === 'list') {
    return props.files.map((file) => ({
      node: {
        name: file.path,
        path: file.path,
        dir: false,
        changeType: file.changeType,
        count: 1,
        children: [],
      } satisfies FileTreeNode,
      depth: 0,
    }));
  }
  return flattenVisible(buildFileTree(props.files), isCollapsed);
});

function isSelected(path: string): boolean {
  const current = props.selected;
  return current !== null && current.path === path && current.staged === stagedSection.value;
}

function inspect(path: string): void {
  emit('inspect', path, stagedSection.value);
}

function actOnPaths(paths: string[]): void {
  if (paths.length === 0) {
    return;
  }
  if (stagedSection.value) {
    emit('unstage', paths);
  } else {
    emit('stage', paths);
  }
}
</script>

<template>
  <ul
    class="min-h-0 flex-1 overflow-y-auto overflow-x-hidden"
    :data-testid="`commit-file-list-${section}`"
    :data-section="section"
  >
    <li
      v-for="row in rows"
      :key="`${section}-${row.node.path}`"
      class="group flex cursor-pointer items-center gap-1 py-0.5 pr-2 text-xs hover:bg-bg-hover"
      :style="{ paddingLeft: `${8 + row.depth * 12}px` }"
      :data-node-type="row.node.dir ? 'dir' : 'file'"
      :data-path="row.node.path"
      :data-selected="!row.node.dir && isSelected(row.node.path)"
      :title="row.node.dir ? row.node.path : `点击查看差异：${row.node.path}`"
      @click="row.node.dir ? toggleDir(row.node.path) : inspect(row.node.path)"
    >
      <template v-if="row.node.dir">
        <ChevronRight v-if="isCollapsed(row.node.path)" :size="11" class="shrink-0 text-text-dim" />
        <ChevronDown v-else :size="11" class="shrink-0 text-text-dim" />
        <span class="min-w-0 truncate text-text">{{ row.node.name }}</span>
        <span class="shrink-0 text-xxs text-text-dim">{{ row.node.count }}</span>
        <button
          type="button"
          class="ml-auto shrink-0 rounded p-0.5 opacity-0 group-hover:opacity-100 hover:bg-bg"
          :title="stagedSection ? '取消暂存整个目录' : '暂存整个目录'"
          @click.stop="actOnPaths(collectFilePaths(row.node))"
        >
          <Minus v-if="stagedSection" :size="11" />
          <Plus v-else :size="11" />
        </button>
      </template>

      <template v-else>
        <span class="min-w-0 truncate" :class="fileClass">{{ row.node.name }}</span>
        <button
          type="button"
          class="ml-auto shrink-0 rounded p-0.5 opacity-0 group-hover:opacity-100 hover:bg-bg"
          :title="stagedSection ? '取消暂存' : '暂存'"
          @click.stop="actOnPaths([row.node.path])"
        >
          <Minus v-if="stagedSection" :size="11" />
          <Plus v-else :size="11" />
        </button>
      </template>
    </li>

    <li v-if="rows.length === 0" class="px-2 py-1 text-xs text-text-dim">
      {{ emptyText }}
    </li>
  </ul>
</template>
