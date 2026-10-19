<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  ChevronDown,
  ChevronRight,
  EyeOff,
  File,
  FileSymlink,
  RefreshCw,
  Search,
} from 'lucide-vue-next';
import { useFilesStore } from '@/stores/files';
import { useReposStore } from '@/stores/repos';
import { flattenProjectTree, splitPath } from '@/utils/projectTree';

/**
 * 侧栏「文件」模式：仓库工作区文件树（懒加载）+ 关键字搜索。
 *
 * <p>与 IDEA 一致：单击目录前的箭头展开/折叠，双击目录行同样切换，
 * 双击文件查看内容（文件没有箭头，不会和展开语义打架）。
 * 关键字过滤走后端搜索（`/files/search`），因为树是逐目录懒加载的，
 * 本地过滤看不到未展开目录里的文件。</p>
 */
const props = defineProps<{ repoId: string }>();

/** 输入防抖：避免每个按键都打一次全仓库遍历 */
const SEARCH_DEBOUNCE_MS = 220;

const files = useFilesStore();
const repos = useReposStore();
const filter = ref('');
let timer: ReturnType<typeof setTimeout> | undefined;

const tree = computed(() => files.tree(props.repoId));
const searching = computed(() => filter.value.trim().length > 0);
const search = computed(() => files.search(props.repoId));
/** 当前正在查看的文件（仓库内相对路径），用于树/搜索结果里的高亮 */
const openedPath = computed(() => files.openedPath(props.repoId));

const rows = computed(() => flattenProjectTree(tree.value.children, tree.value.expanded));
/** 搜索结果的「目录前缀 + 文件名」预先拆好，避免模板里逐行重复计算 */
const results = computed(() =>
  search.value.matches.map((entry) => ({ entry, parts: splitPath(entry.path) })),
);

onMounted(() => {
  void files.ensureRoot(props.repoId);
});

onBeforeUnmount(() => {
  if (timer) {
    clearTimeout(timer);
  }
});

watch(filter, (value) => {
  if (timer) {
    clearTimeout(timer);
  }
  timer = setTimeout(() => {
    void files.runSearch(props.repoId, value);
  }, SEARCH_DEBOUNCE_MS);
});

function isExpanded(path: string): boolean {
  return tree.value.expanded.has(path);
}

/** 行样式：正在查看的文件优先高亮，其次弱化被忽略项，其余正常 */
function rowClass(path: string, ignored: boolean): string {
  if (path === openedPath.value) {
    return 'bg-accent/15 text-accent';
  }
  return ignored ? 'text-text-dim opacity-60' : 'text-text';
}

function onToggleDir(path: string): void {
  void files.toggleDir(props.repoId, path);
}

function onActivate(node: { path: string; dir: boolean }): void {
  if (node.dir) {
    onToggleDir(node.path);
    return;
  }
  openFile(node.path);
}

function openFile(path: string): void {
  // 先清掉 Diff 选择：这样右列一定能从文件内容切回 Diff（见 repos.clearSelection 的说明）
  repos.clearSelection(props.repoId);
  void files.openFile(props.repoId, path);
}

async function onToggleIgnored(): Promise<void> {
  await files.setShowIgnored(props.repoId, !tree.value.showIgnored);
  // 忽略项会影响搜索结果，正在搜索时立刻按新口径重查
  if (searching.value) {
    await files.runSearch(props.repoId, filter.value);
  }
}

function onRefresh(): void {
  void files.refresh(props.repoId);
  if (searching.value) {
    void files.runSearch(props.repoId, filter.value);
  }
}
</script>

