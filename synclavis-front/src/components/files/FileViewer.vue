<script setup lang="ts">
import { computed } from 'vue';
import { FileCode2, X } from 'lucide-vue-next';
import { useFilesStore } from '@/stores/files';
import { formatBytes, splitPath } from '@/utils/projectTree';

/**
 * 只读文件内容查看（侧栏「文件」模式双击文件后占用右列）。
 *
 * <p>不做语法高亮：项目约束不引入新依赖，这里用等宽字体 + 行号保证可读性。
 * 二进制 / 超大文件由后端标记，这里给降级提示。</p>
 */
const props = defineProps<{ repoId: string }>();

const files = useFilesStore();
const state = computed(() => files.content(props.repoId));
const path = computed(() => files.openedPath(props.repoId));
const data = computed(() => state.value.data);

/** 行号与内容一次算好，避免模板里重复 split */
const lines = computed(() => {
  const text = data.value?.content;
  if (text === null || text === undefined) {
    return [];
  }
  // 末尾换行不额外产生一个空行号，与多数编辑器一致
  const parts = text.split('\n');
  if (parts.length > 1 && parts[parts.length - 1] === '') {
    parts.pop();
  }
  return parts;
});

const fileName = computed(() => splitPath(path.value ?? '').name);
const dirName = computed(() => splitPath(path.value ?? '').dir);

function close(): void {
  files.closeFile(props.repoId);
}
</script>

<template>
  <section class="flex min-h-0 min-w-0 flex-1 flex-col" data-testid="file-viewer">
    <div
      class="flex shrink-0 items-center gap-2 overflow-hidden border-b border-border px-2 py-1.5 text-xxs text-text-dim"
      data-testid="file-viewer-header"
    >
      <FileCode2 :size="12" class="shrink-0" />
      <span class="shrink-0 rounded-sm bg-accent/15 px-1 text-accent">只读</span>
      <span v-if="dirName" class="shrink-0 truncate" :title="dirName">{{ dirName }}/</span>
      <span class="min-w-0 truncate text-text" :title="path ?? ''">{{ fileName }}</span>
      <span v-if="data && !data.binary && !data.tooLarge" class="shrink-0">
        · {{ formatBytes(data.size) }}
      </span>
      <span v-if="data?.encoding" class="shrink-0">· {{ data.encoding }}</span>
      <button
        type="button"
        class="ml-auto shrink-0 rounded p-0.5 text-text-dim hover:bg-bg-hover hover:text-text"
        title="关闭文件查看"
        data-testid="file-viewer-close"
        @click="close"
      >
        <X :size="12" />
      </button>
    </div>

    <div v-if="state.loading" class="flex flex-1 items-center justify-center text-xs text-text-dim">
      正在读取…
    </div>

    <div v-else-if="state.error" class="flex flex-1 items-center justify-center px-4 text-center text-xs text-danger">
      {{ state.error }}
    </div>

    <div v-else-if="data?.tooLarge" class="flex flex-1 items-center justify-center px-4 text-center text-xs text-text-dim">
      文件过大（{{ formatBytes(data.size) }}），不在浏览器中展示
    </div>

    <div v-else-if="data?.binary" class="flex flex-1 items-center justify-center px-4 text-center text-xs text-text-dim">
      二进制文件（{{ formatBytes(data.size) }}），无法以文本方式展示
    </div>

    <div v-else-if="data" class="min-h-0 min-w-0 flex-1 overflow-auto bg-bg">
      <div class="flex min-w-full font-mono text-xxs leading-[18px]" data-testid="file-content">
        <!-- 行号列固定宽度，内容列可横向滚动 -->
        <div class="shrink-0 select-none border-r border-border px-2 text-right text-text-dim">
          <div v-for="(_, index) in lines" :key="`ln-${index}`" class="whitespace-pre">{{ index + 1 }}</div>
        </div>
        <div class="min-w-0 flex-1 px-2">
          <div v-for="(line, index) in lines" :key="`lc-${index}`" class="whitespace-pre text-text">{{ line }}</div>
        </div>
      </div>
      <p v-if="lines.length === 0" class="p-3 text-xs text-text-dim">空文件</p>
    </div>

    <div v-else class="flex flex-1 items-center justify-center text-xs text-text-dim">
      在左侧文件树中双击文件查看内容
    </div>
  </section>
</template>
