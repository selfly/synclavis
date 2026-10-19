<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NButton, NCheckbox, NInput, NModal, NSpin } from 'naive-ui';
import { ArrowUp, ChevronRight, File, Folder, HardDrive, Home } from 'lucide-vue-next';
import { fsApi, type FsEntryDto, type FsKind, type FsListDto } from '@/api/fs';
import {
  PICKER_PREFS_KEY,
  basenameOf,
  dirnameOf,
  readPickerPrefs,
  resolvePickerPath,
  visibleEntries,
  writePickerPrefs,
  type PickerPrefs,
} from '@/utils/pathPicker';

const props = withDefaults(
  defineProps<{
    show: boolean;
    kind?: FsKind;
    initialPath?: string | null;
  }>(),
  { kind: 'dir', initialPath: null },
);
const emit = defineEmits<{
  (e: 'update:show', value: boolean): void;
  (e: 'select', path: string): void;
}>();

const current = ref<FsListDto | null>(null);
const pathInput = ref('');
const selected = ref<FsEntryDto | null>(null);
const loading = ref(false);
const errorText = ref<string | null>(null);
const showHidden = ref(false);
const lastDir = ref<string | null>(null);
/** 并发导航时只接受最后一次请求的结果，避免慢响应覆盖新目录 */
let requestSeq = 0;

const kind = computed<FsKind>(() => props.kind);
const entries = computed(() => visibleEntries(current.value?.entries ?? [], showHidden.value));
const canConfirm = computed(() =>
  kind.value === 'file' ? selected.value !== null : current.value !== null,
);
const confirmLabel = computed(() => (kind.value === 'file' ? '选择文件' : '选择此目录'));
const title = computed(() => (kind.value === 'file' ? '选择文件' : '选择目录'));

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function loadPrefs(): PickerPrefs {
  try {
    return readPickerPrefs(window.localStorage.getItem(PICKER_PREFS_KEY));
  } catch {
    return { lastDir: null, showHidden: false };
  }
}

function persistPrefs(): void {
  try {
    window.localStorage.setItem(
      PICKER_PREFS_KEY,
      writePickerPrefs({ lastDir: lastDir.value, showHidden: showHidden.value }),
    );
  } catch {
    // 隐私模式下 localStorage 可能不可用，忽略即可
  }
}

function apply(data: FsListDto): void {
  current.value = data;
  pathInput.value = data.path;
  lastDir.value = data.path;
  selected.value = null;
  persistPrefs();
}

/** 严格进入某个目录；失败时保留当前列表并给出错误 */
async function loadDir(path: string | null): Promise<boolean> {
  const seq = ++requestSeq;
  loading.value = true;
  errorText.value = null;
  try {
    const data = await fsApi.list(path, kind.value);
    if (seq !== requestSeq) {
      return false;
    }
    apply(data);
    return true;
  } catch (error) {
    if (seq === requestSeq) {
      errorText.value = messageOf(error);
    }
    return false;
  } finally {
    if (seq === requestSeq) {
      loading.value = false;
    }
  }
}

/** 手动输入/初始路径：文件模式下允许落到父目录并预选该文件 */
async function openPath(path: string | null): Promise<void> {
  if (await loadDir(path)) {
    return;
  }
  if (kind.value !== 'file' || !path) {
    return;
  }
  const parent = dirnameOf(path);
  if (!parent || !(await loadDir(parent))) {
    return;
  }
  const name = basenameOf(path);
  selected.value =
    current.value?.entries.find((entry) => !entry.dir && entry.name === name) ?? null;
}

function onEntryClick(entry: FsEntryDto): void {
  if (entry.dir) {
    void loadDir(entry.path);
    return;
  }
  selected.value = entry;
}

function onEntryDblClick(entry: FsEntryDto): void {
  if (!entry.dir) {
    confirm();
  }
}

function confirm(): void {
  if (kind.value === 'file') {
    if (!selected.value) {
      return;
    }
    emit('select', selected.value.path);
  } else {
    if (!current.value) {
      return;
    }
    emit('select', current.value.path);
  }
  emit('update:show', false);
}

watch(
  () => props.show,
  async (visible) => {
    if (!visible) {
      return;
    }
    errorText.value = null;
    selected.value = null;
    const prefs = loadPrefs();
    showHidden.value = prefs.showHidden;
    await openPath(resolvePickerPath(props.initialPath, prefs));
  },
);

