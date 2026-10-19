import type { WsEvent } from '@/types/events';

/** 默认按当前站点推导；dev 下由 Vite proxy 转发到后端 */
const WS_BASE =
  import.meta.env.VITE_WS_BASE ??
  `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}`;

export type WsEventHandler = (event: WsEvent) => void;
export type WsStateHandler = (state: 'open' | 'closed') => void;

export interface WsConnection {
  send: (payload: unknown) => void;
  close: () => void;
}

/**
 * 通用事件流连接（内置指数退避重连，最多 8 次）。
 * 重连成功后的状态补偿（拉一次 status）由调用方负责。
 */
export function connectEvents(
  path: string,
  onEvent: WsEventHandler,
  onState?: WsStateHandler,
): WsConnection {
  let socket: WebSocket | null = null;
  let retry = 0;
  let closedByUser = false;
  let reconnectTimer: number | undefined;

  const open = (): void => {
    socket = new WebSocket(`${WS_BASE}${path}`);

    socket.onopen = () => {
      retry = 0;
      onState?.('open');
    };

    socket.onmessage = (message: MessageEvent<string>) => {
      try {
        onEvent(JSON.parse(message.data) as WsEvent);
      } catch {
        // 忽略无法解析的消息
      }
    };

    socket.onclose = () => {
      onState?.('closed');
      if (closedByUser || retry >= 8) {
        return;
      }
      const delay = Math.min(1000 * 2 ** retry, 15000);
      retry += 1;
      reconnectTimer = window.setTimeout(open, delay);
    };
  };

  open();

  return {
    send: (payload: unknown) => {
      if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(payload));
      }
    },
    close: () => {
      closedByUser = true;
      if (reconnectTimer !== undefined) {
        window.clearTimeout(reconnectTimer);
      }
      socket?.close();
    },
  };
}

/** 订阅某仓库的事件流。 */
export function connectRepoEvents(
  repoId: string,
  onEvent: WsEventHandler,
  onState?: WsStateHandler,
): WsConnection {
  return connectEvents(`/ws/repos/${repoId}/events`, onEvent, onState);
}

/** 订阅全局事件流（clone 等尚无 repoId 的操作）。 */
export function connectGlobalEvents(
  onEvent: WsEventHandler,
  onState?: WsStateHandler,
): WsConnection {
  return connectEvents('/ws/events', onEvent, onState);
}
