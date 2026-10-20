<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { FitAddon } from '@xterm/addon-fit';
import { Terminal } from '@xterm/xterm';
import type { ITheme } from '@xterm/xterm';
import type { Disposable, PluginContext } from '@synclavis/plugin-api';
import { terminalApi, type TerminalSession } from './api';

const props = defineProps<{ context: PluginContext }>();

type TerminalStatus = 'idle' | 'connecting' | 'connected' | 'exited' | 'error';

interface ServerMessage {
  type: 'output' | 'exit' | 'error' | 'pong';
  data?: string;
  code?: number | string;
  message?: string;
}

const host = ref<HTMLElement | null>(null);
const status = ref<TerminalStatus>('idle');
const errorMessage = ref<string | null>(null);
const session = ref<TerminalSession | null>(null);

let term: Terminal | null = null;
let fitAddon: FitAddon | null = null;
let socket: WebSocket | null = null;
let repoId: string | null = null;
let currentRepoId: string | null = null;
let initialized = false;
let disposed = false;
let generation = 0;
let pendingInput = '';
let fitFrame = 0;
let resizeObserver: ResizeObserver | null = null;
let themeObserver: MutationObserver | null = null;
let repoSubscription: Disposable | null = null;

const MAX_PENDING_INPUT = 64 * 1024;
const INPUT_CHUNK = 8 * 1024;

const statusText = computed(() => {
  switch (status.value) {
    case 'connecting':
      return '连接中…';
    case 'connected':
      return '已连接';
    case 'exited':
      return '已退出';
    case 'error':
      return '连接失败';
    default:
      return repoId ? '空闲' : '未打开仓库';
  }
});

const shellLabel = computed(() => {
  const shell = session.value?.shell;
  if (!shell) {
    return 'shell';
  }
  const parts = shell.split(/[\\/]/);
  return parts[parts.length - 1] || shell;
});

const cwdLabel = computed(() => session.value?.cwd ?? '');

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

function createTerminal(): void {
  if (!host.value) {
    return;
  }
  term = new Terminal({
    allowProposedApi: false,
    cursorBlink: true,
    convertEol: false,
    fontFamily: 'Menlo, Monaco, "Cascadia Code", "SFMono-Regular", Consolas, monospace',
    fontSize: 12,
    lineHeight: 1.2,
    scrollback: 5000,
    theme: readTheme(),
  });
  fitAddon = new FitAddon();
  term.loadAddon(fitAddon);
  term.open(host.value);
  term.onData((data) => sendInput(data));
  term.onResize(() => sendResize());

  themeObserver = new MutationObserver(() => {
    if (term) {
      term.options.theme = readTheme();
    }
  });
  themeObserver.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-theme'],
  });

  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => scheduleFit());
    resizeObserver.observe(host.value);
  }

  scheduleFit();
}

function scheduleFit(): void {
  if (fitFrame !== 0) {
    return;
  }
  fitFrame = requestAnimationFrame(() => {
    fitFrame = 0;
    fit();
  });
}

function fit(): void {
  if (!fitAddon || !term) {
    return;
  }
  try {
    fitAddon.fit();
  } catch {
    // 面板折叠/切换时容器可能暂时没有尺寸，下一次 ResizeObserver 会再试
  }
}

function sendInputChunks(data: string): void {
  for (let offset = 0; offset < data.length; offset += INPUT_CHUNK) {
    const chunk = data.slice(offset, offset + INPUT_CHUNK);
    sendJson({ type: 'input', data: chunk });
  }
}

function sendInput(data: string): void {
  if (!data) {
    return;
  }
  if (socket && socket.readyState === WebSocket.OPEN) {
    sendInputChunks(data);
    return;
  }
  if (status.value === 'connecting') {
    pendingInput += data;
    if (pendingInput.length > MAX_PENDING_INPUT) {
      pendingInput = pendingInput.slice(-MAX_PENDING_INPUT);
    }
  }
}

function sendResize(): void {
  if (!term || !socket || socket.readyState !== WebSocket.OPEN) {
    return;
  }
  sendJson({
    type: 'resize',
    cols: Math.max(1, Math.min(500, term.cols)),
    rows: Math.max(1, Math.min(500, term.rows)),
  });
}

function sendJson(payload: Record<string, unknown>): void {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    return;
  }
  try {
    socket.send(JSON.stringify(payload));
  } catch {
    // onclose 会统一处理
  }
}

