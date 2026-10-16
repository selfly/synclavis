<script setup lang="ts">
import { computed } from 'vue';
import { Archive, FolderGit2, GitBranch, Tag } from 'lucide-vue-next';
import { useReposStore } from '@/stores/repos';
import { useTabsStore } from '@/stores/tabs';

const props = defineProps<{ repoId: string }>();

const repos = useReposStore();
const tabs = useTabsStore();

const detail = computed(() => repos.detail(props.repoId));
const tab = computed(() => tabs.tabById(props.repoId));

const localBranches = computed(() => detail.value.branches.filter((b) => b.local));
const remoteBranches = computed(() => detail.value.branches.filter((b) => !b.local));
</script>

<template>
  <aside class="flex w-[240px] shrink-0 flex-col overflow-y-auto border-r border-border bg-bg-elev">
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
      <h3 class="mb-1 text-xxs uppercase tracking-wide text-text-dim">
        远程分支 ({{ remoteBranches.length }})
      </h3>
      <ul class="space-y-0.5">
        <li
          v-for="branch in remoteBranches"
          :key="branch.fullName"
          class="truncate rounded px-1 py-0.5 text-xs text-text-dim hover:bg-bg-hover"
          :title="branch.fullName"
        >
          {{ branch.name }}
        </li>
        <li v-if="remoteBranches.length === 0" class="px-1 text-xxs text-text-dim">无</li>
      </ul>
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
