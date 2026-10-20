import { computed, onMounted, onUnmounted, ref } from 'vue';
import type { SelectOption } from 'naive-ui';
import type { PluginContext } from '@synclavis/plugin-api';
import {
  aiApi,
  ApiError,
  type AiEvent,
  type AiSession,
  type HarnessInfo,
} from './api';

export const PLUGIN_ID = 'synclavis.ai-panel';

export interface DiffFile {
  path: string;
  status: string;
}

interface BaseItem {
  id: string;
}

export interface UserItem extends BaseItem {
  kind: 'user';
  text: string;
  at: string;
  /** 与 turn.started 对齐；发送中的本地消息暂时为空 */
  turnId?: string;
}

export interface AssistantItem extends BaseItem {
  kind: 'assistant';
  turnId: string;
  text: string;
  streaming: boolean;
  model?: string;
}

export interface ToolItem extends BaseItem {
  kind: 'tool';
  turnId: string;
  toolCallId: string;
  name: string;
  input: unknown;
  done: boolean;
  ok: boolean;
  output?: unknown;
  error?: string;
}

export interface PermissionItem extends BaseItem {
  kind: 'permission';
  turnId: string;
  requestId: string;
  title: string;
  kindLabel: string;
  details: unknown;
  decision: 'allow' | 'deny' | null;
}

export interface DiffItem extends BaseItem {
  kind: 'diff';
  turnId: string;
  files: DiffFile[];
}

export interface ErrorItem extends BaseItem {
  kind: 'error';
  turnId: string;
  code: string;
  message: string;
  details?: unknown;
}

export interface NoticeItem extends BaseItem {
  kind: 'notice';
  text: string;
}

export type ChatItem =
  | UserItem
  | AssistantItem
  | ToolItem
  | PermissionItem
  | DiffItem
  | ErrorItem
  | NoticeItem;

export type WsStatus = 'idle' | 'connecting' | 'open' | 'closed';

