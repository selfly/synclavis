<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NButton, NInput, NModal, NSelect, useMessage } from 'naive-ui';
import { GitBranchPlus } from 'lucide-vue-next';
import { gitApi } from '@/api/git';
import { useReposStore } from '@/stores/repos';
import { useTabsStore } from '@/stores/tabs';
import { extractConflictFiles, isValidBranchName } from '@/utils/branches';

/**
 * 「新建分支并切换」/「检出分支」对话框。
 *
 * 一份组件承载两种入口，靠 `mode` 区分文案与起点交互：
 * - `create`（默认，侧栏「本地分支」旁的 `+`）：起点默认当前分支，可选任意本地/远程分支或选中提交；
 * - `checkout`（侧栏双击远程分支）：起点由父级锁定为该远程分支、只读展示，本地名由父级预填。
 */
const props = defineProps<{
  show: boolean;
  repoId: string;
  /** `create`＝新建分支并切换（默认）；`checkout`＝把远程分支检出为同名本地跟踪分支 */
  mode?: 'create' | 'checkout';
  /** 起点：本地分支名 / 远程跟踪名（如 `origin/dev`）/ commit id；省略=当前 HEAD。`checkout` 下必填且锁定 */
  startPoint?: string;
  /** 预填的本地分支名（从远程分支检出时取去掉 remote 前缀的短名） */
  suggestedName?: string;
}>();

const emit = defineEmits<{ (e: 'update:show', value: boolean): void }>();

const repos = useReposStore();
const tabs = useTabsStore();
const message = useMessage();

const name = ref('');
const startPoint = ref<string | null>(null);
const submitting = ref(false);
const errorText = ref<string | null>(null);

/** 检出模式：起点锁定为所双击的远程分支，不渲染起点选择器 */
const isCheckout = computed(() => props.mode === 'checkout');
const title = computed(() => (isCheckout.value ? '检出分支' : '新建分支'));

const detail = computed(() => repos.detail(props.repoId));
/** 没有任何分支可作起点：仓库还没有提交（`git init` 后未 commit） */
const unborn = computed(() => detail.value.branches.length === 0);

const startOptions = computed(() => {
  const options: Array<{ label: string; value: string }> = [];
  const selected = repos.selected(props.repoId);
  if (selected) {
    options.push({ label: `提交 ${selected.slice(0, 8)}`, value: selected });
  }
  for (const branch of detail.value.branches) {
    const suffix = branch.local ? (branch.current ? '（当前分支）' : '') : '（远程）';
    options.push({ label: `${branch.name}${suffix}`, value: branch.name });
  }
  return options;
});

const trimmedName = computed(() => name.value.trim());
const nameValid = computed(() => isValidBranchName(trimmedName.value));
const existsLocal = computed(() =>
  detail.value.branches.some((branch) => branch.local && branch.name === trimmedName.value),
);

const canSubmit = computed(() => !submitting.value && !unborn.value && nameValid.value);

watch(
  () => props.show,
  (visible) => {
    if (!visible) {
      return;
    }
    errorText.value = null;
    submitting.value = false;
    name.value = props.suggestedName ?? '';
    // 检出模式起点锁定为父级给的远程分支；新建模式优先父级预填，其次当前分支（都无则交给后端默认 HEAD）
    startPoint.value = isCheckout.value
      ? (props.startPoint ?? null)
      : (props.startPoint ?? detail.value.status?.branch ?? null);
  },
);

async function submit(): Promise<void> {
  if (unborn.value) {
    errorText.value = '该仓库还没有任何提交，请先在提交区创建一次提交';
    return;
  }
  if (!nameValid.value) {
    errorText.value = '分支名不合法：不能以 `-` 开头，且不能含空白、`..`、`@{`、`~^:?*[\\` 等字符';
    return;
  }
  if (existsLocal.value) {
    errorText.value = '本地已存在同名分支，双击侧栏该分支即可切换';
    return;
  }

  submitting.value = true;
  errorText.value = null;
  try {
    const status = await gitApi.checkout(props.repoId, {
      branch: trimmedName.value,
      create: true,
      startPoint: startPoint.value ?? undefined,
    });
    tabs.upsertStatus(props.repoId, status);
    message.success(
      isCheckout.value
        ? `已检出 ${trimmedName.value}（跟踪 ${startPoint.value}）`
        : `已创建并切换到 ${trimmedName.value}`,
    );
    emit('update:show', false);
    await repos.refreshAll(props.repoId);
  } catch (error) {
    const files = extractConflictFiles(error);
    errorText.value =
      files.length > 0
        ? `本地改动会被覆盖，请先提交或 Stash：${files.join('、')}`
        : error instanceof Error
          ? error.message
          : String(error);
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="title"
    style="width: 520px"
    @update:show="emit('update:show', $event)"
  >
    <div class="flex flex-col gap-3" data-testid="new-branch-dialog">
      <div>
        <label class="mb-1 block text-xs text-text-dim">{{ isCheckout ? '本地分支名' : '分支名' }}</label>
        <n-input
          v-model:value="name"
          data-testid="new-branch-name"
          placeholder="feature/login"
          :disabled="submitting"
          @keyup.enter="submit"
        />
      </div>

      <div v-if="isCheckout">
        <label class="mb-1 block text-xs text-text-dim">来源</label>
        <p
          class="truncate rounded bg-bg-hover px-2 py-1 text-xs text-text-dim"
          data-testid="new-branch-source"
          :title="startPoint ?? ''"
        >
          {{ startPoint ?? '(未知远程分支)' }}
        </p>
      </div>
      <div v-else>
        <label class="mb-1 block text-xs text-text-dim">起点</label>
        <n-select
          v-model:value="startPoint"
          data-testid="new-branch-start"
          :options="startOptions"
          :disabled="submitting || unborn"
          placeholder="当前 HEAD"
          filterable
        />
      </div>

      <p v-if="unborn" class="rounded bg-warn/10 px-2 py-1.5 text-xs text-warn">
        该仓库还没有任何提交，先创建一次提交后才能{{ isCheckout ? '检出分支' : '新建分支' }}。
      </p>

      <div v-if="errorText" class="rounded bg-danger/10 px-2 py-1.5 text-xs text-danger">
        {{ errorText }}
      </div>

      <div class="flex justify-end gap-2 pt-1">
        <n-button size="small" :disabled="submitting" @click="emit('update:show', false)">
          取消
        </n-button>
        <n-button
          size="small"
          type="primary"
          data-testid="new-branch-confirm"
          :loading="submitting"
          :disabled="!canSubmit"
          @click="submit"
        >
          <template #icon><GitBranchPlus :size="13" /></template>
          {{ isCheckout ? '检出' : '创建并切换' }}
        </n-button>
      </div>
    </div>
  </n-modal>
</template>
