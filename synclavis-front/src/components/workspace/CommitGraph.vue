<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { NButton, NInput } from 'naive-ui';
import { Search } from 'lucide-vue-next';
import { useReposStore } from '@/stores/repos';
import { computeGraph } from '@/utils/gitGraph';
import { computeVirtualWindow } from '@/utils/virtual';
import CommitRowGraph from './CommitRowGraph.vue';

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
/** 引用范围：`true` = 全部引用（缺省，对齐 SourceGit），`false` = 只看当前分支 */
const scopeAll = ref(repos.filter(props.repoId).all);
/** 提交顺序：`date` = 按时间（缺省，对齐 SourceGit），`topo` = 同分支扎堆 */
const order = ref(repos.filter(props.repoId).order);
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

/**
 * 泳道图布局：对**整个已加载集合**算一次，与窗口化共用同一个 `ROW_H`（§9.5.4）。
 *
 * 输入是完整集合而不是「当前可见行」——否则窗口一滚动列号就会重排。
 */
const graph = computed(() => computeGraph(detail.value.commits, { rowHeight: ROW_H }));

/** 与 `visibleCommits` 同一个窗口切片，逐行交给 `CommitRowGraph` */
const visibleRows = computed(() => graph.value.rows.slice(virtualWindow.value.start, virtualWindow.value.end));

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

/**
 * 切换引用范围（全部引用 / 当前分支）。
 *
 * <p>切换即重查第一页并回到顶部——两个范围下的提交集合不同，沿用旧滚动位置会指向
 * 完全不相干的行。范围只存内存（与 author/q 过滤一致），不落盘。</p>
 */
async function toggleScope(): Promise<void> {
  scopeAll.value = !scopeAll.value;
  await repos.applyFilter(props.repoId, { all: scopeAll.value });
  if (scroller.value) {
    scroller.value.scrollTop = 0;
  }
  syncViewport();
}

/**
 * 切换提交顺序（按时间 / 按拓扑）。
 *
 * <p>两种顺序下同一仓库的提交集合不变，但行的位置完全不同（`date` 会被其它分支穿插），
 * 因此同样要重查第一页并回到顶部。</p>
 */
async function toggleOrder(): Promise<void> {
  order.value = order.value === 'date' ? 'topo' : 'date';
  await repos.applyFilter(props.repoId, { order: order.value });
  if (scroller.value) {
    scroller.value.scrollTop = 0;
  }
  syncViewport();
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

    <div
      class="flex shrink-0 items-center gap-2 overflow-hidden border-b border-border px-2 py-1 text-xxs text-text-dim"
    >
      <span class="min-w-0 truncate">
        共 {{ detail.total }} 个提交
        <span v-if="detail.commits.length < detail.total">（已加载 {{ detail.commits.length }}）</span>
      </span>
      <!-- 顺序：默认按时间（对齐 SourceGit），可切「同分支扎堆」的拓扑序 -->
      <n-button
        class="ml-auto shrink-0"
        size="tiny"
        quaternary
        type="primary"
        data-testid="log-order-toggle"
        :data-order="order"
        :title="order === 'date' ? '当前：按提交时间（点击改为拓扑序）' : '当前：拓扑序（点击改为按提交时间）'"
        @click="toggleOrder"
      >
        {{ order === 'date' ? '按时间' : '按拓扑' }}
      </n-button>
      <!-- 引用范围：与 SourceGit 一样默认覆盖全部引用，避免「只被远程分支指向的提交看不到」 -->
      <n-button
        class="shrink-0"
        size="tiny"
        quaternary
        type="primary"
        data-testid="log-scope-toggle"
        :data-scope="scopeAll ? 'all' : 'branch'"
        :title="scopeAll ? '当前：全部引用（点击只看当前分支）' : '当前：当前分支（点击看全部引用）'"
        @click="toggleScope"
      >
        {{ scopeAll ? '全部引用' : '当前分支' }}
      </n-button>
    </div>

    <div
      ref="scroller"
      class="min-h-0 flex-1 overflow-y-auto"
      data-testid="commit-list"
      :data-lane-count="graph.laneCount"
      @scroll="onScroll"
    >
      <p v-if="detail.commits.length === 0" class="px-2 py-1 text-xs text-text-dim">暂无提交</p>

      <div v-else class="relative" :style="{ height: `${virtualWindow.totalHeight}px` }">
        <ul
          class="absolute inset-x-0 top-0"
          :style="{ transform: `translateY(${virtualWindow.offsetY}px)` }"
        >
          <li
            v-for="(commit, index) in visibleCommits"
            :key="commit.id"
            class="flex cursor-pointer items-stretch overflow-hidden border-b border-border hover:bg-bg-hover"
            :class="commit.id === selected ? 'bg-bg-hover' : ''"
            data-testid="commit-row"
            :data-parents="commit.parents.length"
            :style="{ height: `${ROW_H}px` }"
            :title="`${commit.id} ${commit.subject}`"
            @click="repos.selectCommit(props.repoId, commit.id)"
          >
            <!-- 左侧泳道图：只画本行 segment（固定 44px，与 ROW_H 同源） -->
            <CommitRowGraph :row="visibleRows[index]" :lane-count="graph.laneCount" :height="ROW_H" />
            <div class="flex min-w-0 flex-1 flex-col justify-center pr-2">
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
