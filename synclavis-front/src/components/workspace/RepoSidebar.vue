<script setup lang="ts">
import { computed, ref } from 'vue';
import { Archive, ChevronDown, ChevronRight, FolderGit2, GitBranch, Plus, Tag } from 'lucide-vue-next';
import { useReposStore } from '@/stores/repos';
import { useTabsStore } from '@/stores/tabs';
import { groupRemoteBranches, shortRemoteBranchName } from '@/utils/remotes';

const props = defineProps<{ repoId: string; width: number }>();
const emit = defineEmits<{ (e: 'settings'): void }>();

const repos = useReposStore();
const tabs = useTabsStore();

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
</script>

<template>
  <aside
    class="flex shrink-0 flex-col overflow-y-auto border-r border-border bg-bg-elev"
    data-testid="repo-sidebar"
    :style="{ width: `${width}px` }"
  >
    <section class="border-b border-border px-2 py-2">
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

    <section class="border-b border-border px-2 py-2">
      <h3 class="mb-1 flex items-center gap-1 text-xxs uppercase tracking-wide text-text-dim">
        <GitBranch :size="11" /> 本地分支 ({{ localBranches.length }})
      </h3>
      <ul class="space-y-0.5">
        <li
          v-for="branch in localBranches"
          :key="branch.fullName"
          class="truncate rounded px-1 py-0.5 text-xs hover:bg-bg-hover"
          :class="branch.current ? 'text-accent' : 'text-text'"
          :title="branch.fullName"
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
            class="truncate rounded py-0.5 pl-4 pr-1 text-xs text-text-dim hover:bg-bg-hover"
            :title="branch.fullName"
            data-testid="remote-branch"
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
  </aside>
</template>
