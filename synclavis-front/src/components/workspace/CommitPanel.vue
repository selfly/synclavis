<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NButton, NInput, useMessage } from 'naive-ui';
import { FolderTree, List } from 'lucide-vue-next';
import ResizeHandle from '@/components/common/ResizeHandle.vue';
import WorktreeFileList from './WorktreeFileList.vue';
import { useReposStore } from '@/stores/repos';
import {
  readFileView,
  writeFileView,
  type FileViewMode,
  type WorktreeFileItem,
} from '@/utils/fileTree';
import { LAYOUT_BOUNDS } from '@/utils/layout';

const props = defineProps<{
  repoId: string;
  height: number;
  /** 「已暂存/未暂存」文件列表宽度 */
  fileListWidth: number;
  /** 动态上限：由 RepoWorkspace 按右列实测宽度算好，保证提交信息框不被压没 */
  fileListMax: number;
}>();

const emit = defineEmits<{
  (e: 'update:fileListWidth', value: number): void;
  (e: 'commit-file-list-width', value: number): void;
}>();

const repos = useReposStore();
const message = useMessage();

const text = ref('');
const submitting = ref(false);

type TabId = 'changes' | 'untracked';

const view = ref<FileViewMode>(readFileView());
const tab = ref<TabId>('changes');
/** 用户是否手动选过 tab：选过之后不再自动跳到「第一个非空」 */
const tabChosen = ref(false);

const detail = computed(() => repos.detail(props.repoId));
const selectedFile = computed(() => repos.worktreeFile(props.repoId));

const staged = computed<WorktreeFileItem[]>(() =>
  (detail.value.status?.staged ?? []).map((file) => ({ path: file.path, changeType: file.changeType })),
);
const unstaged = computed<WorktreeFileItem[]>(() =>
  (detail.value.status?.unstaged ?? []).map((file) => ({ path: file.path, changeType: file.changeType })),
);
const untracked = computed<WorktreeFileItem[]>(() =>
  (detail.value.status?.untracked ?? []).map((path) => ({ path, changeType: 'ADD' })),
);
const conflicts = computed(() => detail.value.status?.conflicts ?? []);
const changesCount = computed(() => staged.value.length + unstaged.value.length);

/**
 * 默认 tab：变更优先；变更两区都空、未跟踪非空才默认到「未跟踪」。
 *
 * <p>只在状态首次到达时决定一次，之后不再自动切换——否则用户刚在「未跟踪」暂存一个文件、
 * 变更变非空时，视图会被强行拽回「变更」。</p>
 */
watch(
  () => detail.value.status,
  (status) => {
    if (tabChosen.value || !status) {
      return;
    }
    const changes = status.staged.length + status.unstaged.length;
    tab.value = changes === 0 && status.untracked.length > 0 ? 'untracked' : 'changes';
    tabChosen.value = true;
  },
  { immediate: true },
);

function pickTab(next: TabId): void {
  tab.value = next;
  tabChosen.value = true;
}

function toggleView(): void {
  view.value = view.value === 'tree' ? 'list' : 'tree';
  writeFileView(view.value);
}

/** 点击文件 → 在右侧查看它改了哪些内容（见《详细设计文档》§6.2） */
async function inspect(path: string, stagedFile: boolean): Promise<void> {
  try {
    await repos.selectWorktreeFile(props.repoId, path, stagedFile);
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  }
}

async function doStage(paths: string[]): Promise<void> {
  try {
    await repos.stage(props.repoId, paths);
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  }
}

async function doUnstage(paths: string[]): Promise<void> {
  try {
    await repos.unstage(props.repoId, paths);
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  }
}

