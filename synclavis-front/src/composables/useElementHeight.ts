import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue';

/**
 * 追踪某个元素的实测高度（`clientHeight`）。
 *
 * <p>用于计算拖拽面板的动态上限：容器被压缩时不允许把对侧内容挤成 0。</p>
 */
export function useElementHeight(target: Ref<HTMLElement | null>): Ref<number> {
  const height = ref(0);
  let observer: ResizeObserver | null = null;

  function measure(): void {
    height.value = target.value?.clientHeight ?? 0;
  }

  onMounted(() => {
    measure();
    if (typeof ResizeObserver !== 'undefined' && target.value) {
      observer = new ResizeObserver(measure);
      observer.observe(target.value);
    }
    window.addEventListener('resize', measure);
  });

  onBeforeUnmount(() => {
    if (observer) {
      observer.disconnect();
      observer = null;
    }
    window.removeEventListener('resize', measure);
  });

  return height;
}

/**
 * 追踪某个元素的实测宽度（`clientWidth`）。
 *
 * <p>横向分隔条的动态上限用：窄窗口下不允许把右侧 Diff 区压没。</p>
 */
export function useElementWidth(target: Ref<HTMLElement | null>): Ref<number> {
  const width = ref(0);
  let observer: ResizeObserver | null = null;

  function measure(): void {
    width.value = target.value?.clientWidth ?? 0;
  }

  onMounted(() => {
    measure();
    if (typeof ResizeObserver !== 'undefined' && target.value) {
      observer = new ResizeObserver(measure);
      observer.observe(target.value);
    }
    window.addEventListener('resize', measure);
  });

  onBeforeUnmount(() => {
    if (observer) {
      observer.disconnect();
      observer = null;
    }
    window.removeEventListener('resize', measure);
  });

  return width;
}

/** 追踪视口高度（日志面板上限用） */
export function useViewportHeight(): Ref<number> {
  const height = ref(typeof window === 'undefined' ? 0 : window.innerHeight);

  function measure(): void {
    height.value = window.innerHeight;
  }

  onMounted(() => {
    measure();
    window.addEventListener('resize', measure);
  });

  onBeforeUnmount(() => {
    window.removeEventListener('resize', measure);
  });

  return height;
}
