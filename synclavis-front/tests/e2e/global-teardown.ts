import { readFileSync, realpathSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// 项目是 ESM（"type": "module"），不能使用 __dirname
const here = dirname(fileURLToPath(import.meta.url));

const BASE = process.env.SYNCLAVIS_BASE_URL ?? 'http://127.0.0.1:18080';

interface SessionTab {
  id: string;
  path: string;
  name: string;
}

interface SessionDto {
  activeId: string | null;
  tabs: SessionTab[];
}

interface RecentRepo {
  path: string;
  name: string;
  lastOpenedAt: string;
}

interface SettingsDto {
  recentRepos: RecentRepo[];
}

async function api<T>(method: string, path: string, body?: unknown): Promise<T | null> {
  try {
    const response = await fetch(`${BASE}${path}`, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) {
      return null;
    }
    const payload = (await response.json()) as { data: T };
    return payload.data;
  } catch {
    return null;
  }
}

/**
 * E2E 结束后清场。
 *
 * <p>测试会在**真实**后端会话里打开一个临时仓库（`os.tmpdir()/synclavis-e2e-*`）。
 * 不清理的话，每跑一次 E2E 都会在用户的 Tab 栏里留下一个指向已删除目录的死 Tab，
 * 并污染「最近打开」列表。</p>
 *
 * <p>只处理路径位于本次临时目录下的 Tab 与最近记录，绝不触碰用户自己的仓库。</p>
 */
export default async function globalTeardown(): Promise<void> {
  let tempBase: string;
  try {
    // 后端会用 realpath 规范化路径（macOS 上 /var → /private/var），
    // 这里必须同样规范化，否则前缀比对永远不成立。
    tempBase = realpathSync(dirname(readFileSync(join(here, '.repo-path'), 'utf8').trim()));
  } catch {
    return;
  }
  if (!tempBase.includes('synclavis-e2e-')) {
    // 兜底：路径不在预期的临时目录下就不动手
    return;
  }

  const session = await api<SessionDto>('GET', '/api/session');
  if (!session) {
    return;
  }

  for (const tab of session.tabs) {
    if (tab.path.startsWith(tempBase)) {
      await api('DELETE', `/api/repos/${tab.id}`);
    }
  }

  const settings = await api<SettingsDto>('GET', '/api/settings');
  if (settings) {
    const kept = settings.recentRepos.filter((repo) => !repo.path.startsWith(tempBase));
    if (kept.length !== settings.recentRepos.length) {
      await api('PUT', '/api/settings', { recentRepos: kept });
    }
  }

  // 若清理后仍有 Tab，把激活项指向第一个（避免 activeId 悬空）
  const after = await api<SessionDto>('GET', '/api/session');
  if (after) {
    const stillOpen = after.tabs.some((tab) => tab.id === after.activeId);
    if (!stillOpen && after.tabs.length > 0) {
      await api('PUT', '/api/session/active', { repoId: after.tabs[0].id });
    }
  }

  // 还原运行前的主题（E-03 会切换并持久化，不应改变用户的偏好）
  try {
    const theme = readFileSync(join(here, '.repo-theme'), 'utf8').trim();
    if (theme === 'dark' || theme === 'light') {
      await api('PUT', '/api/settings', { theme });
    }
  } catch {
    // 没有记录就跳过
  }
}