async function submit(amend: boolean): Promise<void> {
  submitting.value = true;
  try {
    await repos.commit(props.repoId, text.value, amend);
    text.value = '';
    message.success(amend ? '已 Amend' : '已提交');
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <section
    class="flex shrink-0 flex-col border-t border-border bg-bg-elev"
    data-testid="commit-panel"
    :style="{ height: `${height}px` }"
  >
    <div
      v-if="conflicts.length"
      class="flex shrink-0 items-center gap-2 border-b border-border px-2 py-1 text-xxs text-warn"
    >
      <span>冲突 {{ conflicts.length }}</span>
    </div>

    <div class="flex min-h-0 flex-1">
      <!-- 宽度由 layout.commitFileListWidth 驱动，右边界可拖拽 -->
      <div
        class="flex shrink-0 flex-col overflow-hidden"
        data-testid="commit-file-list"
        :style="{ width: `${fileListWidth}px` }"
      >
        <!-- 分区 tab：已暂存/未暂存同处「变更」，未跟踪单独一栏 -->
        <div class="flex shrink-0 items-center border-b border-border px-1" role="tablist">
          <button
            type="button"
            role="tab"
            class="-mb-px border-b px-2 py-1 text-xxs"
            :class="
              tab === 'changes'
                ? 'border-accent text-text'
                : 'border-transparent text-text-dim hover:text-text'
            "
            :aria-selected="tab === 'changes'"
            data-testid="commit-tab-changes"
            @click="pickTab('changes')"
          >
            变更 {{ changesCount }}
          </button>
          <button
            type="button"
            role="tab"
            class="-mb-px border-b px-2 py-1 text-xxs"
            :class="
              tab === 'untracked'
                ? 'border-accent text-text'
                : 'border-transparent text-text-dim hover:text-text'
            "
            :aria-selected="tab === 'untracked'"
            data-testid="commit-tab-untracked"
            @click="pickTab('untracked')"
          >
            未跟踪 {{ untracked.length }}
          </button>
          <button
            type="button"
            class="ml-auto mr-1 shrink-0 rounded p-0.5 text-text-dim hover:bg-bg-hover hover:text-text"
            :title="view === 'tree' ? '切换为列表' : '切换为树形'"
            data-testid="commit-file-view-toggle"
            @click="toggleView"
          >
            <List v-if="view === 'tree'" :size="12" />
            <FolderTree v-else :size="12" />
          </button>
        </div>

        <div class="flex min-h-0 flex-1 flex-col overflow-hidden">
          <template v-if="tab === 'changes'">
            <template v-if="staged.length">
              <div
                class="flex shrink-0 items-center gap-2 border-b border-border px-2 py-0.5 text-xxs text-text-dim"
              >
                <span>已暂存 · {{ staged.length }}</span>
                <button
                  type="button"
                  class="ml-auto rounded px-1 hover:bg-bg-hover hover:text-text"
                  title="全部取消暂存"
                  data-testid="commit-block-unstage-all"
                  @click="doUnstage(staged.map((file) => file.path))"
                >
                  全部取消暂存
                </button>
              </div>
              <WorktreeFileList
                section="staged"
                :files="staged"
                :view="view"
                :selected="selectedFile"
                @inspect="inspect"
                @unstage="doUnstage"
              />
            </template>

            <template v-if="unstaged.length">
              <div
                class="flex shrink-0 items-center gap-2 border-b border-border px-2 py-0.5 text-xxs text-text-dim"
              >
                <span>未暂存 · {{ unstaged.length }}</span>
                <button
                  type="button"
                  class="ml-auto rounded px-1 hover:bg-bg-hover hover:text-text"
                  title="全部暂存"
                  data-testid="commit-block-stage-all"
                  @click="doStage(unstaged.map((file) => file.path))"
                >
                  全部暂存
                </button>
              </div>
              <WorktreeFileList
                section="unstaged"
                :files="unstaged"
                :view="view"
                :selected="selectedFile"
                @inspect="inspect"
                @stage="doStage"
              />
            </template>

            <div
              v-if="changesCount === 0"
              class="px-2 py-1 text-xs text-text-dim"
              data-testid="commit-changes-empty"
            >
              {{ untracked.length ? '无变更（未跟踪文件见「未跟踪」）' : '工作区干净' }}
            </div>
          </template>

          <template v-else>
            <div
              class="flex shrink-0 items-center gap-2 border-b border-border px-2 py-0.5 text-xxs text-text-dim"
            >
              <span>未跟踪 · {{ untracked.length }}</span>
              <button
                type="button"
                class="ml-auto rounded px-1 hover:bg-bg-hover hover:text-text"
                title="全部暂存"
                data-testid="commit-untracked-stage-all"
                @click="doStage(untracked.map((file) => file.path))"
              >
                全部暂存
              </button>
            </div>
            <WorktreeFileList
              section="untracked"
              :files="untracked"
              :view="view"
              :selected="selectedFile"
              @inspect="inspect"
              @stage="doStage"
            />
          </template>
        </div>
      </div>

      <ResizeHandle
        axis="x"
        :size="fileListWidth"
        :min="LAYOUT_BOUNDS.commitFileListWidth.min"
        :max="fileListMax"
        label="调整暂存区文件列表宽度"
        @update:size="emit('update:fileListWidth', $event)"
        @change="emit('commit-file-list-width', $event)"
      />

      <div class="flex min-w-0 flex-1 flex-col gap-1 p-2" data-testid="commit-message-area">
        <n-input
          v-model:value="text"
          type="textarea"
          size="small"
          placeholder="提交信息（Ctrl+Enter 提交）"
          :autosize="{ minRows: 2, maxRows: 4 }"
          @keydown.ctrl.enter="submit(false)"
        />
        <div class="flex justify-end gap-2">
          <n-button size="tiny" :disabled="submitting" @click="submit(true)">Amend</n-button>
          <n-button
            size="tiny"
            type="primary"
            :loading="submitting"
            :disabled="staged.length === 0"
            @click="submit(false)"
          >
            Commit
          </n-button>
        </div>
      </div>
    </div>
  </section>
</template>