function closeSocket(): void {
  const current = socket;
  socket = null;
  pendingInput = '';
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

async function resetSession(deleteRemote: boolean): Promise<void> {
  generation += 1;
  closeSocket();
  const terminalId = session.value?.terminalId;
  session.value = null;
  status.value = 'idle';
  errorMessage.value = null;
  if (terminalId && deleteRemote) {
    void terminalApi.delete(terminalId).catch(() => {
      // 进程可能已自然退出，DELETE 404 可忽略
    });
  }
}

async function startSession(): Promise<void> {
  if (disposed || !term) {
    return;
  }
  if (!currentRepoId) {
    term.reset();
    term.writeln('请先打开一个仓库。');
    status.value = 'idle';
    return;
  }

  term.reset();
  status.value = 'connecting';
  errorMessage.value = null;
  fit();
  const gen = generation;
  const repo = currentRepoId;
  try {
    const created = await terminalApi.create(repo, term.cols || 80, term.rows || 24);
    if (disposed || gen !== generation || currentRepoId !== repo) {
      void terminalApi.delete(created.terminalId).catch(() => {});
      return;
    }
    session.value = created;
    connect(created.terminalId, gen, repo);
  } catch (error) {
    if (disposed || gen !== generation) {
      return;
    }
    status.value = 'error';
    errorMessage.value = error instanceof Error ? error.message : String(error);
    term.writeln(`\x1b[31m${errorMessage.value}\x1b[0m`);
  }
}

function connect(terminalId: string, gen: number, repo: string): void {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const url = `${protocol}//${window.location.host}/ws/terminal/sessions/${encodeURIComponent(terminalId)}`;
  const ws = new WebSocket(url);
  socket = ws;

  ws.onopen = () => {
    if (socket !== ws || gen !== generation) {
      return;
    }
    status.value = 'connected';
    if (pendingInput) {
      const buffered = pendingInput;
      pendingInput = '';
      sendInputChunks(buffered);
    }
    sendResize();
  };

  ws.onmessage = (event) => {
    if (socket !== ws || gen !== generation) {
      return;
    }
    let message: ServerMessage;
    try {
      message = JSON.parse(String(event.data)) as ServerMessage;
    } catch {
      return;
    }
    if (message.type === 'output') {
      term?.write(message.data ?? '');
      return;
    }
    if (message.type === 'exit') {
      const code = typeof message.code === 'number' ? message.code : -1;
      status.value = 'exited';
      session.value = null;
      term?.write(`\r\n\x1b[90m[进程已退出，退出码 ${code}]\x1b[0m\r\n`);
      return;
    }
    if (message.type === 'error') {
      status.value = 'error';
      errorMessage.value = message.message ?? '终端错误';
      term?.write(`\r\n\x1b[31m[${errorMessage.value}]\x1b[0m\r\n`);
    }
  };

  ws.onerror = () => {
    if (socket !== ws || gen !== generation) {
      return;
    }
    status.value = 'error';
    errorMessage.value = `终端连接失败（${repo}）`;
  };

  ws.onclose = () => {
    if (socket !== ws) {
      return;
    }
    socket = null;
    if (gen === generation && (status.value === 'connected' || status.value === 'connecting')) {
      status.value = 'error';
      errorMessage.value = '终端连接已断开';
    }
  };
}

async function switchRepo(next: string | null): Promise<void> {
  if (disposed) {
    return;
  }
  const normalized = next || null;
  if (initialized && normalized === currentRepoId) {
    return;
  }
  initialized = true;
  currentRepoId = normalized;
  repoId = normalized;
  await resetSession(true);
  await startSession();
}

async function restart(): Promise<void> {
  if (!currentRepoId) {
    return;
  }
  await resetSession(true);
  await startSession();
}

function clear(): void {
  term?.clear();
}

onMounted(async () => {
  await nextTick();
  createTerminal();
  repoSubscription = props.context.services.repos.onDidChangeActive((next) => {
    void switchRepo(next);
  });
  await switchRepo(props.context.services.repos.activeId());
});

onBeforeUnmount(() => {
  disposed = true;
  repoSubscription?.dispose();
  repoSubscription = null;
  if (fitFrame !== 0) {
    cancelAnimationFrame(fitFrame);
    fitFrame = 0;
  }
  resizeObserver?.disconnect();
  resizeObserver = null;
  themeObserver?.disconnect();
  themeObserver = null;
  void resetSession(true);
  term?.dispose();
  term = null;
  fitAddon = null;
});
</script>

<template>
  <div class="terminal-panel">
    <div class="terminal-toolbar">
      <span class="terminal-title">终端</span>
      <span class="terminal-meta" :title="cwdLabel">{{ shellLabel }}</span>
      <span class="terminal-meta terminal-cwd" :title="cwdLabel">{{ cwdLabel }}</span>
      <span class="terminal-status" :data-status="status">{{ statusText }}</span>
      <button type="button" class="terminal-button" :disabled="!repoId" @click="restart">
        重启
      </button>
      <button type="button" class="terminal-button" @click="clear">清空</button>
    </div>
    <p v-if="errorMessage" class="terminal-error" data-testid="terminal-error">
      {{ errorMessage }}
    </p>
    <div ref="host" class="terminal-host" data-testid="terminal-host"></div>
  </div>
</template>
