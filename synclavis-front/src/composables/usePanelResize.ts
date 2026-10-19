import { onBeforeUnmount, ref, type Ref } from 'vue';
import { clamp, resolveDragSize, resolveResizeDirection } from '@/utils/layout';

export interface PanelResizeOptions {
  /** 拖拽轴：`y` 表示水平分隔条（改高度），`x` 表示垂直分隔条（改宽度） */
  axis: 'x' | 'y';
  /** 当前尺寸（拖动起点用） */
  size: () => number;
  min: () => number;
  max: () => number;
  /** true = 向上/向左拖变大（分隔条位于面板右侧或下方） */
  invert?: boolean;
  /** 拖拽与按键过程中的实时回调（不落盘） */
  onChange: (size: number) => void;
  /** 交互结束回调（落盘一次） */
  onCommit: (size: number) => void;
  /** 键盘步进像素 */
  step?: number;
  /** 键盘调整后的落盘防抖 */
  commitDelay?: number;
}

export interface PanelResize {
  dragging: Ref<boolean>;
  onPointerDown: (event: PointerEvent) => void;
  onPointerMove: (event: PointerEvent) => void;
  onPointerUp: (event: PointerEvent) => void;
  onKeydown: (event: KeyboardEvent) => void;
}

/**
 * 分隔条拖拽/键盘调整。
 *
 * <p>指针路径用 `setPointerCapture`，因此指针移出分隔条仍能继续拖动；
 * 落盘只在 `pointerup` 发生一次，避免拖拽过程中刷爆后端。</p>
 */
export function usePanelResize(options: PanelResizeOptions): PanelResize {
  const dragging = ref(false);
  let startPosition = 0;
  let startSize = 0;
  let lastSize = options.size();
  let timer: ReturnType<typeof setTimeout> | null = null;

  function positionOf(event: { clientX: number; clientY: number }): number {
    return options.axis === 'x' ? event.clientX : event.clientY;
  }

  function compute(position: number): number {
    return resolveDragSize({
      base: startSize,
      delta: position - startPosition,
      invert: options.invert === true,
      min: options.min(),
      max: options.max(),
    });
  }

  function onPointerDown(event: PointerEvent): void {
    event.preventDefault();
    dragging.value = true;
    startPosition = positionOf(event);
    startSize = options.size();
    lastSize = startSize;
    (event.currentTarget as HTMLElement | null)?.setPointerCapture?.(event.pointerId);
  }

  function onPointerMove(event: PointerEvent): void {
    if (!dragging.value) {
      return;
    }
    lastSize = compute(positionOf(event));
    options.onChange(lastSize);
  }

  function onPointerUp(event: PointerEvent): void {
    if (!dragging.value) {
      return;
    }
    dragging.value = false;
    (event.currentTarget as HTMLElement | null)?.releasePointerCapture?.(event.pointerId);
    options.onCommit(lastSize);
  }

  function scheduleCommit(size: number): void {
    if (timer !== null) {
      clearTimeout(timer);
    }
    timer = setTimeout(() => {
      timer = null;
      options.onCommit(size);
    }, options.commitDelay ?? 400);
  }

  function onKeydown(event: KeyboardEvent): void {
    const step = options.step ?? 8;
    const direction = resolveResizeDirection({
      axis: options.axis,
      invert: options.invert === true,
      key: event.key,
    });
    if (direction === 0) {
      return;
    }

    event.preventDefault();
    const next = clamp(options.size() + direction * step, options.min(), options.max());
    options.onChange(next);
    scheduleCommit(next);
  }

  onBeforeUnmount(() => {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  });

  return { dragging, onPointerDown, onPointerMove, onPointerUp, onKeydown };
}
