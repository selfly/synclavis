<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NButton, NModal, NSpin, useMessage } from 'naive-ui';
import { Upload } from 'lucide-vue-next';
import { operationsApi, type PushBody, type PushPreview } from '@/api/operations';
import { useOperationsStore } from '@/stores/operations';

/**
 * 「推送预览」确认弹窗：推送前把这次会推上去的提交摆出来（需求 §Push）。
 *
 * - 无 upstream 且 `body.setUpstream` 时，在同一个弹窗里说明「将设置 upstream」，
 *   按钮改为「设置并推送」——不再单独弹一次「未设置上游分支」。
 * - `ahead = 0` 时提示「没有需要推送的提交」并禁用确认（推送只会得到 UP_TO_DATE）。
 * - `behind > 0` 说明本地落后远端，推送必被拒（非快进），这里只警告不拦截（最终判定在 push）。
 */
const props = defineProps<{
  show: boolean;
  repoId: string;
  /** 实际要执行的推送意图（`tags` 不进本弹窗，由 PushButton 直接推） */
  body: PushBody;
}>();

const emit = defineEmits<{ (e: 'update:show', value: boolean): void }>();

const operations = useOperationsStore();
const message = useMessage();

const loading = ref(false);
const submitting = ref(false);
const errorText = ref<string | null>(null);
const preview = ref<PushPreview | null>(null);

const target = computed(() => {
  const value = preview.value;
  return value ? `${value.remote}/${value.branch}` : '';
});
/** 只在「本次要设置 upstream」且「确实还没有 upstream」时提示 */
const willSetUpstream = computed(() => props.body.setUpstream === true && !preview.value?.upstream);
const hiddenCount = computed(() => {
  const value = preview.value;
  return value ? Math.max(value.ahead - value.commits.length, 0) : 0;
});
const canSubmit = computed(
  () => !loading.value && !submitting.value && !errorText.value && (preview.value?.ahead ?? 0) > 0,
);

watch(
  () => props.show,
  (visible) => {
    if (visible) {
      void load();
    }
  },
);

async function load(): Promise<void> {
  loading.value = true;
  errorText.value = null;
  preview.value = null;
  try {
    preview.value = await operationsApi.pushPreview(props.repoId, {
      remote: props.body.remote ?? undefined,
      branch: props.body.branch ?? undefined,
    });
  } catch (error) {
    errorText.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

async function submit(): Promise<void> {
  if (!operations.canRun(props.repoId, 'push')) {
    message.warning('该仓库正在执行其他操作');
    return;
  }
  submitting.value = true;
  try {
    const handle = await operationsApi.push(props.repoId, props.body);
    operations.begin(handle.operationId, props.repoId, 'push');
    operations.appendLog(props.repoId, {
      level: 'info',
      text: `开始 push ${preview.value ? `${target.value}（${preview.value.ahead} 个提交）` : ''}`.trim(),
    });
    emit('update:show', false);
  } catch (error) {
    errorText.value = error instanceof Error ? error.message : String(error);
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    title="推送预览"
    style="width: 560px"
    @update:show="emit('update:show', $event)"
  >
    <div class="flex flex-col gap-3" data-testid="push-preview-dialog">
      <div v-if="loading" class="px-1 py-3"><n-spin :size="16" /></div>

      <template v-else-if="preview">
        <p class="text-xs text-text-dim" data-testid="push-preview-summary">
          <template v-if="preview.ahead > 0">
            将推送 <span class="text-text">{{ preview.ahead }}</span> 个提交到
            <span class="text-text">{{ target }}</span>
          </template>
          <template v-else>目标 <span class="text-text">{{ target }}</span></template>
        </p>

        <p
          v-if="willSetUpstream"
          class="rounded bg-bg-hover px-2 py-1.5 text-xs text-text-dim"
          data-testid="push-preview-upstream"
        >
          当前分支还没有 upstream，将同时设置 upstream 到 {{ target }}
        </p>

        <ul
          v-if="preview.commits.length > 0"
          class="max-h-[280px] overflow-y-auto rounded border border-border"
        >
          <li
            v-for="commit in preview.commits"
            :key="commit.id"
            class="flex items-baseline gap-2 border-b border-border px-2 py-1.5 text-xs last:border-b-0"
            data-testid="push-preview-commit"
          >
            <code class="shrink-0 text-text-dim">{{ commit.shortId }}</code>
            <span class="min-w-0 flex-1 truncate" :title="commit.subject">{{ commit.subject }}</span>
            <span class="shrink-0 text-text-dim">{{ commit.author }}</span>
          </li>
        </ul>

        <p v-if="hiddenCount > 0" class="text-xxs text-text-dim">
          仅列出最近 {{ preview.commits.length }} 条，还有 {{ hiddenCount }} 条未列出
        </p>

        <p
          v-if="preview.ahead === 0"
          class="rounded bg-warn/10 px-2 py-1.5 text-xs text-warn"
          data-testid="push-preview-empty"
        >
          没有需要推送的提交（远端已是最新）
        </p>

        <p
          v-if="preview.behind > 0"
          class="rounded bg-danger/10 px-2 py-1.5 text-xs text-danger"
          data-testid="push-preview-behind"
        >
          远端还有 {{ preview.behind }} 个提交未合并到本地，直接推送会被拒绝（非快进），请先 Pull
        </p>
      </template>

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
          data-testid="push-preview-confirm"
          :loading="submitting"
          :disabled="!canSubmit"
          @click="submit"
        >
          <template #icon><Upload :size="13" /></template>
          {{ willSetUpstream ? '设置并推送' : '推送' }}
        </n-button>
      </div>
    </div>
  </n-modal>
</template>
