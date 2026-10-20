<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { FitAddon } from '@xterm/addon-fit';
import { Terminal } from '@xterm/xterm';
import type { ITheme } from '@xterm/xterm';
import type { Disposable, PluginContext } from '@synclavis/plugin-api';
import { ApiError, terminalApi, type TerminalSession } from './api';

const props = defineProps<{ context: PluginContext }>();

type TerminalStatus = 'idle' | 'connecting' | 'connected' | 'exited' | 'error';

interface ServerMessage {
  type: 'output' | 'exit' | 'error' | 'pong';
  data?: string;
  code?: number | string;
  message?: string;
}

/** 每个仓库独立的 UI 状态；xterm/socket 等运行时对象不放进响应式系统。 */
interface RepoState {
  status: TerminalStatus;
  errorMessage: string | null;
  session: TerminalSession | null;
}

interface RepoRuntime {
  repoId: string;
  host: HTMLElement;
  term: Terminal;
  fitAddon: FitAddon;
  socket: WebSocket | null;
  session: TerminalSession | null;
  status: TerminalStatus;
  errorMessage: string | null;
  generation: number;
  pendingInput: string;
  fitFrame: number;
  resizeObserver: ResizeObserver | null;
  disposed: boolean;
}

const MAX_PENDING_INPUT = 64 * 1024;
const INPUT_CHUNK = 8 * 1024;

const repoOrder = ref<string[]>([]);
const activeRepoId = ref<string | null>(null);
const states = reactive<Record<string, RepoState>>({});

const hostRefs = new Map<string, HTMLElement>();
const runtimes = new Map<string, RepoRuntime>();

let disposed = false;
let themeObserver: MutationObserver | null = null;
let repoActiveSubscription: Disposable | null = null;
let repoOpenSubscription: Disposable | null = null;

const activeState = computed<RepoState | null>(() =>
  activeRepoId.value ? states[activeRepoId.value] ?? null : null,
);

const statusText = computed(() => {
  switch (activeState.value?.status) {
    case 'connecting':
      return '连接中…';
    case 'connected':
      return '已连接';
    case 'exited':
      return '已退出';
    case 'error':
      return '连接失败';
    default:
      return activeRepoId.value ? '空闲' : '未打开仓库';
  }
});

const shellLabel = computed(() => {
  const shell = activeState.value?.session?.shell;
  if (!shell) {
    return 'shell';
  }
  const parts = shell.split(/[\\/]/);
  return parts[parts.length - 1] || shell;
});

const cwdLabel = computed(() => activeState.value?.session?.cwd ?? '');
const errorMessage = computed(() => activeState.value?.errorMessage ?? null);

function cssRgb(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value ? `rgb(${value})` : fallback;
}

function cssRgbAlpha(name: string, alpha: number, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value ? `rgb(${value} / ${alpha})` : fallback;
}

function readTheme(): ITheme {
  return {
    background: cssRgb('--sl-bg-rgb', '#1e1e1e'),
    foreground: cssRgb('--sl-text-rgb', '#d4d4d4'),
    cursor: cssRgb('--sl-accent-rgb', '#3b82f6'),
    cursorAccent: cssRgb('--sl-bg-rgb', '#1e1e1e'),
    selectionBackground: cssRgbAlpha('--sl-accent-rgb', 0.35, 'rgba(59,130,246,0.35)'),
  };
}

function createRuntime(repoId: string, host: HTMLElement): RepoRuntime {
  const term = new Terminal({
    allowProposedApi: false,
    cursorBlink: true,
    convertEol: false,
    fontFamily: 'Menlo, Monaco, "Cascadia Code", "SFMono-Regular", Consolas, monospace',
    fontSize: 12,
    lineHeight: 1.2,
    scrollback: 5000,
    theme: readTheme(),
  });
  const fitAddon = new FitAddon();
  term.loadAddon(fitAddon);
  term.open(host);

  const runtime: RepoRuntime = {
    repoId,
    host,
    term,
    fitAddon,
    socket: null,
    session: null,
    status: 'idle',
    errorMessage: null,
    generation: 0,
    pendingInput: '',
    fitFrame: 0,
    resizeObserver: null,
    disposed: false,
  };

  term.onData((data) => sendInput(runtime, data));
  term.onResize(() => sendResize(runtime));

  if (typeof ResizeObserver !== 'undefined') {
    runtime.resizeObserver = new ResizeObserver(() => {
      if (activeRepoId.value === runtime.repoId) {
        scheduleFit(runtime);
      }
    });
    runtime.resizeObserver.observe(host);
  }

  scheduleFit(runtime);
  return runtime;
}

