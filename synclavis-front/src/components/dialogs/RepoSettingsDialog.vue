<script setup lang="ts">
import { ref, watch } from 'vue';
import { NButton, NInput, NModal, NSelect } from 'naive-ui';
import { useMessage } from 'naive-ui';
import { KeyRound, ShieldCheck, Trash2 } from 'lucide-vue-next';
import { reposApi, type UpdateRepoSettingsBody } from '@/api/repos';
import { useTabsStore } from '@/stores/tabs';
import type { PullStrategy } from '@/types/repo';
import PathPickerDialog from '@/components/dialogs/PathPickerDialog.vue';

const props = defineProps<{ show: boolean; repoId: string }>();
const emit = defineEmits<{ (e: 'update:show', value: boolean): void }>();

const tabs = useTabsStore();
const message = useMessage();

const sshKeyPath = ref('');
const httpsUsername = ref('');
const httpsPassword = ref('');
const pullStrategy = ref<PullStrategy>('merge');
const hasPassword = ref(false);
const saving = ref(false);
const loading = ref(false);
const errorText = ref<string | null>(null);
const showKeyPicker = ref(false);

const strategyOptions = [
  { label: 'Pull (Merge)', value: 'merge' },
  { label: 'Pull (Rebase)', value: 'rebase' },
  { label: 'Pull (Fast-forward only)', value: 'ff-only' },
];

watch(
  () => props.show,
  async (visible) => {
    if (!visible) {
      return;
    }
    loading.value = true;
    errorText.value = null;
    httpsPassword.value = '';
    try {
      const settings = await reposApi.getSettings(props.repoId);
      sshKeyPath.value = settings.sshKeyPath ?? '';
      httpsUsername.value = settings.httpsUsername ?? '';
      pullStrategy.value = settings.pullStrategy;
      hasPassword.value = settings.hasHttpsPassword;
    } catch (error) {
      errorText.value = error instanceof Error ? error.message : String(error);
    } finally {
      loading.value = false;
    }
  },
);

async function save(): Promise<void> {
  saving.value = true;
  errorText.value = null;
  try {
    const body: UpdateRepoSettingsBody = {
      sshKeyPath: sshKeyPath.value.trim() || null,
      httpsUsername: httpsUsername.value.trim() || null,
      pullStrategy: pullStrategy.value,
    };
    // 仅在填写了新口令时提交（空 = 不改动）
    if (httpsPassword.value.length > 0) {
      body.httpsPassword = httpsPassword.value;
    }
    const settings = await reposApi.updateSettings(props.repoId, body);
    tabs.patchTab(props.repoId, {
      sshKeyPath: settings.sshKeyPath,
      pullStrategy: settings.pullStrategy,
    });
    hasPassword.value = settings.hasHttpsPassword;
    httpsPassword.value = '';
    message.success('仓库设置已保存');
    emit('update:show', false);
  } catch (error) {
    errorText.value = error instanceof Error ? error.message : String(error);
  } finally {
    saving.value = false;
  }
}

async function clearPassword(): Promise<void> {
  saving.value = true;
  try {
    const settings = await reposApi.updateSettings(props.repoId, { httpsPassword: '' });
    hasPassword.value = settings.hasHttpsPassword;
    httpsPassword.value = '';
    message.success('已清除保存的 HTTPS 凭据');
  } catch (error) {
    errorText.value = error instanceof Error ? error.message : String(error);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    title="仓库设置"
    style="width: 580px"
    @update:show="emit('update:show', $event)"
  >
    <div class="flex flex-col gap-3">
      <div>
        <label class="mb-1 flex items-center gap-1 text-xs text-text-dim">
          <KeyRound :size="12" /> SSH 私钥路径
        </label>
        <div class="flex items-center gap-2">
          <n-input
            v-model:value="sshKeyPath"
            class="min-w-0 flex-1"
            placeholder="留空使用默认 ~/.ssh/id_rsa"
            :disabled="loading || saving"
          />
          <n-button
            size="small"
            data-testid="settings-key-browse"
            :disabled="loading || saving"
            @click="showKeyPicker = true"
          >
            浏览…
          </n-button>
        </div>
        <p class="mt-1 text-xxs text-text-dim">每个仓库可单独指定私钥；不支持带口令的私钥</p>
      </div>

      <div class="rounded border border-border p-2">
        <p class="mb-1 flex items-center gap-1 text-xxs text-text-dim">
          <ShieldCheck :size="12" /> HTTPS 凭据（用户名 + PAT/密码）
        </p>
        <div class="flex flex-col gap-2">
          <n-input
            v-model:value="httpsUsername"
            placeholder="用户名"
            :disabled="loading || saving"
          />
          <n-input
            v-model:value="httpsPassword"
            type="password"
            show-password-on="click"
            :placeholder="hasPassword ? '已保存口令（留空表示不改动）' : 'PAT / 密码'"
            :disabled="loading || saving"
          />
          <div class="flex items-center gap-2">
            <n-button
              size="tiny"
              quaternary
              type="error"
              :disabled="!hasPassword || saving"
              @click="clearPassword"
            >
              <template #icon><Trash2 :size="11" /></template>
              清除已保存口令
            </n-button>
            <span class="text-xxs text-text-dim">
              口令只写入本机状态文件（0600），不会在任何接口响应中回显
            </span>
          </div>
        </div>
      </div>

      <div>
        <label class="mb-1 block text-xs text-text-dim">默认 Pull 策略</label>
        <n-select
          v-model:value="pullStrategy"
          :options="strategyOptions"
          size="small"
          :disabled="loading || saving"
        />
      </div>

      <div v-if="errorText" class="rounded bg-danger/10 px-2 py-1.5 text-xs text-danger">
        {{ errorText }}
      </div>

      <div class="flex justify-end gap-2 pt-1">
        <n-button size="small" :disabled="saving" @click="emit('update:show', false)">取消</n-button>
        <n-button size="small" type="primary" :loading="saving" @click="save">保存</n-button>
      </div>
    </div>
  </n-modal>

  <PathPickerDialog
    v-model:show="showKeyPicker"
    kind="file"
    :initial-path="sshKeyPath"
    @select="sshKeyPath = $event"
  />
</template>
