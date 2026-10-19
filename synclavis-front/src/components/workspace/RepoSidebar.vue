<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  Archive,
  ChevronDown,
  ChevronRight,
  FolderGit2,
  FolderTree,
  GitBranch,
  Plus,
  Tag,
} from 'lucide-vue-next';
import ProjectFileTree from '@/components/files/ProjectFileTree.vue';
import { useFilesStore, type SidebarMode } from '@/stores/files';
import { useReposStore } from '@/stores/repos';
import { useTabsStore } from '@/stores/tabs';
import type { BranchInfo } from '@/types/repo';
import { groupRemoteBranches, shortRemoteBranchName } from '@/utils/remotes';

const props = defineProps<{ repoId: string; width: number }>();
const emit = defineEmits<{
  (e: 'settings'): void;
  /** 双击本地分支：切换到它（当前分支不触发） */
  (e: 'checkout', branch: BranchInfo): void;
  /** 双击远程分支：检出到本地（由它创建同名本地跟踪分支并切换） */
  (e: 'checkout-remote', payload: { name: string; remote: string }): void;
  /** 「本地分支」标题旁的 `+`：新建分支并切换 */
  (e: 'new-branch'): void;
}>();

const repos = useReposStore();
const tabs = useTabsStore();
const files = useFilesStore();

/** 侧栏两种模式：Git（分支/标签/Stash）与文件（项目文件树） */
const mode = computed<SidebarMode>(() => files.mode);

function switchMode(next: SidebarMode): void {
  if (next === mode.value) {
    return;
  }
  // 具体的根目录加载由 ProjectFileTree 挂载时负责，这里只切模式，避免重复请求
  files.setMode(next);
}

const detail = computed(() => repos.detail(props.repoId));
const tab = computed(() => tabs.tabById(props.repoId));

const localBranches = computed(() => detail.value.branches.filter((b) => b.local));
const remoteBranches = computed(() => detail.value.branches.filter((b) => !b.local));
/** 远程分支按 remote 分组折叠（组内显示去掉 remote 前缀的短名） */
const remoteGroups = computed(() =>
  groupRemoteBranches(remoteBranches.value, detail.value.status?.remotes ?? []),
);

const collapsed = ref<Record<string, boolean>>({});

function toggleGroup(name: string): void {
  collapsed.value = { ...collapsed.value, [name]: !collapsed.value[name] };
}

/** 单击不做任何事（避免误触），双击才切换；当前分支不响应。 */
function onLocalDoubleClick(branch: BranchInfo): void {
  if (branch.current) {
    return;
  }
  emit('checkout', branch);
}

function onRemoteDoubleClick(branch: BranchInfo, remote: string): void {
  emit('checkout-remote', { name: branch.name, remote });
}
</script>

