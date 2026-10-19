<script setup lang="ts">
import { Plus } from 'lucide-vue-next';

const emit = defineEmits<{ (e: 'open'): void }>();
</script>

<template>
  <button
    type="button"
    class="mb-0.5 ml-1 flex h-[26px] w-[26px] shrink-0 items-center justify-center rounded text-text-dim hover:bg-bg-hover hover:text-text"
    title="打开仓库"
    @click="emit('open')"
  >
    <Plus :size="14" />
  </button>
</template>
