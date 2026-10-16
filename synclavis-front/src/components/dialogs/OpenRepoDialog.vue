<script setup lang="ts">
import { ref, watch } from 'vue';
import { NButton, NInput, NModal, NSpin } from 'naive-ui';
import { useMessage } from 'naive-ui';
import { Download, FolderOpen, History, KeyRound } from 'lucide-vue-next';
import { reposApi, type RecentRepoDto } from '@/api/repos';
import { useTabsStore } from '@/stores/tabs';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{
  (e: 'update:show', value: boolean): void;
  (e: 'clone'): void;
}>();

const tabs = useTabsStore();
const message = useMessage();

const path = ref('');
const sshKeyPath = ref('');
const recents = ref<RecentRepoDto[]>([]);
const loadingRecents = ref(false);
const submitting = ref(false);
const errorText = ref<string | null>(null);

watch(
  () => props.show,
  async (visible) => {
    if (!visible) {
      return;
    }
    errorText.value = null;
    loadingRecents.value = true;
    try {
      recents.value = await reposApi.recent(10);
    } catch {
      recents.value = [];
    } finally {
      loadingRecents.value = false;
    }
  },
);

function pickRecent(repo: RecentRepoDto): void {
  path.value = repo.path;
}

async function submit(): Promise<void> {
  const target = path.value.trim();
  if (!target) {
    errorText.value = '请输入仓库路径';
    return;
  }
  submitting.value = true;
  errorText.value = null;
  try {
    const key = sshKeyPath.value.trim();
    await tabs.openRepo(target, key.length > 0 ? key : null);
    message.success('仓库已打开');
    emit('update:show', false);
    path.value = '';
    sshKeyPath.value = '';
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
    title="打开仓库"
    style="width: 560px"
    @update:show="emit('update:show', $event)"
  >
    <div class="flex flex-col gap-3">
      <div>
        <label class="mb-1 block text-xs text-text-dim">本地路径</label>
        <n-input
          v-model:value="path"
          placeholder="/Users/me/projects/my-repo"
          :disabled="submitting"
          @keyup.enter="submit"
        />
      </div>

      <div>
        <label class="mb-1 flex items-center gap-1 text-xs text-text-dim">
          <KeyRound :size="12" />
          SSH 私钥路径（可选，留空使用默认 ~/.ssh/id_rsa）
        </label>
        <n-input
          v-model:value="sshKeyPath"
          placeholder="/Users/me/.ssh/id_ed25519_work"
          :disabled="submitting"
          @keyup.enter="submit"
        />
      </div>

      <div v-if="errorText" class="rounded bg-danger/10 px-2 py-1.5 text-xs text-danger">
        {{ errorText }}
      </div>

      <div>
        <div class="mb-1 flex items-center gap-1 text-xs text-text-dim">
          <History :size="12" />
          最近打开
        </div>
        <div v-if="loadingRecents" class="px-1 py-2"><n-spin :size="14" /></div>
        <div v-else-if="recents.length === 0" class="px-1 py-2 text-xs text-text-dim">
          暂无记录
        </div>
        <ul v-else class="max-h-[180px] overflow-y-auto rounded border border-border">
          <li
            v-for="repo in recents"
            :key="repo.path"
            class="flex cursor-pointer items-center gap-2 border-b border-border px-2 py-1.5 text-xs last:border-b-0 hover:bg-bg-hover"
            @click="pickRecent(repo)"
          >
            <FolderOpen :size="12" class="shrink-0 text-text-dim" />
            <span class="truncate">{{ repo.path }}</span>
          </li>
        </ul>
      </div>

      <div class="flex justify-between gap-2 pt-1">
        <n-button size="small" :disabled="submitting" @click="emit('clone')">
          <template #icon><Download :size="13" /></template>
          克隆远程仓库
        </n-button>
        <div class="flex gap-2">
          <n-button size="small" :disabled="submitting" @click="emit('update:show', false)">
            取消
          </n-button>
          <n-button size="small" type="primary" :loading="submitting" @click="submit">
            打开
          </n-button>
        </div>
      </div>
    </div>
  </n-modal>
</template>
