<script setup lang="ts">
import OpenRepoButton from './OpenRepoButton.vue';
import RepoTabItem from './RepoTabItem.vue';
import ThemeToggle from '@/components/common/ThemeToggle.vue';
import { useTabsStore } from '@/stores/tabs';

const emit = defineEmits<{ (e: 'open'): void }>();

const tabs = useTabsStore();

async function onClose(id: string): Promise<void> {
  await tabs.closeRepo(id);
}
</script>

<template>
  <div class="flex h-[34px] shrink-0 items-end gap-0.5 border-b border-border bg-bg-elev px-1">
    <div class="flex min-w-0 flex-1 items-end gap-0.5 overflow-x-auto">
      <RepoTabItem
        v-for="tab in tabs.tabs"
        :key="tab.id"
        :tab="tab"
        :active="tab.id === tabs.activeId"
        @activate="tabs.setActive($event)"
        @close="onClose"
      />
    </div>
    <OpenRepoButton @open="emit('open')" />
    <ThemeToggle />
  </div>
</template>
