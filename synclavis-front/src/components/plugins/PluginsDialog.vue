<script setup lang="ts">
import { ref, watch } from 'vue';
import { NButton, NModal, NSwitch, NTag, useMessage } from 'naive-ui';
import { Puzzle, RefreshCw, ShieldAlert } from 'lucide-vue-next';
import { usePluginsStore } from '@/stores/plugins';
import type { PluginInfo } from '@/plugins/types';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ (e: 'update:show', value: boolean): void }>();

const plugins = usePluginsStore();
const message = useMessage();
const busyId = ref<string | null>(null);
const devBusy = ref(false);
const reloading = ref(false);

watch(
  () => props.show,
  (visible) => {
    if (visible && !plugins.loaded) {
      void plugins.load();
    }
  },
);

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

async function togglePlugin(item: PluginInfo, enabled: boolean): Promise<void> {
  busyId.value = item.id;
  try {
    await plugins.setEnabled(item.id, enabled);
    message.success(enabled ? `已启用 ${item.name}` : `已停用 ${item.name}`);
  } catch (error) {
    message.error(messageOf(error));
  } finally {
    busyId.value = null;
  }
}

async function toggleDeveloperMode(enabled: boolean): Promise<void> {
  devBusy.value = true;
  try {
    await plugins.setDeveloperMode(enabled);
    message.success(enabled ? '已开启开发者模式' : '已关闭开发者模式');
  } catch (error) {
    message.error(messageOf(error));
  } finally {
    devBusy.value = false;
  }
}

async function reloadPlugins(): Promise<void> {
  reloading.value = true;
  try {
    await plugins.reload();
    message.success(`已重新扫描，共 ${plugins.items.length} 个插件`);
  } catch (error) {
    message.error(messageOf(error));
  } finally {
    reloading.value = false;
  }
}

function openRightDock(item: PluginInfo): void {
  const view = plugins
    .viewsFor('rightDock')
    .find((candidate) => candidate.pluginId === item.id);
  if (!view) {
    return;
  }
  plugins.setActiveView('rightDock', view.key);
  plugins.setRightDockOpen(true);
  emit('update:show', false);
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    title="插件"
    style="width: 680px"
    @update:show="emit('update:show', $event)"
  >
    <div class="flex max-h-[70vh] flex-col gap-3 overflow-auto pr-1">
      <div class="flex items-start justify-between gap-3">
        <div class="min-w-0">
          <p class="flex items-center gap-1 text-xs text-text">
            <Puzzle :size="13" /> 本地插件
          </p>
          <p class="mt-1 break-all text-xxs text-text-dim">
            插件目录：<code>{{ plugins.pluginsDir || '加载中…' }}</code>
          </p>
          <p class="mt-1 text-xxs text-text-dim">
            每个插件一个子目录，包含 manifest.json 与 dist/；本地未签名插件默认禁用。
          </p>
        </div>
        <n-button size="small" :loading="reloading" @click="reloadPlugins">
          <template #icon><RefreshCw :size="12" /></template>
          重新扫描
        </n-button>
      </div>

      <div class="flex items-center justify-between gap-3 rounded border border-border px-2 py-2">
        <div class="flex items-center gap-2">
          <ShieldAlert :size="14" class="text-warn" />
          <div>
            <p class="text-xs text-text">开发者模式</p>
            <p class="text-xxs text-text-dim">
              允许本地未签名插件使用 <code>shell:any</code>；仅在你信任插件时开启。
            </p>
          </div>
        </div>
        <n-switch
          :value="plugins.developerMode"
          :loading="devBusy"
          :disabled="devBusy"
          data-testid="plugins-developer-mode"
          @update:value="toggleDeveloperMode"
        />
      </div>

      <p
        v-if="plugins.error"
        class="rounded bg-danger/10 px-2 py-1 text-xxs text-danger"
        data-testid="plugins-error"
      >
        {{ plugins.error }}
      </p>

      <p v-if="plugins.loading && !plugins.loaded" class="text-xs text-text-dim">正在扫描插件…</p>
      <p v-else-if="plugins.items.length === 0" class="text-xs text-text-dim">
        还没有插件。把插件目录放到上面的路径后点击「重新扫描」。
      </p>

      <ul v-else class="flex flex-col gap-2">
        <li
          v-for="item in plugins.items"
          :key="item.id"
          class="rounded border border-border px-3 py-2"
          data-testid="plugin-row"
          :data-plugin-id="item.id"
        >
          <div class="flex items-start gap-2">
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-1">
                <span class="text-xs font-medium text-text">{{ item.name }}</span>
                <n-tag size="small" :bordered="false">v{{ item.version }}</n-tag>
                <n-tag size="small" :bordered="false" type="info">{{ item.source }}</n-tag>
                <n-tag v-if="item.enabled" size="small" :bordered="false" type="success">
                  已启用
                </n-tag>
                <n-tag v-if="!item.compatible" size="small" :bordered="false" type="error">
                  不兼容
                </n-tag>
              </div>
              <p class="mt-0.5 break-all text-xxs text-text-dim">{{ item.id }}</p>
              <p v-if="item.description" class="mt-1 text-xxs text-text-dim">
                {{ item.description }}
              </p>
              <p v-if="item.permissions.length > 0" class="mt-1 text-xxs text-text-dim">
                权限：{{ item.permissions.join('、') }}
              </p>
              <p
                v-if="item.error || plugins.runtimeErrors[item.id]"
                class="mt-1 rounded bg-danger/10 px-2 py-1 text-xxs text-danger"
              >
                {{ item.error || plugins.runtimeErrors[item.id] }}
              </p>
            </div>
            <div class="flex shrink-0 items-center gap-2">
              <n-button
                v-if="
                  item.enabled &&
                  plugins
                    .viewsFor('rightDock')
                    .some((view) => view.pluginId === item.id)
                "
                size="tiny"
                @click="openRightDock(item)"
              >
                打开视图
              </n-button>
              <n-switch
                :value="item.enabled"
                :disabled="!item.compatible || busyId === item.id || !item.manifest"
                :loading="busyId === item.id"
                data-testid="plugin-enabled"
                @update:value="togglePlugin(item, $event)"
              />
            </div>
          </div>
        </li>
      </ul>
    </div>
  </n-modal>
</template>