<template>
  <aside
    class="flex shrink-0 flex-col overflow-hidden border-r border-border bg-bg-elev"
    data-testid="repo-sidebar"
    :style="{ width: `${width}px` }"
  >
    <section class="shrink-0 border-b border-border px-2 py-2">
      <div class="flex items-center gap-1.5 text-xs font-medium text-text">
        <FolderGit2 :size="12" />
        <span class="truncate">{{ tab?.name ?? repoId }}</span>
      </div>
      <p class="mt-1 truncate text-xxs text-text-dim" :title="tab?.path">{{ tab?.path }}</p>
      <p v-if="detail.status" class="mt-1 text-xxs text-text-dim">
        分支 <span class="text-text">{{ detail.status.branch ?? '(detached)' }}</span>
        <span v-if="detail.status.ahead > 0" class="ml-1">↑{{ detail.status.ahead }}</span>
        <span v-if="detail.status.behind > 0" class="ml-1">↓{{ detail.status.behind }}</span>
      </p>
      <p v-if="tab?.sshKeyPath" class="mt-0.5 truncate text-xxs text-text-dim" :title="tab.sshKeyPath">
        key: {{ tab.sshKeyPath }}
      </p>
    </section>

    <!-- Git / 文件 双模式：文件模式让出整块高度给文件树，两者各自内部滚动 -->
    <div class="flex shrink-0 items-center border-b border-border" role="tablist">
      <button
        type="button"
        role="tab"
        class="flex flex-1 items-center justify-center gap-1 border-b-2 px-2 py-1 text-xxs"
        :class="mode === 'git' ? 'border-accent text-text' : 'border-transparent text-text-dim hover:text-text'"
        :aria-selected="mode === 'git'"
        data-testid="sidebar-mode-git"
        @click="switchMode('git')"
      >
        <GitBranch :size="11" /> Git
      </button>
      <button
        type="button"
        role="tab"
        class="flex flex-1 items-center justify-center gap-1 border-b-2 px-2 py-1 text-xxs"
        :class="mode === 'files' ? 'border-accent text-text' : 'border-transparent text-text-dim hover:text-text'"
        :aria-selected="mode === 'files'"
        data-testid="sidebar-mode-files"
        @click="switchMode('files')"
      >
        <FolderTree :size="11" /> 文件
      </button>
    </div>

    <ProjectFileTree v-if="mode === 'files'" :repo-id="repoId" />

    <div v-else class="min-h-0 flex-1 overflow-y-auto">
      <section class="border-b border-border px-2 py-2">
        <div class="mb-1 flex items-center justify-between">
          <h3 class="flex items-center gap-1 text-xxs uppercase tracking-wide text-text-dim">
            <GitBranch :size="11" /> 本地分支 ({{ localBranches.length }})
          </h3>
          <button
            type="button"
            class="rounded p-0.5 text-text-dim hover:bg-bg-hover hover:text-text"
            title="新建分支"
            data-testid="sidebar-new-branch"
            @click="emit('new-branch')"
          >
            <Plus :size="11" />
          </button>
        </div>
        <ul class="space-y-0.5">
          <li
            v-for="branch in localBranches"
            :key="branch.fullName"
            class="truncate rounded px-1 py-0.5 text-xs hover:bg-bg-hover"
            :class="branch.current ? 'text-accent' : 'cursor-pointer text-text'"
            :title="branch.current ? `${branch.fullName}（当前分支）` : `双击切换到 ${branch.name}`"
            data-testid="local-branch"
            :data-name="branch.name"
            :data-current="branch.current ? 'true' : 'false'"
            @dblclick="onLocalDoubleClick(branch)"
          >
            {{ branch.name }}
            <span v-if="branch.current && (branch.ahead || branch.behind)" class="text-text-dim">
              (↑{{ branch.ahead }} ↓{{ branch.behind }})
            </span>
          </li>
        </ul>
      </section>

      <section class="border-b border-border px-2 py-2">
        <div class="mb-1 flex items-center justify-between">
          <h3 class="text-xxs uppercase tracking-wide text-text-dim">
            远程分支 ({{ remoteBranches.length }})
          </h3>
          <button
            type="button"
            class="rounded p-0.5 text-text-dim hover:bg-bg-hover hover:text-text"
            title="添加远程仓库"
            data-testid="sidebar-add-remote"
            @click="emit('settings')"
          >
            <Plus :size="11" />
          </button>
        </div>

        <p v-if="remoteGroups.length === 0" class="px-1 text-xxs text-text-dim">无</p>

        <div v-for="group in remoteGroups" :key="group.name" class="mb-1">
          <button
            type="button"
            class="flex w-full min-w-0 items-center gap-1 rounded px-1 py-0.5 text-left text-xxs text-text-dim hover:bg-bg-hover hover:text-text"
            data-testid="remote-group"
            :data-name="group.name"
            @click="toggleGroup(group.name)"
          >
            <ChevronDown v-if="!collapsed[group.name]" :size="10" class="shrink-0" />
            <ChevronRight v-else :size="10" class="shrink-0" />
            <span class="min-w-0 truncate">{{ group.name }}</span>
            <span class="shrink-0">({{ group.branches.length }})</span>
          </button>
          <ul v-if="!collapsed[group.name]" class="space-y-0.5">
            <li
              v-for="branch in group.branches"
              :key="branch.fullName"
              class="cursor-pointer truncate rounded py-0.5 pl-4 pr-1 text-xs text-text-dim hover:bg-bg-hover"
              :title="`双击检出 ${branch.name} 到本地（创建同名跟踪分支）`"
              data-testid="remote-branch"
              :data-name="branch.name"
              @dblclick="onRemoteDoubleClick(branch, group.name)"
            >
              {{ shortRemoteBranchName(branch.name, group.name) }}
            </li>
          </ul>
        </div>
      </section>

      <section class="border-b border-border px-2 py-2">
        <h3 class="mb-1 flex items-center gap-1 text-xxs uppercase tracking-wide text-text-dim">
          <Tag :size="11" /> 标签 ({{ detail.tags.length }})
        </h3>
        <ul class="space-y-0.5">
          <li
            v-for="tag in detail.tags"
            :key="tag.name"
            class="truncate rounded px-1 py-0.5 text-xs hover:bg-bg-hover"
            :title="tag.message ?? tag.name"
          >
            {{ tag.name }}
          </li>
          <li v-if="detail.tags.length === 0" class="px-1 text-xxs text-text-dim">无</li>
        </ul>
      </section>

      <section class="px-2 py-2">
        <h3 class="mb-1 flex items-center gap-1 text-xxs uppercase tracking-wide text-text-dim">
          <Archive :size="11" /> Stashes ({{ detail.stashes.length }})
        </h3>
        <ul class="space-y-0.5">
          <li
            v-for="stash in detail.stashes"
            :key="stash.commitId"
            class="truncate rounded px-1 py-0.5 text-xs hover:bg-bg-hover"
            :title="stash.message"
          >
            stash@{{ '{' }}{{ stash.index }}{{ '}' }}
          </li>
          <li v-if="detail.stashes.length === 0" class="px-1 text-xxs text-text-dim">无</li>
        </ul>
      </section>
    </div>
  </aside>
</template>
