import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';
import { HISTORY_COMMITS } from './global-setup';

const here = dirname(fileURLToPath(import.meta.url));
const repoPath = readFileSync(join(here, '.repo-path'), 'utf8').trim();

/** 仓库总提交数：init + HISTORY_COMMITS */
const TOTAL_COMMITS = HISTORY_COMMITS + 1;
/** 最新一条提交的标题 */
const NEWEST_SUBJECT = `history-${String(HISTORY_COMMITS).padStart(3, '0')}`;

/**
 * 打开应用并确保 E2E 仓库处于打开且已加载完成。
 *
 * 不再依赖「当前没有仓库才去打开」的推断：后端会话可能残留上一次运行、指向已删除的
 * 临时目录，那种情况下工作区会一直停在错误态。`POST /api/repos/open` 对已打开仓库
 * 是幂等的（聚焦并返回同一 Tab），因此这里无条件走一次对话框更稳。
 */
async function ensureRepoOpen(page: Page): Promise<void> {
  await page.goto('/');

  const openButton = page.getByRole('button', { name: '打开仓库' }).first();
  await expect(openButton).toBeVisible();
  await openButton.click();

  const dialog = page.locator('.n-modal');
  await dialog.getByPlaceholder('/Users/me/projects/my-repo').fill(repoPath);
  await dialog.getByRole('button', { name: '打开', exact: true }).click();
  // 必须等遮罩退场：残余的 .n-modal-container 会吃掉后续 page.mouse 的 pointerdown
  await expect(dialog).toBeHidden();

  // 就绪信号：提交总数已知，且最新提交已渲染（虚拟滚动后「最老的 init」不一定在 DOM 里）
  await expect(page.getByText(`共 ${TOTAL_COMMITS} 个提交`)).toBeVisible();
  await expect(page.getByTestId('commit-row').filter({ hasText: NEWEST_SUBJECT })).toBeVisible();
}

/** 读取影响「提交流是否可见」的关键布局指标 */
async function layoutMetrics(page: Page) {
  return page.evaluate(() => {
    const panel = document.querySelector('[data-testid="commit-panel"]');
    const scroller = document.querySelector('[data-testid="commit-list"]');
    const rows = document.querySelectorAll('[data-testid="commit-row"]');
    const commitButton = [...document.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === 'Commit',
    );
    const panelRect = panel?.getBoundingClientRect() ?? null;
    const buttonRect = commitButton?.getBoundingClientRect() ?? null;
    return {
      innerHeight: window.innerHeight,
      docScrollHeight: document.documentElement.scrollHeight,
      pageScrolls: document.documentElement.scrollHeight > window.innerHeight + 1,
      panelTop: panelRect ? Math.round(panelRect.top) : -1,
      panelBottom: panelRect ? Math.round(panelRect.bottom) : -1,
      panelHeight: panelRect ? Math.round(panelRect.height) : -1,
      commitButtonInViewport:
        !!buttonRect && buttonRect.top >= 0 && buttonRect.bottom <= window.innerHeight,
      listClientHeight: scroller ? (scroller as HTMLElement).clientHeight : -1,
      listScrollHeight: scroller ? (scroller as HTMLElement).scrollHeight : -1,
      domRows: rows.length,
    };
  });
}

/**
 * 弹层（Naive UI 的 `.n-modal-container`）在关闭动画期间仍有尺寸并会吃掉 pointerdown。
 * 这里等它彻底退场，而不是只看 `.n-modal` 是否隐藏。
 */
async function waitForNoOverlay(page: Page): Promise<void> {
  await expect
    .poll(() =>
      page.evaluate(() => {
        const container = document.querySelector('.n-modal-container');
        if (!container) {
          return 0;
        }
        const rect = container.getBoundingClientRect();
        return Math.round(rect.width) + Math.round(rect.height);
      }),
    )
    .toBe(0);
}

async function dragHandle(page: Page, label: string, dy: number): Promise<void> {
  const handle = page.getByRole('separator', { name: label });
  await expect(handle).toBeVisible();
  await waitForNoOverlay(page);

  const box = await handle.boundingBox();
  if (!box) {
    throw new Error(`找不到分隔条：${label}`);
  }
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;

  // page.mouse 不做可操作性检查：先确认该坐标确实命中分隔条，避免“拖了但没生效”的假通过
  const hit = await page.evaluate(
    (point) => {
      const el = document.elementFromPoint(point.x, point.y);
      return el?.closest('[role="separator"]')?.getAttribute('aria-label') ?? null;
    },
    { x, y },
  );
  expect(hit, `坐标 (${Math.round(x)}, ${Math.round(y)}) 未命中分隔条`).toBe(label);

  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x, y + dy, { steps: 8 });
  await page.mouse.up();
}

async function backendLayout(page: Page) {
  const response = await page.request.get('/api/settings');
  const body = (await response.json()) as { data: { layout: Record<string, number> } };
  return body.data.layout;
}

test('E-01 打开仓库后出现 Tab、快捷操作栏与三栏工作区', async ({ page }) => {
  await ensureRepoOpen(page);

  // Tab 标题含仓库名
  await expect(page.locator('text=e2e-repo').first()).toBeVisible();

  // 需求：至少包括 Fetch / Pull / Push / Stash
  await expect(page.locator('button:has-text("Fetch")').first()).toBeVisible();
  await expect(page.locator('button:has-text("Pull")').first()).toBeVisible();
  await expect(page.locator('button:has-text("Push")').first()).toBeVisible();
  await expect(page.locator('button:has-text("Stash")').first()).toBeVisible();

  // 左侧边栏（分支）与提交历史
  await expect(page.locator('text=本地分支')).toBeVisible();
  await expect(page.getByText(`共 ${TOTAL_COMMITS} 个提交`)).toBeVisible();
  await expect(page.getByTestId('commit-row').filter({ hasText: NEWEST_SUBJECT })).toBeVisible();
});

