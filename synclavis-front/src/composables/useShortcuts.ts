import { onMounted, onUnmounted } from 'vue';
import { useSettingsStore } from '@/stores/settings';

export interface ShortcutHandlers {
  refresh: () => void;
  fetch: () => void;
  push: () => void;
}

/** 把 KeyboardEvent 归一化成 "Ctrl+Shift+F" / "F5" 这样的组合键串。 */
export function comboOf(event: KeyboardEvent): string {
  const parts: string[] = [];
  if (event.ctrlKey || event.metaKey) {
    parts.push('Ctrl');
  }
  if (event.altKey) {
    parts.push('Alt');
  }
  if (event.shiftKey) {
    parts.push('Shift');
  }
  let key = event.key;
  if (key === ' ') {
    key = 'Space';
  } else if (key.length === 1) {
    key = key.toUpperCase();
  }
  parts.push(key);
  return parts.join('+');
}

function isEditableTarget(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  if (!element) {
    return false;
  }
  const tag = element.tagName;
  return (
    tag === 'INPUT' ||
    tag === 'TEXTAREA' ||
    tag === 'SELECT' ||
    element.isContentEditable === true
  );
}

/**
 * 全局快捷键（可在后端「全局设置」中改键）。
 *
 * <p>默认：F5 刷新、Ctrl+Shift+F Fetch、Ctrl+Shift+P Push。
 * 输入框聚焦时不触发，避免干扰正常输入。</p>
 */
export function useShortcuts(handlers: ShortcutHandlers): void {
  const settings = useSettingsStore();

  function onKeydown(event: KeyboardEvent): void {
    if (isEditableTarget(event.target)) {
      return;
    }
    const combo = comboOf(event);
    const shortcuts = settings.shortcuts;

    if (combo === shortcuts.refresh) {
      event.preventDefault();
      handlers.refresh();
    } else if (combo === shortcuts.fetch) {
      event.preventDefault();
      handlers.fetch();
    } else if (combo === shortcuts.push) {
      event.preventDefault();
      handlers.push();
    }
  }

  onMounted(() => window.addEventListener('keydown', onKeydown));
  onUnmounted(() => window.removeEventListener('keydown', onKeydown));
}
