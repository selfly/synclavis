<script setup lang="ts">
import { computed } from 'vue';
import { NButton } from 'naive-ui';
import { X } from 'lucide-vue-next';
import PluginViewHost from '@/components/plugins/PluginViewHost.vue';
import { usePluginsStore } from '@/stores/plugins';
import type { PluginSlotName } from '@/plugins/types';

const props = defineProps<{ dock: PluginSlotName }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const plugins = usePluginsStore();
const views = computed(() => plugins.viewsFor(props.dock));
const active = computed(() => plugins.activeViewFor(props.dock));
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

    <PluginViewHost v-if="active" :view="active" class="min-h-0 flex-1" />
  </aside>
</template>
