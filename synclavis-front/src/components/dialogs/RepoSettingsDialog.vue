<script setup lang="ts">
import { ref, watch } from 'vue';
import { NButton, NInput, NModal, NPopconfirm, NSelect } from 'naive-ui';
import { useMessage } from 'naive-ui';
import { GitBranch, KeyRound, Pencil, Plus, ShieldCheck, Trash2 } from 'lucide-vue-next';
import { reposApi, type UpdateRepoSettingsBody } from '@/api/repos';
import { remotesApi } from '@/api/remotes';
import { operationsApi } from '@/api/operations';
import { useOperationsStore } from '@/stores/operations';
import { useTabsStore } from '@/stores/tabs';
import type { PullStrategy, RemoteInfo } from '@/types/repo';
import { isValidRemoteName, isValidRemoteUrl } from '@/utils/remotes';
import PathPickerDialog from '@/components/dialogs/PathPickerDialog.vue';

const props = defineProps<{ show: boolean; repoId: string }>();
const emit = defineEmits<{ (e: 'update:show', value: boolean): void }>();

const tabs = useTabsStore();
const operations = useOperationsStore();
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

// 远程仓库（改动即时生效，不走「保存」按钮）
const remotes = ref<RemoteInfo[]>([]);
const remotesError = ref<string | null>(null);
const remoteBusy = ref(false);
const showAddForm = ref(false);
const newRemoteName = ref('');
const newRemoteUrl = ref('');
const editingName = ref<string | null>(null);
const editingUrl = ref('');

const strategyOptions = [
  { label: 'Pull (Merge)', value: 'merge' },
  { label: 'Pull (Rebase)', value: 'rebase' },
  { label: 'Pull (Fast-forward only)', value: 'ff-only' },
];

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

async function loadRemotes(): Promise<void> {
  remotesError.value = null;
  try {
    remotes.value = await remotesApi.list(props.repoId);
  } catch (error) {
    remotesError.value = messageOf(error);
  }
}

watch(
  () => props.show,
  async (visible) => {
    if (!visible) {
      showAddForm.value = false;
      editingName.value = null;
      remotesError.value = null;
      return;
    }
    loading.value = true;
    errorText.value = null;
    httpsPassword.value = '';
    showAddForm.value = false;
    editingName.value = null;
    try {
      const [settings] = await Promise.all([
        reposApi.getSettings(props.repoId),
        loadRemotes(),
      ]);
      sshKeyPath.value = settings.sshKeyPath ?? '';
      httpsUsername.value = settings.httpsUsername ?? '';
      pullStrategy.value = settings.pullStrategy;
      hasPassword.value = settings.hasHttpsPassword;
    } catch (error) {
      errorText.value = messageOf(error);
    } finally {
      loading.value = false;
    }
  },
);

function startAdd(): void {
  showAddForm.value = true;
  newRemoteName.value = remotes.value.length === 0 ? 'origin' : '';
  newRemoteUrl.value = '';
  remotesError.value = null;
}

function cancelAdd(): void {
  showAddForm.value = false;
  newRemoteName.value = '';
  newRemoteUrl.value = '';
}

/** 新增成功后自动 Fetch 一次，让远程分支立刻可见；失败只提示 */
async function fetchRemoteQuietly(name: string): Promise<void> {
  try {
    const handle = await operationsApi.fetch(props.repoId, { remote: name });
    operations.begin(handle.operationId, props.repoId, 'fetch');
    operations.appendLog(props.repoId, { level: 'info', text: `开始 fetch ${name}` });
  } catch (error) {
    message.warning(`远程已添加，但未能发起 Fetch：${messageOf(error)}`);
  }
}

async function submitAdd(): Promise<void> {
  const name = newRemoteName.value.trim();
  const url = newRemoteUrl.value.trim();
  if (!isValidRemoteName(name)) {
    remotesError.value = '远程名不合法：不能为空，且不能含空白、/ 等字符';
    return;
  }
  if (!isValidRemoteUrl(url)) {
    remotesError.value = 'url 不合法：形如 https://host/repo.git、git@host:path/repo.git 或本地绝对路径';
    return;
  }
  remoteBusy.value = true;
  remotesError.value = null;
  try {
    remotes.value = await remotesApi.add(props.repoId, name, url);
    cancelAdd();
    message.success(`已添加远程 ${name}`);
    void fetchRemoteQuietly(name);
  } catch (error) {
    remotesError.value = messageOf(error);
  } finally {
    remoteBusy.value = false;
  }
}

function startEdit(remote: RemoteInfo): void {
  editingName.value = remote.name;
  editingUrl.value = remote.url ?? '';
  remotesError.value = null;
}

async function submitEdit(): Promise<void> {
  const name = editingName.value;
  if (!name) {
    return;
  }
  const url = editingUrl.value.trim();
  if (!isValidRemoteUrl(url)) {
    remotesError.value = 'url 不合法：形如 https://host/repo.git、git@host:path/repo.git 或本地绝对路径';
    return;
  }
  remoteBusy.value = true;
  remotesError.value = null;
  try {
    remotes.value = await remotesApi.updateUrl(props.repoId, name, url);
    editingName.value = null;
    message.success(`已更新 ${name} 的 URL`);
  } catch (error) {
    remotesError.value = messageOf(error);
  } finally {
    remoteBusy.value = false;
  }
}