function scheduleFit(runtime: RepoRuntime): void {
  if (runtime.fitFrame !== 0 || runtime.disposed) {
    return;
  }
  runtime.fitFrame = requestAnimationFrame(() => {
    runtime.fitFrame = 0;
    fit(runtime);
  });
}

function fit(runtime: RepoRuntime): void {
  if (runtime.disposed || activeRepoId.value !== runtime.repoId) {
    return;
  }
  try {
    runtime.fitAddon.fit();
  } catch {
    // 面板折叠/切换时容器可能暂时没有尺寸，下一次 ResizeObserver 会再试
  }
}

function sendInputChunks(runtime: RepoRuntime, data: string): void {
  for (let offset = 0; offset < data.length; offset += INPUT_CHUNK) {
    const chunk = data.slice(offset, offset + INPUT_CHUNK);
    sendJson(runtime, { type: 'input', data: chunk });
  }
}

function sendInput(runtime: RepoRuntime, data: string): void {
  if (!data || runtime.disposed) {
    return;
  }
  if (runtime.socket && runtime.socket.readyState === WebSocket.OPEN) {
    sendInputChunks(runtime, data);
    return;
  }
  if (runtime.status === 'connecting') {
    runtime.pendingInput += data;
    if (runtime.pendingInput.length > MAX_PENDING_INPUT) {
      runtime.pendingInput = runtime.pendingInput.slice(-MAX_PENDING_INPUT);
    }
  }
}

function sendResize(runtime: RepoRuntime): void {
  if (!runtime.socket || runtime.socket.readyState !== WebSocket.OPEN) {
    return;
  }
  sendJson(runtime, {
    type: 'resize',
    cols: Math.max(1, Math.min(500, runtime.term.cols)),
    rows: Math.max(1, Math.min(500, runtime.term.rows)),
  });
}

function sendJson(runtime: RepoRuntime, payload: Record<string, unknown>): void {
  if (!runtime.socket || runtime.socket.readyState !== WebSocket.OPEN) {
    return;
  }
  try {
    runtime.socket.send(JSON.stringify(payload));
  } catch {
    // onclose 会统一处理
  }
}

function closeSocket(runtime: RepoRuntime): void {
  const current = runtime.socket;
  runtime.socket = null;
  runtime.pendingInput = '';
  if (!current) {
    return;
  }
  current.onopen = null;
  current.onmessage = null;
  current.onerror = null;
  current.onclose = null;
  try {
    current.close(1000, 'client reset');
  } catch {
    // 已经关闭
  }
}

function setRuntimeState(runtime: RepoRuntime, patch: Partial<RepoState>): void {
  Object.assign(runtime, patch);
  const state = states[runtime.repoId];
  if (state) {
    Object.assign(state, patch);
  }
}

async function resetSession(runtime: RepoRuntime, deleteRemote: boolean): Promise<void> {
  runtime.generation += 1;
  closeSocket(runtime);
  const terminalId = runtime.session?.terminalId;
  runtime.session = null;
  setRuntimeState(runtime, { status: 'idle', errorMessage: null, session: null });
  if (terminalId && deleteRemote) {
    void terminalApi.delete(terminalId).catch(() => {
      // 进程可能已自然退出，DELETE 404 可忽略
    });
  }
}

