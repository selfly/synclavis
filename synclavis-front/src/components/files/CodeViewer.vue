<script setup lang="ts">
import { Compartment, EditorState } from '@codemirror/state';
import { EditorView, lineNumbers } from '@codemirror/view';
import { syntaxHighlighting } from '@codemirror/language';
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { CODE_HIGHLIGHT_STYLE, type CodeLanguage } from '@/utils/codeLanguage';

/**
 * 只读 CodeMirror 6 宿主：负责把文本渲染成带语法高亮的代码块。
 *
 * <p>为什么是 CM6：以后要支持编辑时，只需在这里加 history/keymap/可编辑开关，
 * 语法、主题、行号、滚动都不用重写。</p>
 *
 * <p>只读的做法是 `editable=false` + `readOnly`：文本可以选中复制，但没有光标、
 * 不吃 IME，也不装 history/commands/autocomplete（省体积、少交互）。</p>
 *
 * <p>配色不在这里：类名由 `codeLanguage.ts` 的 HighlightStyle 固定输出，
 * 颜色写在 `styles/index.css` 的 `.hl-*` 上（因此切主题时编辑器无需重建）。</p>
 */
const props = defineProps<{ text: string; language: CodeLanguage | null }>();

const host = ref<HTMLElement | null>(null);
const view = shallowRef<EditorView | null>(null);

/** 语法包是异步 import 回来的：先挂空配置，到了再 reconfigure，避免整棵视图重建 */
const languageSlot = new Compartment();

/** 递增序号：语言加载完时若已换文件/已卸载，就丢弃这次结果 */
let generation = 0;

const theme = EditorView.theme({
  '&': {
    height: '100%',
    backgroundColor: 'transparent',
    color: 'rgb(var(--sl-text-rgb))',
  },
  '&.cm-focused': { outline: 'none' },
  '.cm-scroller': {
    overflow: 'auto',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace',
    fontSize: 'var(--sl-font-xs)',
    lineHeight: '18px',
  },
  '.cm-content': { padding: '0', caretColor: 'transparent' },
  '.cm-line': { padding: '0 8px' },
  '.cm-gutters': {
    backgroundColor: 'transparent',
    border: 'none',
    borderRight: '1px solid rgb(var(--sl-border-rgb))',
    color: 'rgb(var(--sl-text-dim-rgb))',
  },
  '.cm-lineNumbers .cm-gutterElement': { padding: '0 8px' },
  '.cm-content ::selection': { backgroundColor: 'rgb(var(--sl-accent-rgb) / 0.3)' },
});

function createState(text: string): EditorState {
  return EditorState.create({
    doc: text,
    extensions: [
      lineNumbers(),
      EditorState.readOnly.of(true),
      EditorView.editable.of(false),
      EditorState.tabSize.of(4),
      EditorView.contentAttributes.of({ role: 'textbox', 'aria-readonly': 'true', 'aria-label': '文件内容（只读）' }),
      syntaxHighlighting(CODE_HIGHLIGHT_STYLE),
      languageSlot.of([]),
      theme,
    ],
  });
}

/** 语法包可能加载失败（网络抖动/分包丢失）：失败了就当纯文本，不打扰用户 */
async function applyLanguage(): Promise<void> {
  const current = ++generation;
  const language = props.language;
  if (language === null) {
    return;
  }
  let support;
  try {
    support = await language.load();
  } catch (error) {
    console.warn(`[files] 加载 ${language.id} 语法包失败，改为纯文本展示`, error);
    return;
  }
  if (current !== generation || view.value === null) {
    return;
  }
  view.value.dispatch({ effects: languageSlot.reconfigure(support) });
}

onMounted(() => {
  view.value = new EditorView({ state: createState(props.text), parent: host.value as HTMLElement });
  void applyLanguage();
});

onBeforeUnmount(() => {
  generation += 1;
  view.value?.destroy();
  view.value = null;
});

// 同一个文件被重新打开（内容变了但路径没变）时重建状态；换文件由父组件 :key 重新挂载
watch(
  () => props.text,
  (text) => {
    view.value?.setState(createState(text));
    void applyLanguage();
  },
);

watch(() => props.language, () => void applyLanguage());
</script>

<template>
  <div ref="host" class="h-full min-h-0 w-full min-w-0" data-testid="file-code" />
</template>
