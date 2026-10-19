<script setup lang="ts">
import { computed } from 'vue';
import { FileCode2, X } from 'lucide-vue-next';
import { useFilesStore } from '@/stores/files';
import { matchLanguage, shouldHighlight } from '@/utils/codeLanguage';
import { formatBytes, splitPath } from '@/utils/projectTree';
import CodeViewer from './CodeViewer.vue';

/**
 * 只读文件内容查看（侧栏「文件」模式双击文件后占用右列）。
 *
 * <p>文本文件交给 {@link CodeViewer}（CodeMirror 6 只读模式）做语法高亮，语言识别与
 * 体积/行数上限见 `utils/codeLanguage.ts`；超上限或识别不出语言时退回纯文本 + 行号渲染，
 * 并在头部说明原因。二进制 / 后端标记的超大文件走各自降级提示。</p>
 */
const props = defineProps<{ repoId: string }>();

const files = useFilesStore();
const state = computed(() => files.content(props.repoId));
const path = computed(() => files.openedPath(props.repoId));
const data = computed(() => state.value.data);

const text = computed(() => data.value?.content ?? '');

/** 能拿到非空文本（非二进制、非超大）时才谈得上高亮 */
const hasText = computed(() => {
  const file = data.value;
  if (file === undefined || file === null || file.binary || file.tooLarge) {
    return false;
  }
  return file.content !== null && file.content !== '';
});

const language = computed(() => (hasText.value ? matchLanguage(path.value ?? '') : null));
const highlightable = computed(() => hasText.value && shouldHighlight(text.value));
/** 有语言可识别、但文件太大 → 头部提示「语法高亮已关闭」 */
const highlightOff = computed(() => language.value !== null && hasText.value && !highlightable.value);
const useCodeViewer = computed(() => language.value !== null && highlightable.value);

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
      <span v-if="language" class="shrink-0 text-text" data-testid="file-language">· {{ language.name }}</span>
      <span v-if="highlightOff" class="shrink-0 text-warn" data-testid="file-highlight-off">
        · 语法高亮已关闭（文件过大）
      </span>
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

    <div v-else-if="data" class="min-h-0 min-w-0 flex-1 overflow-hidden bg-bg" data-testid="file-content">
      <!-- 有语法可用且文件不大：交给 CodeMirror（行号、滚动、高亮都在里面） -->
      <CodeViewer v-if="useCodeViewer" :key="path ?? ''" :text="text" :language="language" />

      <!-- 降级：识别不出语言、文件过大、或内容是空串时，用等宽文本 + 行号渲染 -->
      <div v-else class="h-full overflow-auto">
        <div class="flex min-w-full font-mono text-xxs leading-[18px]">
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
    </div>

    <div v-else class="flex flex-1 items-center justify-center text-xs text-text-dim">
      在左侧文件树中双击文件查看内容
    </div>
  </section>
</template>