watch(showHidden, () => {
  persistPrefs();
});
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="title"
    style="width: 640px"
    @update:show="emit('update:show', $event)"
  >
    <div class="flex flex-col gap-2">
      <div class="flex items-center gap-2">
        <n-input
          v-model:value="pathInput"
          size="small"
          placeholder="输入绝对路径后回车"
          data-testid="path-picker-input"
          :disabled="loading"
          @keyup.enter="openPath(pathInput)"
        />
        <n-button
          size="small"
          title="上级目录"
          data-testid="path-picker-up"
          :disabled="loading || !current?.parent"
          @click="loadDir(current?.parent ?? null)"
        >
          <template #icon><ArrowUp :size="13" /></template>
        </n-button>
        <n-button size="small" title="用户主目录" :disabled="loading" @click="openPath(null)">
          <template #icon><Home :size="13" /></template>
        </n-button>
        <n-button
          v-for="root in current?.roots ?? []"
          :key="root"
          size="small"
          title="根目录"
          :disabled="loading"
          @click="loadDir(root)"
        >
          <template #icon><HardDrive :size="13" /></template>
          {{ root }}
        </n-button>
      </div>

      <div class="flex items-center gap-2">
        <div class="flex min-w-0 flex-1 flex-wrap items-center gap-0.5 text-xxs text-text-dim">
          <template v-for="(crumb, index) in current?.breadcrumbs ?? []" :key="crumb.path">
            <ChevronRight v-if="index > 0" :size="10" class="shrink-0" />
            <button
              type="button"
              class="min-w-0 truncate rounded px-1 hover:bg-bg-hover hover:text-text"
              :title="crumb.path"
              @click="loadDir(crumb.path)"
            >
              {{ crumb.name }}
            </button>
          </template>
        </div>
        <n-checkbox v-model:checked="showHidden" size="small" data-testid="path-picker-show-hidden">
          显示隐藏项
        </n-checkbox>
      </div>

      <div
        class="h-[320px] overflow-y-auto overflow-x-hidden rounded border border-border"
        data-testid="path-picker"
      >
        <div v-if="loading" class="flex h-full items-center justify-center">
          <n-spin :size="16" />
        </div>
        <div v-else-if="errorText" class="p-3 text-xs text-danger">{{ errorText }}</div>
        <div v-else-if="entries.length === 0" class="p-3 text-xs text-text-dim">空目录</div>
        <ul v-else class="py-1">
          <li v-for="entry in entries" :key="entry.path">
            <button
              type="button"
              class="flex w-full min-w-0 items-center gap-2 px-2 py-1.5 text-left text-xs hover:bg-bg-hover"
              :class="selected?.path === entry.path ? 'bg-accent/15' : ''"
              data-testid="path-picker-entry"
              :data-path="entry.path"
              :data-dir="entry.dir ? 'true' : 'false'"
              :data-selected="selected?.path === entry.path ? 'true' : undefined"
              @click="onEntryClick(entry)"
              @dblclick="onEntryDblClick(entry)"
            >
              <Folder v-if="entry.dir" :size="13" class="shrink-0 text-text-dim" />
              <File v-else :size="13" class="shrink-0 text-text-dim" />
              <span class="min-w-0 flex-1 truncate" :title="entry.name">{{ entry.name }}</span>
              <span
                v-if="entry.repo"
                class="shrink-0 rounded bg-accent/15 px-1 text-xxs text-accent"
              >
                仓库
              </span>
              <ChevronRight v-if="entry.dir" :size="12" class="shrink-0 text-text-dim" />
            </button>
          </li>
        </ul>
      </div>

      <p v-if="current?.truncated" class="text-xxs text-warn">
        目录项过多，仅显示前 2000 项
      </p>

      <div class="flex items-center justify-between gap-2 pt-1">
        <span class="min-w-0 truncate text-xxs text-text-dim" :title="selected?.path ?? current?.path ?? ''">
          {{
            kind === 'file'
              ? selected
                ? `已选：${selected.path}`
                : '请选择一个文件'
              : `当前：${current?.path ?? ''}`
          }}
        </span>
        <div class="flex shrink-0 gap-2">
          <n-button size="small" @click="emit('update:show', false)">取消</n-button>
          <n-button
            size="small"
            type="primary"
            data-testid="path-picker-choose"
            :disabled="!canConfirm"
            @click="confirm"
          >
            {{ confirmLabel }}
          </n-button>
        </div>
      </div>
    </div>
  </n-modal>
</template>
