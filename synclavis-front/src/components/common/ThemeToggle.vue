<script setup lang="ts">
import { computed } from 'vue';
import { Moon, Sun } from 'lucide-vue-next';
import { useSettingsStore } from '@/stores/settings';

const settings = useSettingsStore();

const isDark = computed(() => settings.theme === 'dark');

async function toggle(): Promise<void> {
  await settings.setTheme(isDark.value ? 'light' : 'dark');
}
</script>

<template>
  <button
    type="button"
    class="mb-0.5 ml-1 flex h-[26px] w-[26px] shrink-0 items-center justify-center rounded text-text-dim hover:bg-bg-hover hover:text-text"
    :title="isDark ? '切换到浅色主题' : '切换到深色主题'"
    @click="toggle"
  >
    <component :is="isDark ? Sun : Moon" :size="14" />
  </button>
</template>