async function startSession(runtime: RepoRuntime): Promise<void> {
  if (disposed || runtime.disposed || !runtime.host.isConnected) {
    return;
  }

  const gen = ++runtime.generation;
  runtime.term.reset();
  setRuntimeState(runtime, { status: 'connecting', errorMessage: null });
  fit(runtime);
  try {
    const created = await terminalApi.create(
      runtime.repoId,
      runtime.term.cols || 80,
      runtime.term.rows || 24,
    );
    if (disposed || runtime.disposed || gen !== runtime.generation) {
      void terminalApi.delete(created.terminalId).catch(() => {});
      return;
    }
    runtime.session = created;
    setRuntimeState(runtime, { session: created });
    connect(runtime, created.terminalId, gen);
  } catch (error) {
    if (disposed || runtime.disposed || gen !== runtime.generation) {
      return;
    }
    const hint =
      error instanceof ApiError && error.code === 'TOO_MANY_TERMINALS'
        ? '（终端数量已达上限，请关闭不需要的仓库标签）'
        : '';
    const message = `${error instanceof Error ? error.message : String(error)}${hint}`;
    setRuntimeState(runtime, { status: 'error', errorMessage: message });
    runtime.term.writeln(`\x1b[31m${message}\x1b[0m`);
  }
}

function connect(runtime: RepoRuntime, terminalId: string, gen: number): void {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const url = `${protocol}//${window.location.host}/ws/terminal/sessions/${encodeURIComponent(terminalId)}`;
  const ws = new WebSocket(url);
  runtime.socket = ws;

  ws.onopen = () => {
    if (runtime.socket !== ws || gen !== runtime.generation) {
      return;
    }
    setRuntimeState(runtime, { status: 'connected' });
    if (runtime.pendingInput) {
      const buffered = runtime.pendingInput;
      runtime.pendingInput = '';
      sendInputChunks(runtime, buffered);
    }
    sendResize(runtime);
  };

  ws.onmessage = (event) => {
    if (runtime.socket !== ws || gen !== runtime.generation) {
      return;
    }
    let message: ServerMessage;
    try {
      message = JSON.parse(String(event.data)) as ServerMessage;
    } catch {
      return;
    }
    if (message.type === 'output') {
      runtime.term.write(message.data ?? '');
      return;
    }
    if (message.type === 'exit') {
      const code = typeof message.code === 'number' ? message.code : -1;
      setRuntimeState(runtime, { status: 'exited', session: null });
      runtime.session = null;
      runtime.term.write(`\r\n\x1b[90m[进程已退出，退出码 ${code}]\x1b[0m\r\n`);
      return;
    }
    if (message.type === 'error') {
      const text = message.message ?? '终端错误';
      setRuntimeState(runtime, { status: 'error', errorMessage: text });
      runtime.term.write(`\r\n\x1b[31m[${text}]\x1b[0m\r\n`);
    }
  };

  ws.onerror = () => {
    if (runtime.socket !== ws || gen !== runtime.generation) {
      return;
    }
    setRuntimeState(runtime, {
      status: 'error',
      errorMessage: `终端连接失败（${runtime.repoId}）`,
    });
  };

  ws.onclose = () => {
    if (runtime.socket !== ws) {
      return;
    }
    runtime.socket = null;
    if (
      gen === runtime.generation &&
      (runtime.status === 'connected' || runtime.status === 'connecting')
    ) {
      setRuntimeState(runtime, { status: 'error', errorMessage: '终端连接已断开' });
    }
  };
}

function setHost(repoId: string, el: unknown): void {
  if (el instanceof HTMLElement) {
    hostRefs.set(repoId, el);
    return;
  }
  hostRefs.delete(repoId);
}

async function switchRepo(next: string | null): Promise<void> {
  if (disposed) {
    return;
  }
  const normalized = next || null;
  if (normalized === activeRepoId.value && (!normalized || runtimes.has(normalized))) {
    return;
  }
  activeRepoId.value = normalized;
  if (!normalized) {
    return;
  }

  if (!repoOrder.value.includes(normalized)) {
    repoOrder.value = [...repoOrder.value, normalized];
  }
  await nextTick();
  if (disposed || activeRepoId.value !== normalized) {
    return;
  }

  const host = hostRefs.get(normalized);
  if (!host) {
    return;
  }

  const existing = runtimes.get(normalized);
  if (existing) {
    existing.term.options.theme = readTheme();
    scheduleFit(existing);
    existing.term.refresh(0, Math.max(0, existing.term.rows - 1));
    return;
  }

  states[normalized] = { status: 'idle', errorMessage: null, session: null };
  const runtime = createRuntime(normalized, host);
  runtimes.set(normalized, runtime);
  void startSession(runtime);
}