async function removeRemote(remote: RemoteInfo): Promise<void> {
  remoteBusy.value = true;
  remotesError.value = null;
  try {
    remotes.value = await remotesApi.remove(props.repoId, remote.name);
    if (editingName.value === remote.name) {
      editingName.value = null;
    }
    message.success(`已删除远程 ${remote.name}`);
  } catch (error) {
    remotesError.value = messageOf(error);
  } finally {
    remoteBusy.value = false;
  }
}

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
    errorText.value = messageOf(error);
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
    errorText.value = messageOf(error);
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
      <div class="rounded border border-border p-2">
        <div class="mb-1 flex items-center justify-between">
          <p class="flex items-center gap-1 text-xxs text-text-dim">
            <GitBranch :size="12" /> 远程仓库
            <span class="text-text-dim/70">（改动立即生效）</span>
          </p>
          <n-button
            size="tiny"
            quaternary
            data-testid="remote-add"
            :disabled="loading || remoteBusy || showAddForm"
            @click="startAdd"
          >
            <template #icon><Plus :size="11" /></template>
            添加远程
          </n-button>
        </div>

        <p v-if="loading" class="text-xxs text-text-dim">加载中…</p>
        <p v-else-if="remotes.length === 0 && !showAddForm" class="text-xxs text-text-dim">
          还没有远程仓库。本地仓库添加远程后即可 Fetch / Push。
        </p>

        <ul v-else-if="remotes.length > 0" class="flex flex-col gap-1">
          <li
            v-for="remote in remotes"
            :key="remote.name"
            class="min-w-0 rounded px-1 py-1 hover:bg-bg-hover"
            data-testid="remote-row"
            :data-name="remote.name"
          >
            <div v-if="editingName !== remote.name" class="flex min-w-0 items-center gap-2">
              <span class="shrink-0 text-xs text-text" data-testid="remote-name">
                {{ remote.name }}
              </span>
              <span
                class="min-w-0 flex-1 truncate text-xxs text-text-dim"
                :title="remote.pushUrl ? `${remote.url}\npush: ${remote.pushUrl}` : remote.url"
              >
                {{ remote.url }}
              </span>
              <n-button
                size="tiny"
                quaternary
                title="修改 URL"
                :disabled="remoteBusy"
                @click="startEdit(remote)"
              >
                <template #icon><Pencil :size="11" /></template>
              </n-button>
              <n-popconfirm @positive-click="removeRemote(remote)">
                <template #trigger>
                  <n-button size="tiny" quaternary type="error" title="删除" :disabled="remoteBusy">
                    <template #icon><Trash2 :size="11" /></template>
                  </n-button>
                </template>
                删除远程 {{ remote.name }}？（不会删除远端仓库本身）
              </n-popconfirm>
            </div>
            <div v-else class="flex items-center gap-2">
              <n-input
                v-model:value="editingUrl"
                size="small"
                class="min-w-0 flex-1"
                :disabled="remoteBusy"
                @keyup.enter="submitEdit"
              />
              <n-button size="tiny" type="primary" :loading="remoteBusy" @click="submitEdit">
                保存
              </n-button>
              <n-button size="tiny" :disabled="remoteBusy" @click="editingName = null">
                取消
              </n-button>
            </div>
          </li>
        </ul>

        <div v-if="showAddForm" class="mt-2 flex items-center gap-2">
          <!--
            宽度必须由外层 div 承担：n-input 根元素自带 width:100%。
            若把 w-[110px]/flex-1 直接写在 n-input 上，name 会顶着 shrink-0 独占整行，
            URL 输入框被挤成 0 宽而彻底不可见（见《详细设计文档》§10.8）。
          -->
          <div class="w-[110px] shrink-0">
            <n-input
              v-model:value="newRemoteName"
              size="small"
              placeholder="origin"
              data-testid="remote-new-name"
              :disabled="remoteBusy"
            />
          </div>
          <div class="min-w-0 flex-1">
            <n-input
              v-model:value="newRemoteUrl"
              size="small"
              placeholder="https://host/repo.git"
              data-testid="remote-new-url"
              :disabled="remoteBusy"
              @keyup.enter="submitAdd"
            />
          </div>
          <n-button
            size="small"
            type="primary"
            data-testid="remote-save"
            :loading="remoteBusy"
            @click="submitAdd"
          >
            添加
          </n-button>
          <n-button size="small" :disabled="remoteBusy" @click="cancelAdd">取消</n-button>
        </div>

        <p
          v-if="remotesError"
          class="mt-1 rounded bg-danger/10 px-2 py-1 text-xxs text-danger"
          data-testid="remote-error"
        >
          {{ remotesError }}
        </p>
      </div>

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