test('E-02 选择提交后展示文件变更与 Diff', async ({ page }) => {
  await ensureRepoOpen(page);

  await page.getByTestId('commit-row').filter({ hasText: NEWEST_SUBJECT }).click();

  await expect(page.locator('text=个文件变更')).toBeVisible();
  await expect(page.locator('text=README.md').first()).toBeVisible();
});

test('E-03 主题切换并持久化（后端为准）', async ({ page }) => {
  await ensureRepoOpen(page);

  const theme = await page.evaluate(() => document.documentElement.getAttribute('data-theme'));
  await page.locator('button[title*="主题"]').first().click();
  await expect
    .poll(() => page.evaluate(() => document.documentElement.getAttribute('data-theme')))
    .not.toBe(theme);

  const toggled = await page.evaluate(() => document.documentElement.getAttribute('data-theme'));
  await page.reload();
  await expect
    .poll(() => page.evaluate(() => document.documentElement.getAttribute('data-theme')))
    .toBe(toggled);
});

test('E-04 刷新后会话恢复（后端持久化 Tab）', async ({ page }) => {
  await ensureRepoOpen(page);

  await page.reload();

  await expect(page.locator('text=e2e-repo').first()).toBeVisible();
  await expect(page.locator('button:has-text("Fetch")').first()).toBeVisible();
});

test('E-05 Fetch 触发操作并出现操作日志', async ({ page }) => {
  await ensureRepoOpen(page);

  await page.locator('button:has-text("操作日志")').first().click();
  await page.locator('button:has-text("Fetch")').first().click();

  await expect(page.locator('text=开始 fetch').first()).toBeVisible();
});

test('E-06 仓库设置对话框可打开并回显 SSH 私钥路径', async ({ page }) => {
  await ensureRepoOpen(page);

  await page.locator('button[title*="仓库设置"]').first().click();
  await expect(page.locator('text=SSH 私钥路径')).toBeVisible();
  await expect(page.getByPlaceholder('留空使用默认 ~/.ssh/id_rsa')).toBeVisible();
  await page.locator('.n-modal').getByRole('button', { name: '取消', exact: true }).click();
});

/**
 * E-07：本次缺陷的回归闸门。
 *
 * 回归前：Naive UI 的 .n-config-provider 截断高度链，提交列表不内部滚动而把整页撑到
 * 5000px+，提交区被推到视口之外（实测 Commit 按钮 y=5039 / 视口 800）。
 */
test('E-07 长提交历史下提交流常驻视口且列表内部滚动', async ({ page }) => {
  await ensureRepoOpen(page);

  const initial = await layoutMetrics(page);
  expect(initial.pageScrolls, '整页不应出现滚动条').toBe(false);
  expect(initial.panelBottom).toBeLessThanOrEqual(initial.innerHeight);
  expect(initial.panelTop).toBeGreaterThanOrEqual(0);
  expect(initial.commitButtonInViewport, 'Commit 按钮必须可见').toBe(true);
  expect(initial.listScrollHeight).toBeGreaterThan(initial.listClientHeight);

  // 虚拟滚动：DOM 行数必须显著少于总提交数，且窗口不随历史增长
  expect(initial.domRows).toBeLessThan(TOTAL_COMMITS);
  expect(initial.domRows).toBeLessThanOrEqual(40);
  expect(initial.domRows).toBeGreaterThan(0);

  // 滚到提交列表底部：最后一屏仍能渲染出来，且整页高度不变
  await page.evaluate(() => {
    const list = document.querySelector('[data-testid="commit-list"]') as HTMLElement | null;
    if (list) {
      list.scrollTop = list.scrollHeight;
    }
  });
  await expect(page.getByTestId('commit-row').filter({ hasText: 'init' })).toBeVisible();

  const scrolled = await layoutMetrics(page);
  expect(scrolled.pageScrolls).toBe(false);
  expect(scrolled.commitButtonInViewport).toBe(true);
  expect(scrolled.panelBottom).toBeLessThanOrEqual(scrolled.innerHeight);
  expect(scrolled.domRows).toBeLessThanOrEqual(40);
});

/** E-08：提交区（暂存文件 + 提交信息）可拖拽调高，尺寸由后端持久化 */
test('E-08 提交区拖拽调高并持久化到后端', async ({ page }) => {
  await ensureRepoOpen(page);

  const before = await layoutMetrics(page);
  await dragHandle(page, '调整提交区高度', -80);
  await expect
    .poll(async () => (await layoutMetrics(page)).panelHeight)
    .toBeGreaterThan(before.panelHeight + 60);

  const after = await layoutMetrics(page);
  // 后端为准：不只看 localStorage 缓存
  await expect
    .poll(async () => (await backendLayout(page)).commitPanelHeight)
    .toBeGreaterThanOrEqual(after.panelHeight - 2);
  await expect
    .poll(async () => (await backendLayout(page)).commitPanelHeight)
    .toBeLessThanOrEqual(after.panelHeight + 2);

  // 刷新后仍然保持
  await page.reload();
  await expect(page.getByTestId('commit-panel')).toBeVisible();
  const reloaded = await layoutMetrics(page);
  expect(Math.abs(reloaded.panelHeight - after.panelHeight)).toBeLessThanOrEqual(2);

  // 复原，避免影响后续用例
  await dragHandle(page, '调整提交区高度', 80);
  await expect
    .poll(async () => (await layoutMetrics(page)).panelHeight)
    .toBeLessThanOrEqual(before.panelHeight + 2);
});