function onReposChanged(openIds: string[]): void {
  const open = new Set(openIds);
  for (const repoId of Array.from(runtimes.keys())) {
    if (!open.has(repoId)) {
      disposeRuntime(repoId, true);
    }
  }
  repoOrder.value = repoOrder.value.filter((repoId) => open.has(repoId));
}

function disposeRuntime(repoId: string, deleteRemote: boolean): void {
  const runtime = runtimes.get(repoId);
  runtimes.delete(repoId);
  delete states[repoId];
  hostRefs.delete(repoId);
  if (!runtime) {
    return;
  }
  runtime.disposed = true;
  runtime.generation += 1;
  if (runtime.fitFrame !== 0) {
    cancelAnimationFrame(runtime.fitFrame);
    runtime.fitFrame = 0;
  }
  runtime.resizeObserver?.disconnect();
  runtime.resizeObserver = null;

  closeSocket(runtime);
  const terminalId = runtime.session?.terminalId;
  runtime.session = null;
  if (terminalId && deleteRemote) {
    void terminalApi.delete(terminalId).catch(() => {
      // 进程可能已自然退出，DELETE 404 可忽略
    });
  }
  runtime.term.dispose();
}

async function restart(): Promise<void> {
  const runtime = activeRepoId.value ? runtimes.get(activeRepoId.value) : null;
  if (!runtime) {
    return;
  }
  await resetSession(runtime, true);
  await startSession(runtime);
}

function clear(): void {
  const runtime = activeRepoId.value ? runtimes.get(activeRepoId.value) : null;
  runtime?.term.clear();
}

onMounted(async () => {
  await nextTick();
  themeObserver = new MutationObserver(() => {
    const theme = readTheme();
    for (const runtime of runtimes.values()) {
      runtime.term.options.theme = theme;
    }
  });
  themeObserver.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-theme'],
  });

  repoActiveSubscription = props.context.services.repos.onDidChangeActive((next) => {
    void switchRepo(next);
  });
  repoOpenSubscription = props.context.services.repos.onDidChangeOpen((ids) => {
    onReposChanged(ids);
  });

  onReposChanged(props.context.services.repos.openIds());
  await switchRepo(props.context.services.repos.activeId());
});

onBeforeUnmount(() => {
  disposed = true;
  repoActiveSubscription?.dispose();
  repoActiveSubscription = null;
  repoOpenSubscription?.dispose();
  repoOpenSubscription = null;
  themeObserver?.disconnect();
  themeObserver = null;
  for (const repoId of Array.from(runtimes.keys())) {
    disposeRuntime(repoId, true);
  }
  repoOrder.value = [];
  hostRefs.clear();
});
</script>

<template>
  <div class="terminal-panel">
    <div class="terminal-toolbar">
      <span class="terminal-title">终端</span>
      <span class="terminal-meta" :title="cwdLabel">{{ shellLabel }}</span>
      <span class="terminal-meta terminal-cwd" :title="cwdLabel">{{ cwdLabel }}</span>
      <span class="terminal-status" :data-status="activeState?.status ?? 'idle'">
        {{ statusText }}
      </span>
      <button type="button" class="terminal-button" :disabled="!activeRepoId" @click="restart">
        重启
      </button>
      <button type="button" class="terminal-button" @click="clear">清空</button>
    </div>
    <p v-if="errorMessage" class="terminal-error" data-testid="terminal-error">
      {{ errorMessage }}
    </p>
    <div class="terminal-hosts">
      <div
        v-for="repo in repoOrder"
        :key="repo"
        :ref="(el) => setHost(repo, el)"
        v-show="repo === activeRepoId"
        class="terminal-host"
        data-testid="terminal-host"
      ></div>
    </div>
  </div>
</template>
