<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NButton, NInput, NModal, NProgress } from 'naive-ui';
import { useMessage } from 'naive-ui';
import { Download, KeyRound, Link } from 'lucide-vue-next';
import { connectGlobalEvents, type WsConnection } from '@/api/ws';
import { reposApi } from '@/api/repos';
import { useTabsStore } from '@/stores/tabs';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ (e: 'update:show', value: boolean): void }>();

const tabs = useTabsStore();
const message = useMessage();

const url = ref('');
const path = ref('');
const sshKeyPath = ref('');
const httpsUsername = ref('');
const httpsPassword = ref('');

const submitting = ref(false);
const progressText = ref('');
const percent = ref(0);
const logLines = ref<string[]>([]);
const errorText = ref<string | null>(null);

let connection: WsConnection | null = null;
let activeOperationId: string | null = null;

const canSubmit = computed(() => url.value.trim().length > 0 && path.value.trim().length > 0);

function closeSocket(): void {
  connection?.close();
  connection = null;
}

watch(
  () => props.show,
  (visible) => {
    if (!visible) {
      closeSocket();
      return;
    }
    errorText.value = null;
    logLines.value = [];
    progressText.value = '';
    percent.value = 0;
  },
);

function handleEvent(event: unknown): void {
  const evt = event as { type: string; operationId?: string; payload?: Record<string, unknown> };
  if (!activeOperationId || evt.operationId !== activeOperationId) {
    return;
  }
  if (evt.type === 'progress' && evt.payload) {
    const completed = Number(evt.payload.completed ?? 0);
    const total = Number(evt.payload.total ?? 0);
    progressText.value = String(evt.payload.title ?? '');
    percent.value = total > 0 ? Math.min(100, Math.round((completed / total) * 100)) : 0;
  } else if (evt.type === 'log' && evt.payload) {
    logLines.value = [...logLines.value, String(evt.payload.text ?? '')].slice(-6);
  } else if (evt.type === 'error' && evt.payload) {
    errorText.value = `${String(evt.payload.code)}: ${String(evt.payload.message)}`;
  } else if (evt.type === 'done') {
    submitting.value = false;
    const ok = Boolean(evt.payload && evt.payload.ok);
    if (ok) {
      message.success('克隆完成');
      void tabs.hydrate();
      emit('update:show', false);
      url.value = '';
      path.value = '';
    } else {
      message.error(errorText.value ?? '克隆失败');
    }
    activeOperationId = null;
    closeSocket();
  }
}

async function submit(): Promise<void> {
  if (!canSubmit.value) {
    errorText.value = '请填写远程地址与目标路径';
    return;
  }
  submitting.value = true;
  errorText.value = null;
  logLines.value = [];
  percent.value = 0;

  try {
    const handle = await reposApi.clone({
      url: url.value.trim(),
      path: path.value.trim(),
      sshKeyPath: sshKeyPath.value.trim() || null,
      httpsUsername: httpsUsername.value.trim() || null,
      httpsPassword: httpsPassword.value.length > 0 ? httpsPassword.value : null,
    });
    activeOperationId = handle.operationId;
    connection = connectGlobalEvents(handleEvent);
  } catch (error) {
    submitting.value = false;
    errorText.value = error instanceof Error ? error.message : String(error);
  }
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    title="克隆远程仓库"
    style="width: 620px"
    @update:show="emit('update:show', $event)"
  >
    <div class="flex flex-col gap-3">
      <div>
        <label class="mb-1 flex items-center gap-1 text-xs text-text-dim">
          <Link :size="12" /> 远程地址
        </label>
        <n-input
          v-model:value="url"
          placeholder="git@github.com:org/repo.git 或 https://host/org/repo.git"
          :disabled="submitting"
        />
      </div>

      <div>
        <label class="mb-1 block text-xs text-text-dim">目标路径</label>
        <n-input v-model:value="path" placeholder="/Users/me/projects/repo" :disabled="submitting" />
      </div>

      <div class="rounded border border-border p-2">
        <p class="mb-1 text-xxs text-text-dim">认证（按地址协议自动选择）</p>
        <div class="flex flex-col gap-2">
          <div>
            <label class="mb-1 flex items-center gap-1 text-xs text-text-dim">
              <KeyRound :size="12" /> SSH 私钥路径（留空用默认 ~/.ssh/id_rsa）
            </label>
            <n-input
              v-model:value="sshKeyPath"
              placeholder="/Users/me/.ssh/id_ed25519_work"
              :disabled="submitting"
            />
          </div>
          <div class="flex gap-2">
            <n-input
              v-model:value="httpsUsername"
              placeholder="HTTPS 用户名"
              :disabled="submitting"
            />
            <n-input
              v-model:value="httpsPassword"
              type="password"
              show-password-on="click"
              placeholder="HTTPS PAT / 密码"
              :disabled="submitting"
            />
          </div>
        </div>
      </div>

      <div v-if="submitting || progressText" class="flex flex-col gap-1">
        <n-progress
          type="line"
          :percentage="percent"
          :height="6"
          :show-indicator="false"
          processing
        />
        <p class="text-xxs text-text-dim">{{ progressText || '正在克隆…' }} {{ percent }}%</p>
        <ul class="max-h-[90px] overflow-y-auto font-mono text-xxs text-text-dim">
          <li v-for="(line, index) in logLines" :key="index">{{ line }}</li>
        </ul>
      </div>

      <div v-if="errorText" class="rounded bg-danger/10 px-2 py-1.5 text-xs text-danger">
        {{ errorText }}
      </div>

      <div class="flex justify-end gap-2 pt-1">
        <n-button size="small" :disabled="submitting" @click="emit('update:show', false)">
          取消
        </n-button>
        <n-button size="small" type="primary" :loading="submitting" @click="submit">
          <template #icon><Download :size="13" /></template>
          克隆
        </n-button>
      </div>
    </div>
  </n-modal>
</template>
