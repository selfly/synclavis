<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { NButton, NInput } from 'naive-ui';
import { Search } from 'lucide-vue-next';
import { useReposStore } from '@/stores/repos';
import { computeVirtualWindow } from '@/utils/virtual';

const props = defineProps<{ repoId: string; width: number }>();

/**
 * 提交行固定行高（像素）。
 *
 * 窗口化计算与行内 :style 必须共用同一个常量：一旦两处漂移，
 * 滚动位置与渲染出的行就会错位（见《详细设计文档》§9.5）。
 */
const ROW_H = 44;
const OVERSCAN = 6;

const repos = useReposStore();
const detail = computed(() => repos.detail(props.repoId));
const selected = computed(() => repos.selected(props.repoId));

const author = ref(repos.filter(props.repoId).author);
const keyword = ref(repos.filter(props.repoId).q);
const loadingMore = ref(false);

const scroller = ref<HTMLElement | null>(null);
const scrollTop = ref(0);
const viewportHeight = ref(0);
let resizeObserver: ResizeObserver | null = null;

/** 读取滚动容器的实测尺寸；面板被拖高/窗口缩放后都要重新同步 */
function syncViewport(): void {
  const el = scroller.value;
  if (el) {
    viewportHeight.value = el.clientHeight;
    scrollTop.value = el.scrollTop;
  }
}

onMounted(() => {
  syncViewport();
  if (typeof ResizeObserver !== 'undefined' && scroller.value) {
    resizeObserver = new ResizeObserver(syncViewport);
    resizeObserver.observe(scroller.value);
  }
  window.addEventListener('resize', syncViewport);
});

onBeforeUnmount(() => {
  if (resizeObserver) {
    resizeObserver.disconnect();
    resizeObserver = null;
  }
  window.removeEventListener('resize', syncViewport);
});

const virtualWindow = computed(() =>
  computeVirtualWindow({
    scrollTop: scrollTop.value,
    viewportHeight: viewportHeight.value,
    rowHeight: ROW_H,
    total: detail.value.commits.length,
    overscan: OVERSCAN,
  }),
);

const visibleCommits = computed(() =>
  detail.value.commits.slice(virtualWindow.value.start, virtualWindow.value.end),
);

function onScroll(): void {
  const el = scroller.value;
  if (el) {
    scrollTop.value = el.scrollTop;
  }
}

async function apply(): Promise<void> {
  await repos.applyFilter(props.repoId, { author: author.value, q: keyword.value });
  if (scroller.value) {
    scroller.value.scrollTop = 0;
  }
  syncViewport();
}

async function reset(): Promise<void> {
  author.value = '';
  keyword.value = '';
  await apply();
}

async function loadMore(): Promise<void> {
  loadingMore.value = true;
  try {
    await repos.loadLogMore(props.repoId);
    // 追加不重置滚动位置，只重新同步一次实测值
    syncViewport();
  } finally {
    loadingMore.value = false;
  }
}
</script>

<template>
  <section
    class="flex shrink-0 flex-col border-r border-border"
    data-testid="commit-column"
    :style="{ width: `${width}px` }"
  >
    <div class="flex shrink-0 items-center gap-1 border-b border-border px-2 py-1.5">
      <n-input v-model:value="keyword" size="tiny" placeholder="搜索提交信息" @keyup.enter="apply" />
      <n-input v-model:value="author" size="tiny" placeholder="作者" class="w-[90px]" @keyup.enter="apply" />
      <n-button size="tiny" @click="apply"><Search :size="12" /></n-button>
      <n-button size="tiny" quaternary @click="reset">重置</n-button>
    </div>

    <p class="shrink-0 border-b border-border px-2 py-1 text-xxs text-text-dim">
      共 {{ detail.total }} 个提交
      <span v-if="detail.commits.length < detail.total">（已加载 {{ detail.commits.length }}）</span>
    </p>

    <div
      ref="scroller"
      class="min-h-0 flex-1 overflow-y-auto"
      data-testid="commit-list"
      @scroll="onScroll"
    >
      <p v-if="detail.commits.length === 0" class="px-2 py-1 text-xs text-text-dim">暂无提交</p>

      <div v-else class="relative" :style="{ height: `${virtualWindow.totalHeight}px` }">
        <ul
          class="absolute inset-x-0 top-0"
          :style="{ transform: `translateY(${virtualWindow.offsetY}px)` }"
        >
          <li
            v-for="commit in visibleCommits"
            :key="commit.id"
            class="flex cursor-pointer flex-col justify-center overflow-hidden border-b border-border px-2 hover:bg-bg-hover"
            :class="commit.id === selected ? 'bg-bg-hover' : ''"
            data-testid="commit-row"
            :style="{ height: `${ROW_H}px` }"
            :title="`${commit.id} ${commit.subject}`"
            @click="repos.selectCommit(props.repoId, commit.id)"
          >
            <div class="flex items-center gap-1.5 overflow-hidden">
              <span class="shrink-0 font-mono text-xxs text-accent">{{ commit.shortId }}</span>
              <span class="truncate text-xs text-text">{{ commit.subject }}</span>
            </div>
            <div class="mt-0.5 flex items-center gap-1.5 overflow-hidden whitespace-nowrap text-xxs text-text-dim">
              <span class="shrink-0">{{ commit.author }}</span>
              <span class="shrink-0">{{ commit.time.slice(0, 16).replace('T', ' ') }}</span>
              <!-- 徽标单行不换行，过多时裁剪；title 兜底展示全部 refs，保证行高恒定 -->
              <span
                v-for="ref in commit.refs"
                :key="ref"
                class="shrink-0 rounded-sm bg-accent/15 px-1 text-xxs text-accent"
                :title="commit.refs.join(' ')"
              >
                {{ ref }}
              </span>
            </div>
          </li>
        </ul>
      </div>
    </div>

    <div
      v-if="detail.commits.length > 0 && detail.commits.length < detail.total"
      class="shrink-0 border-t border-border p-2"
    >
      <n-button size="tiny" block :loading="loadingMore" @click="loadMore">加载更多</n-button>
    </div>
  </section>
</template>
