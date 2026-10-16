<script setup lang="ts">
import { NConfigProvider, NDialogProvider, NMessageProvider } from 'naive-ui';
import AppShell from '@/components/shell/AppShell.vue';
import { naiveThemeOverrides, useNaiveTheme } from '@/composables/useTheme';

const naiveTheme = useNaiveTheme();
</script>

<template>
  <n-config-provider :theme="naiveTheme" :theme-overrides="naiveThemeOverrides">
    <n-message-provider>
      <n-dialog-provider>
        <AppShell />
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>
