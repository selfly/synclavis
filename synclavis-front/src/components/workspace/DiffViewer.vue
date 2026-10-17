<script setup lang="ts">
import { computed } from 'vue';
import { FileDiff } from 'lucide-vue-next';
import { useReposStore } from '@/stores/repos';

const props = defineProps<{ repoId: string }>();

const repos = useReposStore();
const detail = computed(() => repos.detail(props.repoId));
const diff = computed(() => detail.value.diff);
const selected = computed(() => repos.selected(props.repoId));
const worktreeFile = computed(() => repos.worktreeFile(props.repoId));

/** 工作区单文件差异只会有一条 entry，用它显示变更类型与增删行数 */
const worktreeEntry = computed(() =>
  worktreeFile.value ? (diff.value?.entries[0] ?? null) : null,
);

interface PatchLine {
  id: number;
  text: string;
  kind: 'add' | 'del' | 'hunk' | 'meta' | 'context';
}

const patchLines = computed<PatchLine[]>(() => {
  const patch = diff.value?.patch;
  if (!patch) {
    return [];
  }
  return patch.split('\n').map((text, id) => {
    let kind: PatchLine['kind'] = 'context';
    if (text.startsWith('@@')) {
      kind = 'hunk';
    } else if (text.startsWith('+++') || text.startsWith('---') || text.startsWith('diff ')) {
      kind = 'meta';
    } else if (text.startsWith('+')) {
      kind = 'add';
    } else if (text.startsWith('-')) {
      kind = 'del';
    }
    return { id, text, kind };
  });
});

const lineClass: Record<PatchLine['kind'], string> = {
  add: 'bg-[var(--sl-diff-add)] text-success',
  del: 'bg-[var(--sl-diff-del)] text-danger',
  hunk: 'text-accent',
  meta: 'text-text-dim',
  context: 'text-text',
};
</script>

<template>
  <section class="flex min-h-0 flex-1 flex-col" data-testid="diff-viewer">
    <div
      class="flex shrink-0 items-center gap-2 border-b border-border px-2 py-1.5 text-xxs text-text-dim"
      data-testid="diff-header"
    >
      <FileDiff :size="12" />

      <!-- 来源一：暂存区/工作区文件（点底部的文件进来） -->
      <template v-if="worktreeFile">
        <span class="shrink-0 rounded-sm bg-accent/15 px-1 text-accent">
          {{ worktreeFile.staged ? '已暂存' : '未暂存' }}
        </span>
        <span class="truncate text-text" :title="worktreeFile.path">{{ worktreeFile.path }}</span>
        <span v-if="worktreeEntry" class="shrink-0">· {{ worktreeEntry.changeType }}</span>
        <span v-if="worktreeEntry && !worktreeEntry.binary" class="shrink-0">
          · <span class="text-success">+{{ worktreeEntry.insertions }}</span>
          <span class="ml-1 text-danger">-{{ worktreeEntry.deletions }}</span>
        </span>
        <span v-if="worktreeEntry?.binary" class="shrink-0 text-warn">· binary</span>
      </template>

      <!-- 来源二：提交 -->
      <template v-else-if="selected">
        <span class="shrink-0">提交 {{ selected.slice(0, 8) }}</span>
        <span v-if="diff" class="shrink-0">· {{ diff.entries.length }} 个文件变更</span>
      </template>

      <span v-else class="shrink-0">未选择提交</span>
    </div>

    <div v-if="!worktreeFile && !selected" class="flex flex-1 items-center justify-center text-xs text-text-dim">
      在上方提交列表中选择一个提交，或点击底部暂存区文件查看差异
    </div>

    <div v-else class="flex min-h-0 flex-1 flex-col">
      <!-- 提交来源才有文件列表；工作区单文件差异不需要列表 -->
      <ul v-if="!worktreeFile" class="max-h-[140px] shrink-0 overflow-y-auto border-b border-border">
        <li
          v-for="entry in diff?.entries ?? []"
          :key="entry.path"
          class="flex cursor-pointer items-center gap-2 px-2 py-1 text-xs hover:bg-bg-hover"
          @click="repos.loadDiff(props.repoId, selected as string, entry.path)"
        >
          <span class="w-[68px] shrink-0 text-xxs text-text-dim">{{ entry.changeType }}</span>
          <span class="truncate">{{ entry.path }}</span>
          <span v-if="entry.binary" class="ml-auto shrink-0 text-xxs text-warn">binary</span>
          <span v-else class="ml-auto shrink-0 text-xxs">
            <span class="text-success">+{{ entry.insertions }}</span>
            <span class="ml-1 text-danger">-{{ entry.deletions }}</span>
          </span>
        </li>
        <li v-if="(diff?.entries.length ?? 0) === 0" class="px-2 py-1 text-xs text-text-dim">
          该提交没有文件变更
        </li>
      </ul>

      <div class="min-h-0 flex-1 overflow-auto bg-bg">
        <pre
          v-if="patchLines.length > 0"
          class="m-0 p-0 font-mono text-xxs leading-[18px]"
          data-testid="diff-patch"
        ><code>
<template v-for="line in patchLines" :key="line.id"><span :class="lineClass[line.kind]">{{ line.text }}
</span></template></code></pre>
        <p v-else-if="worktreeFile" class="p-3 text-xs text-text-dim">
          该文件当前没有可显示的差异（可能已被提交、回退或不再变更）
        </p>
        <p v-else class="p-3 text-xs text-text-dim">点击上方文件查看 patch 内容</p>
      </div>
    </div>
  </section>
</template>
