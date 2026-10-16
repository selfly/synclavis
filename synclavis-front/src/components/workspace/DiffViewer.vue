<script setup lang="ts">
import { computed } from 'vue';
import { FileDiff } from 'lucide-vue-next';
import { useReposStore } from '@/stores/repos';

const props = defineProps<{ repoId: string }>();

const repos = useReposStore();
const detail = computed(() => repos.detail(props.repoId));
const diff = computed(() => detail.value.diff);
const selected = computed(() => repos.selected(props.repoId));

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
  <section class="flex min-h-0 flex-1 flex-col">
    <div class="flex shrink-0 items-center gap-2 border-b border-border px-2 py-1.5 text-xxs text-text-dim">
      <FileDiff :size="12" />
      <span v-if="selected">提交 {{ selected.slice(0, 8) }}</span>
      <span v-else>未选择提交</span>
      <span v-if="diff">· {{ diff.entries.length }} 个文件变更</span>
    </div>

    <div v-if="!selected" class="flex flex-1 items-center justify-center text-xs text-text-dim">
      在上方提交列表中选择一个提交查看 Diff
    </div>

    <div v-else class="flex min-h-0 flex-1 flex-col">
      <ul class="max-h-[140px] shrink-0 overflow-y-auto border-b border-border">
        <li
          v-for="entry in diff?.entries ?? []"
          :key="entry.path"
          class="flex cursor-pointer items-center gap-2 px-2 py-1 text-xs hover:bg-bg-hover"
          @click="repos.loadDiff(props.repoId, selected, entry.path)"
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
        <pre v-if="patchLines.length > 0" class="m-0 p-0 font-mono text-xxs leading-[18px]"><code>
<template v-for="line in patchLines" :key="line.id"><span :class="lineClass[line.kind]">{{ line.text }}
</span></template></code></pre>
        <p v-else class="p-3 text-xs text-text-dim">点击上方文件查看 patch 内容</p>
      </div>
    </div>
  </section>
</template>
