<script setup lang="ts">
import { defineAsyncComponent, onErrorCaptured, ref, watch, type Component } from 'vue';
import { usePluginsStore } from '@/stores/plugins';
import type { RegisteredPluginView } from '@/plugins/types';

const props = defineProps<{ view: RegisteredPluginView }>();

const plugins = usePluginsStore();
const viewError = ref<string | null>(null);
const asyncComponents = new Map<string, Component>();

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

watch(
  () => props.view.key,
  () => {
    viewError.value = null;
  },
);

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
    if (!plugins.runtimeErrors[props.view.pluginId]) {
      viewError.value = null;
    }
  },
  { deep: true },
);
</script>

<template>
  <div class="h-full min-h-0 overflow-auto">
    <div
      v-if="viewError"
      class="m-2 rounded bg-danger/10 px-2 py-1 text-xxs text-danger"
      data-testid="plugin-view-error"
    >
      {{ viewError }}
    </div>
    <component v-else :is="componentFor(view)" :key="view.key" />
  </div>
</template>
