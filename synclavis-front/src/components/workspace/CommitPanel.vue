<script setup lang="ts">
import { computed, ref } from 'vue';
import { NButton, NInput } from 'naive-ui';
import { useMessage } from 'naive-ui';
import { Minus, Plus } from 'lucide-vue-next';
import { useReposStore } from '@/stores/repos';

const props = defineProps<{ repoId: string }>();

const repos = useReposStore();
const message = useMessage();

const text = ref('');
const submitting = ref(false);

const detail = computed(() => repos.detail(props.repoId));
const staged = computed(() => detail.value.status?.staged ?? []);
const unstaged = computed(() => detail.value.status?.unstaged ?? []);
const untracked = computed(() => detail.value.status?.untracked ?? []);

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
  <section class="flex h-[190px] shrink-0 flex-col border-t border-border bg-bg-elev">
    <div class="flex shrink-0 items-center gap-2 border-b border-border px-2 py-1 text-xxs text-text-dim">
      <span>已暂存 {{ staged.length }}</span>
      <span>· 未暂存 {{ unstaged.length + untracked.length }}</span>
      <span v-if="detail.status?.conflicts.length" class="text-warn">
        · 冲突 {{ detail.status.conflicts.length }}
      </span>
    </div>

    <div class="flex min-h-0 flex-1">
      <ul class="w-[260px] shrink-0 overflow-y-auto border-r border-border">
        <li
          v-for="file in staged"
          :key="`s-${file.path}`"
          class="group flex items-center gap-1 px-2 py-0.5 text-xs hover:bg-bg-hover"
        >
          <span class="truncate text-success">{{ file.path }}</span>
          <button
            type="button"
            class="ml-auto shrink-0 rounded p-0.5 opacity-0 group-hover:opacity-100 hover:bg-bg"
            title="取消暂存"
            @click="doUnstage([file.path])"
          >
            <Minus :size="11" />
          </button>
        </li>
        <li
          v-for="file in unstaged"
          :key="`u-${file.path}`"
          class="group flex items-center gap-1 px-2 py-0.5 text-xs hover:bg-bg-hover"
        >
          <span class="truncate text-text-dim">{{ file.path }}</span>
          <button
            type="button"
            class="ml-auto shrink-0 rounded p-0.5 opacity-0 group-hover:opacity-100 hover:bg-bg"
            title="暂存"
            @click="doStage([file.path])"
          >
            <Plus :size="11" />
          </button>
        </li>
        <li
          v-for="path in untracked"
          :key="`n-${path}`"
          class="group flex items-center gap-1 px-2 py-0.5 text-xs hover:bg-bg-hover"
        >
          <span class="truncate text-warn">{{ path }}</span>
          <button
            type="button"
            class="ml-auto shrink-0 rounded p-0.5 opacity-0 group-hover:opacity-100 hover:bg-bg"
            title="暂存"
            @click="doStage([path])"
          >
            <Plus :size="11" />
          </button>
        </li>
        <li
          v-if="staged.length + unstaged.length + untracked.length === 0"
          class="px-2 py-1 text-xs text-text-dim"
        >
          工作区干净
        </li>
      </ul>

      <div class="flex min-w-0 flex-1 flex-col gap-1 p-2">
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