<template>
  <div class="flex min-h-0 flex-1 flex-col overflow-hidden">
    <div class="flex shrink-0 items-center gap-1 border-b border-border px-2 py-1">
      <Search :size="11" class="shrink-0 text-text-dim" />
      <input
        v-model="filter"
        type="text"
        placeholder="过滤文件"
        class="min-w-0 flex-1 bg-transparent text-xs text-text placeholder:text-text-dim focus:outline-none"
        data-testid="project-file-filter"
      />
      <button
        type="button"
        class="shrink-0 rounded p-0.5 hover:bg-bg-hover"
        :class="tree.showIgnored ? 'text-accent' : 'text-text-dim hover:text-text'"
        :title="tree.showIgnored ? '隐藏被忽略的文件' : '显示被忽略的文件'"
        data-testid="project-file-toggle-ignored"
        @click="onToggleIgnored"
      >
        <EyeOff :size="11" />
      </button>
      <button
        type="button"
        class="shrink-0 rounded p-0.5 text-text-dim hover:bg-bg-hover hover:text-text"
        title="刷新文件树"
        data-testid="project-file-refresh"
        @click="onRefresh"
      >
        <RefreshCw :size="11" />
      </button>
    </div>

    <!-- 搜索模式：结果来自后端，能命中尚未展开的目录 -->
    <div
      v-if="searching"
      class="min-h-0 flex-1 overflow-y-auto overflow-x-hidden py-1"
      data-testid="project-file-results"
    >
      <p v-if="search.loading && results.length === 0" class="px-2 py-1 text-xs text-text-dim">
        搜索中…
      </p>
      <p v-else-if="search.error" class="px-2 py-1 text-xs text-danger">{{ search.error }}</p>
      <template v-else>
        <div
          v-for="row in results"
          :key="row.entry.path"
          class="flex cursor-pointer items-center gap-1 px-2 py-0.5 text-xs hover:bg-bg-hover"
          :class="rowClass(row.entry.path, row.entry.ignored)"
          :title="`双击查看：${row.entry.path}`"
          data-testid="project-search-result"
          :data-path="row.entry.path"
          :data-current="row.entry.path === openedPath ? 'true' : 'false'"
          @dblclick="openFile(row.entry.path)"
        >
          <File :size="11" class="shrink-0" />
          <span v-if="row.parts.dir" class="truncate text-text-dim">{{ row.parts.dir }}/</span>
          <span class="shrink-0">{{ row.parts.name }}</span>
        </div>
        <p v-if="results.length === 0" class="px-2 py-1 text-xs text-text-dim">没有匹配的文件</p>
        <p v-if="search.truncated" class="px-2 py-1 text-xxs text-warn">
          结果过多，仅显示前 {{ results.length }} 项
        </p>
      </template>
    </div>

    <ul
      v-else
      class="min-h-0 flex-1 overflow-y-auto overflow-x-hidden py-1"
      data-testid="project-file-tree"
    >
      <li
        v-for="row in rows"
        :key="row.node.path"
        class="flex cursor-pointer items-center gap-1 py-0.5 pr-2 text-xs hover:bg-bg-hover"
        :class="rowClass(row.node.path, row.node.ignored)"
        :style="{ paddingLeft: `${6 + row.depth * 12}px` }"
        :title="row.node.dir ? row.node.path : `双击查看：${row.node.path}`"
        :data-node-type="row.node.dir ? 'dir' : 'file'"
        :data-path="row.node.path"
        :data-current="row.node.path === openedPath ? 'true' : 'false'"
        @dblclick="onActivate(row.node)"
      >
        <template v-if="row.node.dir">
          <!-- 箭头是独立热区：单击即展开/折叠；-ml-0.5 抵消 padding，保持与文件图标对齐 -->
          <button
            type="button"
            class="-ml-0.5 shrink-0 rounded p-0.5 text-text-dim hover:text-text"
            :title="isExpanded(row.node.path) ? '折叠目录' : '展开目录'"
            :aria-expanded="isExpanded(row.node.path) ? 'true' : 'false'"
            data-testid="project-file-chevron"
            :data-path="row.node.path"
            @click.stop="onToggleDir(row.node.path)"
          >
            <ChevronDown v-if="isExpanded(row.node.path)" :size="11" />
            <ChevronRight v-else :size="11" />
          </button>
          <span class="min-w-0 truncate">{{ row.node.name }}</span>
        </template>
        <template v-else>
          <FileSymlink v-if="row.node.symlink" :size="11" class="shrink-0 text-text-dim" />
          <File v-else :size="11" class="shrink-0 text-text-dim" />
          <span class="min-w-0 truncate">{{ row.node.name }}</span>
        </template>
      </li>

      <li v-if="rows.length === 0 && tree.loading" class="px-2 py-1 text-xs text-text-dim">
        加载中…
      </li>
      <li v-else-if="rows.length === 0" class="px-2 py-1 text-xs text-text-dim">空目录</li>
    </ul>

    <p v-if="tree.error" class="shrink-0 bg-danger/10 px-2 py-1 text-xxs text-danger">
      {{ tree.error }}
    </p>
  </div>
</template>