function asString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function diffFiles(value: unknown): DiffFile[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const result: DiffFile[] = [];
  for (const entry of value) {
    if (!entry || typeof entry !== 'object') {
      continue;
    }
    const record = entry as Record<string, unknown>;
    const path = asString(record.path);
    if (!path) {
      continue;
    }
    result.push({ path, status: asString(record.status) || 'modified' });
  }
  return result;
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

/**
 * AI 面板的业务状态：harness/会话选择、事件流、WS 重连与权限操作。
 *
 * <p>组件只负责渲染；外部插件不能访问宿主 store，因此当前仓库通过
 * {@link PluginContext.services.repos.activeId} 轮询感知。</p>
 */
export function useAiPanel(ctx: PluginContext) {
  const repoId = ref<string | null>(ctx.services.repos.activeId());
  const harnesses = ref<HarnessInfo[]>([]);
  const activeHarness = ref<Record<string, string>>({});
  const selectedHarnessId = ref<string | null>(null);
  const sessions = ref<AiSession[]>([]);
  const sessionRef = ref<string | null>(null);
  const items = ref<ChatItem[]>([]);
  const lastSeq = ref(0);
  const revision = ref(0);
  const busy = ref(false);
  const currentTurnId = ref<string | null>(null);
  const loading = ref(true);
  const creating = ref(false);
  const deleting = ref(false);
  const sending = ref(false);
  const promptText = ref('');
  const error = ref<string | null>(null);
  const historyTruncated = ref(false);
  const wsStatus = ref<WsStatus>('idle');

  let socket: WebSocket | null = null;
  let reconnectTimer: number | null = null;
  let pingTimer: number | null = null;
  let reconnectDelay = 1000;
  let repoTimer: number | null = null;
  let syncPromise: Promise<void> | null = null;
  let syncSessionRef: string | null = null;
  let disposed = false;
  let localSeq = 0;
  /** 会话切换/关闭时递增，避免上一会话的异步同步结果污染新会话 */
  let sessionEpoch = 0;

  const selectedHarness = computed<HarnessInfo | null>(() => {
    return harnesses.value.find((item) => item.id === selectedHarnessId.value) ?? null;
  });

  const currentSession = computed<AiSession | null>(() => {
    return sessions.value.find((item) => item.sessionRef === sessionRef.value) ?? null;
  });

  /** 当前会话绑定的 harness；选择器只决定“新建会话”的默认 harness。 */
  const sessionHarness = computed<HarnessInfo | null>(() => {
    const harnessId = currentSession.value?.harnessId;
    return harnessId
      ? harnesses.value.find((item) => item.id === harnessId) ?? null
      : null;
  });

  const sessionHarnessAvailable = computed(() => {
    if (!sessionHarness.value) {
      // harness 列表尚未加载时不要误禁用；加载后若仍找不到则视为不可用
      return harnesses.value.length === 0;
    }
    return sessionHarness.value.available;
  });

  const harnessOptions = computed<SelectOption[]>(() =>
    harnesses.value.map((item) => ({
      label: `${item.name}${item.version ? ` ${item.version}` : ''}${item.available ? '' : '（不可用）'}`,
      value: item.id,
    })),
  );

  const sessionOptions = computed<SelectOption[]>(() =>
    sessions.value.map((item) => ({
      label: `${item.title}${item.status === 'busy' ? ' · 执行中' : ''}`,
      value: item.sessionRef,
    })),
  );

  const canCreate = computed(() =>
    Boolean(repoId.value && selectedHarness.value?.available && !creating.value),
  );

  const canSend = computed(() =>
    Boolean(
      sessionRef.value &&
        !busy.value &&
        !sending.value &&
        sessionHarnessAvailable.value &&
        promptText.value.trim(),
    ),
  );

  const wsLabel = computed(() => {
    if (wsStatus.value === 'open') {
      return '已连接';
    }
    if (wsStatus.value === 'connecting') {
      return '连接中';
    }
    if (wsStatus.value === 'closed') {
      return '已断开';
    }
    return '未连接';
  });

  const wsTagType = computed<'success' | 'warning' | 'error' | 'default'>(() => {
    if (wsStatus.value === 'open') {
      return 'success';
    }
    if (wsStatus.value === 'connecting') {
      return 'warning';
    }
    if (wsStatus.value === 'closed') {
      return 'error';
    }
    return 'default';
  });

  const sessionError = computed(() => currentSession.value?.lastError?.message ?? null);

  function setError(value: unknown): void {
    error.value = errorMessage(value);
  }

  function clearError(): void {
    error.value = null;
  }

  function addItem(item: ChatItem): void {
    items.value.push(item);
  }

  function findAssistant(turnId: string): AssistantItem | undefined {
    const found = items.value.find(
      (item) => item.kind === 'assistant' && item.turnId === turnId,
    );
    return found && found.kind === 'assistant' ? found : undefined;
  }

  function ensureAssistant(turnId: string, model?: string): AssistantItem {
    const existing = findAssistant(turnId);
    if (existing) {
      if (!existing.model && model) {
        existing.model = model;
      }
      return existing;
    }
    const item: AssistantItem = {
      id: `assistant:${turnId}`,
      kind: 'assistant',
      turnId,
      text: '',
      streaming: true,
      model,
    };
    addItem(item);
    return item;
  }

  function findTool(turnId: string, toolCallId: string): ToolItem | undefined {
    const found = items.value.find(
      (item) =>
        item.kind === 'tool' &&
        item.turnId === turnId &&
        item.toolCallId === toolCallId,
    );
    return found && found.kind === 'tool' ? found : undefined;
  }

  function findPermission(requestId: string): PermissionItem | undefined {
    const found = items.value.find(
      (item) => item.kind === 'permission' && item.requestId === requestId,
    );
    return found && found.kind === 'permission' ? found : undefined;
  }

  function updateSessionState(ref: string, nextBusy: boolean, turnId: string | null): void {
    const session = sessions.value.find((item) => item.sessionRef === ref);
    if (!session) {
      return;
    }
    session.status = nextBusy ? 'busy' : 'idle';
    session.currentTurnId = turnId;
    if (nextBusy) {
      session.lastError = null;
    }
  }

  function applyEvent(event: AiEvent): void {
    const rawPayload: unknown = event.payload;
    const payload: Record<string, unknown> =
      rawPayload && typeof rawPayload === 'object'
        ? (rawPayload as Record<string, unknown>)
        : {};
    const turnId = typeof event.turnId === 'string' ? event.turnId : '';

    switch (event.type) {
      case 'turn.started': {
        busy.value = true;
        currentTurnId.value = turnId;
        updateSessionState(event.sessionRef, true, turnId);
        const promptText = asString(payload.text);
        if (promptText) {
          const existing = items.value.find(
            (item) => item.kind === 'user' && item.turnId === turnId,
          );
          if (!existing) {
            const pending = items.value.find(
              (item) =>
                item.kind === 'user' &&
                !item.turnId &&
                item.text === promptText,
            );
            if (pending && pending.kind === 'user') {
              pending.turnId = turnId;
            } else {
              addItem({
                id: `user:${turnId}`,
                kind: 'user',
                text: promptText,
                at: event.at,
                turnId,
              });
            }
          }
        }
        ensureAssistant(turnId, asString(payload.model) || undefined);
        break;
      }
      case 'message.delta': {
        const item = ensureAssistant(turnId);
        item.text += asString(payload.text);
        item.streaming = true;
        break;
      }
      case 'message.completed': {
        const item = ensureAssistant(turnId);
        item.text = asString(payload.text);
        item.streaming = false;
        break;
      }
      case 'tool.started': {
        const toolCallId = asString(payload.toolCallId);
        if (!toolCallId) {
          break;
        }
        let item = findTool(turnId, toolCallId);
        if (!item) {
          item = {
            id: `tool:${turnId}:${toolCallId}`,
            kind: 'tool',
            turnId,
            toolCallId,
            name: asString(payload.name) || toolCallId,
            input: payload.input,
            done: false,
            ok: false,
          };
          addItem(item);
        } else {
          item.name = asString(payload.name) || item.name;
          item.input = payload.input;
          item.done = false;
        }
        break;
      }
      case 'tool.completed': {
        const toolCallId = asString(payload.toolCallId);
        if (!toolCallId) {
          break;
        }
        let item = findTool(turnId, toolCallId);
        if (!item) {
          item = {
            id: `tool:${turnId}:${toolCallId}`,
            kind: 'tool',
            turnId,
            toolCallId,
            name: asString(payload.name) || toolCallId,
            input: undefined,
            done: false,
            ok: false,
          };
          addItem(item);
        }
        item.name = asString(payload.name) || item.name;
        item.ok = payload.ok === true;
        item.output = payload.output;
        item.error = asString(payload.error) || undefined;
        item.done = true;
        break;
      }
      case 'permission.requested': {
        const requestId = asString(payload.requestId);
        if (!requestId) {
          break;
        }
        const existing = findPermission(requestId);
        if (existing) {
          existing.title = asString(payload.title) || existing.title;
          existing.kindLabel = asString(payload.kind) || existing.kindLabel;
          existing.details = payload.details;
          break;
        }
        addItem({
          id: `permission:${requestId}`,
          kind: 'permission',
          turnId,
          requestId,
          title: asString(payload.title) || '需要确认',
          kindLabel: asString(payload.kind) || 'permission',
          details: payload.details,
          decision: null,
        });
        break;
      }
      case 'permission.resolved': {
        const requestId = asString(payload.requestId);
        const decision = asString(payload.decision);
        const item = findPermission(requestId);
        if (item && (decision === 'allow' || decision === 'deny')) {
          item.decision = decision;
        }
        break;
      }
      case 'diff.updated': {
        const files = diffFiles(payload.files);
        if (files.length > 0) {
          addItem({
            id: `diff:${turnId}:${event.seq}`,
            kind: 'diff',
            turnId,
            files,
          });
        }
        break;
      }
      case 'error': {
        const message = asString(payload.message) || 'AI 执行失败';
        const code = asString(payload.code) || 'HARNESS_ERROR';
        const existing = items.value.find(
          (item) => item.kind === 'error' && item.turnId === turnId,
        );
        if (existing && existing.kind === 'error') {
          existing.code = code;
          existing.message = message;
          existing.details = payload.details;
        } else {
          addItem({
            id: `error:${turnId}`,
            kind: 'error',
            turnId,
            code,
            message,
            details: payload.details,
          });
        }
        const session = sessions.value.find((item) => item.sessionRef === event.sessionRef);
        if (session) {
          session.lastError = { code, message };
        }
        break;
      }
      case 'turn.completed': {
        busy.value = false;
        currentTurnId.value = null;
        updateSessionState(event.sessionRef, false, null);
        const assistant = findAssistant(turnId);
        if (assistant) {
          assistant.streaming = false;
        }
        if (payload.ok !== true) {
          const hasError = items.value.some(
            (item) => item.kind === 'error' && item.turnId === turnId,
          );
          if (!hasError) {
            addItem({
              id: `notice:${turnId}:failed`,
              kind: 'notice',
              text: '本轮执行失败',
            });
          }
        }
        break;
      }
      default:
        break;
    }
    revision.value += 1;
  }

  function ingestEvent(event: AiEvent): void {
    if (!event || typeof event.seq !== 'number' || event.seq <= lastSeq.value) {
      return;
    }
    lastSeq.value = event.seq;
    applyEvent(event);
  }

  function handleWsEvent(event: AiEvent): void {
    if (event.sessionRef !== sessionRef.value || event.seq <= lastSeq.value) {
      return;
    }
    if (event.seq > lastSeq.value + 1) {
      // 收到断档时先补 HTTP 事件；若同步窗口早于事件入缓冲，再补一次
      void syncEvents().then(() => {
        if (event.seq === lastSeq.value + 1) {
          ingestEvent(event);
          return;
        }
        void syncEvents().then(() => {
          if (event.seq === lastSeq.value + 1) {
            ingestEvent(event);
          }
        });
      });
      return;
    }
    ingestEvent(event);
  }

  function syncEvents(): Promise<void> {
    const ref = sessionRef.value;
    if (!ref) {
      return Promise.resolve();
    }
    if (syncPromise && syncSessionRef === ref) {
      return syncPromise;
    }
    const epoch = sessionEpoch;
    const request = (async () => {
      try {
        const result = await aiApi.events(ref, lastSeq.value, 1000);
        if (sessionRef.value !== ref || epoch !== sessionEpoch) {
          return;
        }
        if (result.truncated) {
          historyTruncated.value = true;
        }
        for (const event of result.events) {
          ingestEvent(event);
        }
      } catch (cause) {
        if (cause instanceof ApiError && cause.code === 'NOT_FOUND') {
          // 后端重启后运行时会话不存在：停止重连并回到会话列表
          closeSession();
          void refreshSessions();
        } else {
          setError(cause);
        }
      }
    })();
    syncPromise = request;
    syncSessionRef = ref;
    void request.finally(() => {
      if (syncPromise === request) {
        syncPromise = null;
        syncSessionRef = null;
      }
    });
    return request;
  }

  function connectSocket(): void {
    if (disposed || !sessionRef.value) {
      return;
    }
    const ref = sessionRef.value;
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${protocol}://${window.location.host}/ws/ai/sessions/${encodeURIComponent(ref)}/events`;
    wsStatus.value = 'connecting';
    const next = new WebSocket(url);
    socket = next;

    next.onopen = () => {
      if (socket !== next) {
        return;
      }
      wsStatus.value = 'open';
      reconnectDelay = 1000;
      startPing();
      // 第一次补拉可能在握手完成前发生；连接建立后再补一次，覆盖这段空隙
      void syncEvents().then(() => syncEvents());
    };

    next.onmessage = (message) => {
      if (socket !== next) {
        return;
      }
      let parsed: unknown;
      try {
        parsed = JSON.parse(String(message.data));
      } catch {
        return;
      }
      if (!parsed || typeof parsed !== 'object') {
        return;
      }
      const record = parsed as Record<string, unknown>;
      if (record.type === 'pong') {
        return;
      }
      if (typeof record.type !== 'string' || typeof record.seq !== 'number') {
        return;
      }
      handleWsEvent(record as unknown as AiEvent);
    };

    next.onerror = () => {
      next.close();
    };

    next.onclose = () => {
      if (socket !== next) {
        return;
      }
      socket = null;
      wsStatus.value = 'closed';
      stopPing();
      // 后端重启会同时丢掉运行时会话；先补拉一次，404 时直接退出重连
      void syncEvents();
      scheduleReconnect();
    };
  }

  function scheduleReconnect(): void {
    if (disposed || !sessionRef.value || reconnectTimer !== null) {
      return;
    }
    const delay = reconnectDelay;
    reconnectDelay = Math.min(reconnectDelay * 2, 10000);
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = null;
      connectSocket();
    }, delay);
  }

  function startPing(): void {
    stopPing();
    pingTimer = window.setInterval(() => {
      if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: 'ping' }));
      }
    }, 20000);
  }

  function stopPing(): void {
    if (pingTimer !== null) {
      window.clearInterval(pingTimer);
      pingTimer = null;
    }
  }

  function closeSocket(): void {
    if (reconnectTimer !== null) {
      window.clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    stopPing();
    const current = socket;
    socket = null;
    if (current) {
      current.onopen = null;
      current.onmessage = null;
      current.onerror = null;
      current.onclose = null;
      current.close();
    }
    wsStatus.value = 'idle';
    reconnectDelay = 1000;
  }

  function openSession(session: AiSession): void {
    closeSocket();
    sessionEpoch += 1;
    syncPromise = null;
    syncSessionRef = null;
    sessionRef.value = session.sessionRef;
    items.value = [];
    lastSeq.value = 0;
    currentTurnId.value = session.currentTurnId;
    busy.value = session.status === 'busy' || Boolean(session.currentTurnId);
    historyTruncated.value = false;
    error.value = null;
    revision.value += 1;
    connectSocket();
    void syncEvents();
  }

  function closeSession(): void {
    closeSocket();
    sessionEpoch += 1;
    syncPromise = null;
    syncSessionRef = null;
    sessionRef.value = null;
    items.value = [];
    lastSeq.value = 0;
    currentTurnId.value = null;
    busy.value = false;
    historyTruncated.value = false;
    sending.value = false;
    error.value = null;
    revision.value += 1;
  }

  async function refreshHarnesses(): Promise<void> {
    try {
      const result = await aiApi.harnesses();
      harnesses.value = result.harnesses;
      activeHarness.value = result.activeHarness;
      const preferred = result.activeHarness[PLUGIN_ID];
      const stillThere = selectedHarnessId.value
        ? result.harnesses.some((item) => item.id === selectedHarnessId.value)
        : false;
      if (stillThere) {
        return;
      }
      if (preferred && result.harnesses.some((item) => item.id === preferred)) {
        selectedHarnessId.value = preferred;
        return;
      }
      const firstAvailable = result.harnesses.find((item) => item.available);
      selectedHarnessId.value = firstAvailable?.id ?? result.harnesses[0]?.id ?? null;
    } catch (cause) {
      setError(cause);
    }
  }

  async function selectHarness(value: string | number | null): Promise<void> {
    if (typeof value !== 'string' || value === selectedHarnessId.value) {
      return;
    }
    const previous = selectedHarnessId.value;
    selectedHarnessId.value = value;
    try {
      activeHarness.value = await aiApi.setActiveHarness(PLUGIN_ID, value);
    } catch (cause) {
      selectedHarnessId.value = previous;
      setError(cause);
    }
  }

  async function refreshSessions(): Promise<void> {
    const repo = repoId.value;
    if (!repo) {
      sessions.value = [];
      closeSession();
      return;
    }
    try {
      const result = await aiApi.sessions(repo);
      if (repoId.value !== repo) {
        return;
      }
      sessions.value = result.sessions;
      if (sessionRef.value) {
        const current = result.sessions.find((item) => item.sessionRef === sessionRef.value);
        if (!current) {
          closeSession();
        } else {
          currentTurnId.value = current.currentTurnId;
          busy.value = current.status === 'busy' || Boolean(current.currentTurnId);
        }
      }
      if (!sessionRef.value && result.sessions.length > 0) {
        openSession(result.sessions[0]);
      }
    } catch (cause) {
      setError(cause);
    }
  }

  function selectSession(value: string | number | null): void {
    if (typeof value !== 'string' || value === sessionRef.value) {
      return;
    }
    const session = sessions.value.find((item) => item.sessionRef === value);
    if (session) {
      openSession(session);
    }
  }

  async function newSession(): Promise<void> {
    const repo = repoId.value;
    if (!repo || !selectedHarness.value?.available || creating.value) {
      return;
    }
    const harnessId = selectedHarness.value.id;
    creating.value = true;
    error.value = null;
    try {
      const session = await aiApi.createSession(repo, PLUGIN_ID, harnessId);
      if (repoId.value !== repo) {
        return;
      }
      sessions.value = [session, ...sessions.value];
      openSession(session);
    } catch (cause) {
      setError(cause);
    } finally {
      creating.value = false;
    }
  }

  async function deleteSession(): Promise<void> {
    const ref = sessionRef.value;
    if (!ref || deleting.value) {
      return;
    }
    deleting.value = true;
    error.value = null;
    try {
      await aiApi.deleteSession(ref);
      closeSession();
      await refreshSessions();
    } catch (cause) {
      setError(cause);
    } finally {
      deleting.value = false;
    }
  }

  async function refreshCurrentSession(): Promise<void> {
    const ref = sessionRef.value;
    if (!ref) {
      return;
    }
    try {
      const session = await aiApi.session(ref);
      if (sessionRef.value !== ref) {
        return;
      }
      const index = sessions.value.findIndex((item) => item.sessionRef === ref);
      if (index >= 0) {
        sessions.value.splice(index, 1, session);
      }
      currentTurnId.value = session.currentTurnId;
      busy.value = session.status === 'busy' || Boolean(session.currentTurnId);
    } catch (cause) {
      if (cause instanceof ApiError && cause.code === 'NOT_FOUND') {
        closeSession();
        await refreshSessions();
      } else {
        setError(cause);
      }
    }
  }

  async function send(): Promise<void> {
    const ref = sessionRef.value;
    const text = promptText.value.trim();
    if (!ref || !text || busy.value || sending.value) {
      return;
    }
    const userItem: UserItem = {
      id: `user:${++localSeq}`,
      kind: 'user',
      text,
      at: new Date().toISOString(),
    };
    addItem(userItem);
    promptText.value = '';
    sending.value = true;
    error.value = null;
    revision.value += 1;
    try {
      const result = await aiApi.prompt(ref, text);
      if (sessionRef.value === ref) {
        // 事件可能比 HTTP 响应先到；已收到 turn.completed 时不能把 busy 又置回 true
        const assistant = findAssistant(result.turnId);
        if (!assistant || assistant.streaming) {
          currentTurnId.value = result.turnId;
          busy.value = true;
        }
        // 以服务端状态兜底，覆盖事件与 HTTP 响应交错、或事件暂时丢失的情况
        void refreshCurrentSession();
      }
    } catch (cause) {
      items.value = items.value.filter((item) => item.id !== userItem.id);
      promptText.value = text;
      setError(cause);
      void refreshCurrentSession();
    } finally {
      sending.value = false;
      revision.value += 1;
    }
  }

  async function cancel(): Promise<void> {
    const ref = sessionRef.value;
    if (!ref) {
      return;
    }
    try {
      await aiApi.cancel(ref, currentTurnId.value ?? undefined);
    } catch (cause) {
      setError(cause);
    } finally {
      void refreshCurrentSession();
    }
  }

  async function respondPermission(
    requestId: string,
    decision: 'allow' | 'deny',
  ): Promise<void> {
    const ref = sessionRef.value;
    if (!ref) {
      return;
    }
    const item = findPermission(requestId);
    const previous = item?.decision ?? null;
    if (item) {
      item.decision = decision;
      revision.value += 1;
    }
    try {
      await aiApi.respondPermission(ref, requestId, decision);
    } catch (cause) {
      if (item) {
        item.decision = previous;
        revision.value += 1;
      }
      setError(cause);
    }
  }

  function onRepoChanged(): void {
    const next = ctx.services.repos.activeId();
    if (next === repoId.value) {
      return;
    }
    repoId.value = next;
    closeSession();
    sessions.value = [];
    void refreshSessions();
  }

  async function boot(): Promise<void> {
    loading.value = true;
    try {
      await Promise.allSettled([refreshHarnesses(), refreshSessions()]);
    } finally {
      loading.value = false;
    }
    repoTimer = window.setInterval(onRepoChanged, 1000);
  }

  function dispose(): void {
    disposed = true;
    if (repoTimer !== null) {
      window.clearInterval(repoTimer);
      repoTimer = null;
    }
    closeSocket();
    sessions.value = [];
    items.value = [];
  }

  onMounted(() => {
    void boot();
  });

  onUnmounted(dispose);

  return {
    repoId,
    harnesses,
    activeHarness,
    selectedHarnessId,
    selectedHarness,
    harnessOptions,
    sessions,
    sessionOptions,
    sessionRef,
    currentSession,
    sessionHarness,
    sessionHarnessAvailable,
    items,
    revision,
    busy,
    currentTurnId,
    loading,
    creating,
    deleting,
    sending,
    promptText,
    error,
    historyTruncated,
    wsStatus,
    wsLabel,
    wsTagType,
    sessionError,
    canCreate,
    canSend,
    refreshHarnesses,
    refreshSessions,
    selectHarness,
    selectSession,
    newSession,
    deleteSession,
    send,
    cancel,
    respondPermission,
    clearError,
  };
}

export type AiPanelController = ReturnType<typeof useAiPanel>;
