<script setup lang="ts">
import { computed, defineAsyncComponent, onErrorCaptured, ref, watch, type Component } from 'vue';
import { NButton } from 'naive-ui';
import { X } from 'lucide-vue-next';
import { usePluginsStore } from '@/stores/plugins';
import type { PluginSlotName, RegisteredPluginView } from '@/plugins/types';

const props = defineProps<{ dock: PluginSlotName }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const plugins = usePluginsStore();
const viewError = ref<string | null>(null);
const asyncComponents = new Map<string, Component>();

const views = computed(() => plugins.viewsFor(props.dock));
const active = computed(() => plugins.activeViewFor(props.dock));

function componentFor(view: RegisteredPluginView): Component {
  const cached = asyncComponents.get(view.key);
  if (cached) {
    return cached;
  }
  const component = defineAsyncComponent({
    loader: view.component,
    delay: 0,
    timeout: 15000,
    onError: (error, _retry, fail) => {
      viewError.value = `插件视图加载失败：${error instanceof Error ? error.message : String(error)}`;
      fail();
    },
  });
  asyncComponents.set(view.key, component);
  return component;
}

onErrorCaptured((error) => {
  viewError.value = `插件视图运行出错：${error instanceof Error ? error.message : String(error)}`;
  return false;
});

watch(active, () => {
  viewError.value = null;
});

watch(
  () => plugins.registryVersion,
  () => {
    // 插件重载后同一个 view key 可能指向新的组件，不能复用旧缓存
    asyncComponents.clear();
  },
);

watch(
  () => plugins.runtimeErrors,
  () => {
    // 插件重新激活后 runtimeErrors 会被清空，这里同步清掉视图错误
    if (active.value && !plugins.runtimeErrors[active.value.pluginId]) {
      viewError.value = null;
    }
  },
  { deep: true },
);
</script>

<template>
  <aside
    class="flex min-h-0 min-w-0 flex-col overflow-hidden border-border bg-bg-elev"
    data-testid="plugin-slot"
    :data-slot="dock"
  >
    <header
      v-if="active"
      class="flex h-[30px] shrink-0 items-center gap-1 border-b border-border px-2"
    >
      <span class="min-w-0 truncate text-xs font-medium text-text">{{ active.title }}</span>
      <div class="ml-auto flex min-w-0 items-center gap-1">
        <div v-if="views.length > 1" class="flex min-w-0 items-center gap-0.5 overflow-x-auto">
          <n-button
            v-for="view in views"
            :key="view.key"
            size="tiny"
            quaternary
            :type="view.key === active.key ? 'primary' : 'default'"
            @click="plugins.setActiveView(dock, view.key)"
          >
            {{ view.title }}
          </n-button>
        </div>
        <n-button size="tiny" quaternary title="关闭面板" @click="emit('close')">
          <template #icon><X :size="12" /></template>
        </n-button>
      </div>
    </header>

    <div class="min-h-0 flex-1 overflow-auto">
      <div
        v-if="viewError"
        class="m-2 rounded bg-danger/10 px-2 py-1 text-xxs text-danger"
        data-testid="plugin-view-error"
      >
        {{ viewError }}
      </div>
      <component v-else-if="active" :is="componentFor(active)" :key="active.key" />
    </div>
  </aside>
</template>
