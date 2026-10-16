<script setup lang="ts">
import { computed, ref } from 'vue';
import { NButton, NInput } from 'naive-ui';
import { Search } from 'lucide-vue-next';
import { useReposStore } from '@/stores/repos';

const props = defineProps<{ repoId: string }>();

const repos = useReposStore();
const detail = computed(() => repos.detail(props.repoId));
const selected = computed(() => repos.selected(props.repoId));

const author = ref(repos.filter(props.repoId).author);
const keyword = ref(repos.filter(props.repoId).q);
const loadingMore = ref(false);

async function apply(): Promise<void> {
  await repos.applyFilter(props.repoId, { author: author.value, q: keyword.value });
}

async function reset(): Promise<void> {
  author.value = '';
  keyword.value = '';
  await repos.applyFilter(props.repoId, { author: '', q: '' });
}

async function loadMore(): Promise<void> {
  loadingMore.value = true;
  try {
    await repos.loadLogMore(props.repoId);
  } finally {
    loadingMore.value = false;
  }
}
</script>

<template>
  <section class="flex w-[380px] shrink-0 flex-col border-r border-border">
    <div class="flex shrink-0 items-center gap-1 border-b border-border px-2 py-1.5">
      <n-input v-model:value="keyword" size="tiny" placeholder="搜索提交信息" @keyup.enter="apply" />
      <n-input v-model:value="author" size="tiny" placeholder="作者" class="w-[90px]" @keyup.enter="apply" />
      <n-button size="tiny" @click="apply"><Search :size="12" /></n-button>
      <n-button size="tiny" quaternary @click="reset">重置</n-button>
    </div>

    <div class="min-h-0 flex-1 overflow-y-auto">
      <p class="px-2 py-1 text-xxs text-text-dim">共 {{ detail.total }} 个提交</p>
      <ul>
        <li
          v-for="commit in detail.commits"
          :key="commit.id"
          class="cursor-pointer border-b border-border px-2 py-1.5 hover:bg-bg-hover"
          :class="commit.id === selected ? 'bg-bg-hover' : ''"
          @click="repos.selectCommit(props.repoId, commit.id)"
        >
          <div class="flex items-center gap-1.5">
            <span class="shrink-0 font-mono text-xxs text-accent">{{ commit.shortId }}</span>
            <span class="truncate text-xs text-text">{{ commit.subject }}</span>
          </div>
          <div class="mt-0.5 flex items-center gap-1.5 text-xxs text-text-dim">
            <span class="truncate">{{ commit.author }}</span>
            <span>{{ commit.time.slice(0, 16).replace('T', ' ') }}</span>
          </div>
          <div v-if="commit.refs.length > 0" class="mt-0.5 flex flex-wrap gap-1">
            <span
              v-for="ref in commit.refs"
              :key="ref"
              class="rounded-sm bg-accent/15 px-1 text-xxs text-accent"
            >
              {{ ref }}
            </span>
          </div>
        </li>
      </ul>
      <div v-if="detail.commits.length < detail.total" class="p-2">
        <n-button size="tiny" block :loading="loadingMore" @click="loadMore">加载更多</n-button>
      </div>
    </div>
  </section>
</template>
