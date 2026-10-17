import { readFileSync, realpathSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';
import { COMMIT_MESSAGE_MIN_WIDTH, DEFAULT_LAYOUT, DIFF_MIN_HEIGHT } from '../../src/utils/layout';
import {
  HISTORY_COMMITS,
  LONG_LINE_FILE,
  LONG_PATH_FILE,
  STAGED_PATCH_LINE,
  TRACKED_FILE,
  UNTRACKED_CONTENT,
  UNTRACKED_DIR,
  UNTRACKED_FILE,
  WORKING_PATCH_LINE,
} from './global-setup';

const here = dirname(fileURLToPath(import.meta.url));
const repoPath = readFileSync(join(here, '.repo-path'), 'utf8').trim();
/** 后端会把路径 canonicalize（macOS 上 /var → /private/var），比较时用真实路径 */
const repoRealPath = realpathSync(repoPath);

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
    const sidebarRect = document.querySelector('[data-testid="repo-sidebar"]')?.getBoundingClientRect() ?? null;
    const columnRect = document.querySelector('[data-testid="commit-column"]')?.getBoundingClientRect() ?? null;
    return {
      sidebarWidth: sidebarRect ? Math.round(sidebarRect.width) : -1,
      commitListWidth: columnRect ? Math.round(columnRect.width) : -1,
      panelWidth: panelRect ? Math.round(panelRect.width) : -1,
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

async function dragHandle(page: Page, label: string, dx: number, dy: number): Promise<void> {
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
  await page.mouse.move(x + dx, y + dy, { steps: 8 });
  await page.mouse.up();
}

async function backendLayout(page: Page) {
  const response = await page.request.get('/api/settings');
  const body = (await response.json()) as { data: { layout: Record<string, number> } };
  return body.data.layout;
}

/**
 * 把布局尺寸重置到已知基线。
 *
 * <p>分隔条类用例必须先固定起点：上一轮若把尺寸留在接近上限的位置，
 * 本轮的拖拽量会被钳制、断言随之失效（E-11 就这样翻过车）。
 * 用接口写基线而不是「拖回去」，同样是为了避免钳制带来的残留。</p>
 */
async function resetLayout(
  page: Page,
  patch: Partial<Record<keyof typeof DEFAULT_LAYOUT, number>>,
): Promise<void> {
  await page.request.put('/api/settings', { data: { layout: patch } });
  await page.reload();
  await expect(page.getByTestId('commit-panel')).toBeVisible();
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
  // 原生滚动条/控件跟随主题：color-scheme 必须与 data-theme 一致
  await expect
    .poll(() => page.evaluate(() => getComputedStyle(document.documentElement).colorScheme))
    .toBe(toggled === 'light' ? 'light' : 'dark');

  // 滚动条统一为「细 + 主题色滑块 + 透明轨道」，横向/纵向、diff/列表一致
  const scrollbar = await page.evaluate(() => {
    const cs = getComputedStyle(document.documentElement);
    return { width: cs.scrollbarWidth, color: cs.scrollbarColor };
  });
  expect(scrollbar.width).toBe('thin');
  expect(scrollbar.color).not.toBe('auto');
  // `transparent` 的计算值是 rgba(0, 0, 0, 0)
  expect(scrollbar.color).toContain('rgba(0, 0, 0, 0)');

  await page.reload();
  await expect
    .poll(() => page.evaluate(() => document.documentElement.getAttribute('data-theme')))
    .toBe(toggled);
  await expect
    .poll(() => page.evaluate(() => getComputedStyle(document.documentElement).colorScheme))
    .toBe(toggled === 'light' ? 'light' : 'dark');
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
  await expect(page.getByTestId('settings-key-browse')).toBeVisible();
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

  await resetLayout(page, { commitPanelHeight: DEFAULT_LAYOUT.commitPanelHeight });
  const before = await layoutMetrics(page);
  expect(before.panelHeight).toBe(DEFAULT_LAYOUT.commitPanelHeight);

  await dragHandle(page, '调整提交区高度', 0, -80);
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
  expect(Math.abs((await layoutMetrics(page)).panelHeight - after.panelHeight)).toBeLessThanOrEqual(2);

  // 一路向上拖到上限：Diff 区必须还剩 DIFF_MIN_HEIGHT，而不是被压光
  // （回归：保底曾是 120px，偏高）
  await dragHandle(page, '调整提交区高度', 0, -2000);
  const maxedPanel = await layoutMetrics(page);
  const diffHeight = Math.round(
    (await page.getByTestId('diff-viewer').boundingBox())?.height ?? -1,
  );
  expect(maxedPanel.panelHeight).toBeGreaterThan(after.panelHeight);
  expect(diffHeight).toBeGreaterThanOrEqual(DIFF_MIN_HEIGHT - 5);
  expect(diffHeight).toBeLessThanOrEqual(DIFF_MIN_HEIGHT + 5);
  // 绝对闸门（不引用被测常量）：Diff 区确实能被压到 100px 以下，
  // 说明保底不再是旧的 120px
  expect(diffHeight).toBeLessThan(100);
  expect(maxedPanel.panelBottom).toBeLessThanOrEqual(maxedPanel.innerHeight);
  expect(maxedPanel.pageScrolls).toBe(false);

  // 复原基线，避免影响后续用例
  await resetLayout(page, { commitPanelHeight: DEFAULT_LAYOUT.commitPanelHeight });
});

/** E-09：提交列表列宽可拖拽并持久化；左侧栏最小宽度已降为 120px */
test('E-09 提交列表列宽可拖拽、左侧栏最小宽度为 120px', async ({ page }) => {
  await ensureRepoOpen(page);

  await resetLayout(page, {
    sidebarWidth: DEFAULT_LAYOUT.sidebarWidth,
    commitListWidth: DEFAULT_LAYOUT.commitListWidth,
  });
  const before = await layoutMetrics(page);
  expect(before.commitListWidth).toBe(DEFAULT_LAYOUT.commitListWidth);
  expect(before.sidebarWidth).toBe(DEFAULT_LAYOUT.sidebarWidth);

  // 1) 提交列表列宽：向右拖 100px → 变宽并持久化
  await dragHandle(page, '调整提交列表宽度', 100, 0);
  await expect
    .poll(async () => (await layoutMetrics(page)).commitListWidth)
    .toBeGreaterThan(before.commitListWidth + 80);

  const widened = await layoutMetrics(page);
  await expect
    .poll(async () => (await backendLayout(page)).commitListWidth)
    .toBeGreaterThanOrEqual(widened.commitListWidth - 2);
  await expect
    .poll(async () => (await backendLayout(page)).commitListWidth)
    .toBeLessThanOrEqual(widened.commitListWidth + 2);

  // 拖宽提交列表后，右侧 Diff/提交区必须还留有可用宽度（动态上限保护）
  expect(widened.panelWidth).toBeGreaterThan(200);
  expect(widened.pageScrolls).toBe(false);

  await page.reload();
  await expect(page.getByTestId('commit-panel')).toBeVisible();
  expect(Math.abs((await layoutMetrics(page)).commitListWidth - widened.commitListWidth)).toBeLessThanOrEqual(2);

  // 2) 左侧栏最小宽度：向左猛拖 → 钳在 120（回归：曾为 180）
  await dragHandle(page, '调整左侧栏宽度', -600, 0);
  await expect.poll(async () => (await layoutMetrics(page)).sidebarWidth).toBe(120);
  await expect.poll(async () => (await backendLayout(page)).sidebarWidth).toBe(120);

  // 3) 复原基线
  await resetLayout(page, {
    sidebarWidth: DEFAULT_LAYOUT.sidebarWidth,
    commitListWidth: DEFAULT_LAYOUT.commitListWidth,
  });
  const restored = await layoutMetrics(page);
  expect(restored.sidebarWidth).toBe(DEFAULT_LAYOUT.sidebarWidth);
  expect(restored.commitListWidth).toBe(DEFAULT_LAYOUT.commitListWidth);
});

/**
 * E-10：点击暂存区/未暂存/未跟踪文件，在右侧查看它改了哪些内容。
 *
 * 覆盖三种状态 + 「暂存后该文件换分区」+「点 +/- 按钮不应改变当前查看的文件」+「切回提交来源」。
 */
test('E-10 暂存区文件可点击查看差异，暂存后差异跟着切换', async ({ page }) => {
  await ensureRepoOpen(page);

  const panel = page.getByTestId('commit-panel');
  const header = page.getByTestId('diff-header');
  const patch = page.getByTestId('diff-patch');
  // 选中态用 data-selected 暴露，避免与行上的 hover:bg-bg-hover 混淆
  const selectedRows = panel.locator('li[data-selected="true"]');

  // 默认停在「变更」tab（已暂存/未暂存同屏）
  await expect(page.getByTestId('commit-tab-changes')).toHaveAttribute('aria-selected', 'true');

  // 1) 已暂存文件：HEAD ↔ 索引
  await panel.locator('li', { hasText: TRACKED_FILE }).first().click();
  await expect(header).toContainText('已暂存');
  await expect(header).toContainText(TRACKED_FILE);
  await expect(patch).toContainText(STAGED_PATCH_LINE);
  await expect(patch).not.toContainText(WORKING_PATCH_LINE);
  await expect(selectedRows).toHaveCount(1);

  // 2) 未暂存文件：索引 ↔ 工作区
  await panel.locator('li', { hasText: 'README.md' }).first().click();
  await expect(header).toContainText('未暂存');
  await expect(header).toContainText('README.md');
  await expect(patch).toContainText(WORKING_PATCH_LINE);
  await expect(patch).not.toContainText(STAGED_PATCH_LINE);

  // 3) 未跟踪文件在单独的「未跟踪」tab：按 ADD 呈现，patch 含文件内容
  await page.getByTestId('commit-tab-untracked').click();
  await panel.locator('li', { hasText: UNTRACKED_FILE }).first().click();
  await expect(header).toContainText('未暂存');
  await expect(header).toContainText(UNTRACKED_FILE);
  await expect(header).toContainText('ADD');
  await expect(patch).toContainText(UNTRACKED_CONTENT);

  // 4) 回「变更」tab，点 README.md 行上的「+」：
  //    click.stop 保证不改变当前查看的文件（仍是 notes.txt）
  await page.getByTestId('commit-tab-changes').click();
  const unstagedReadme = panel.locator('li', { hasText: 'README.md' }).first();
  await unstagedReadme.hover();
  await unstagedReadme.locator('button[title="暂存"]').click();
  await expect(header).toContainText(UNTRACKED_FILE);
  await expect(patch).toContainText(UNTRACKED_CONTENT);

  // 暂存后 README.md 只剩「已暂存」一行；点它应看到 HEAD↔索引 的内容
  await expect
    .poll(async () => panel.locator('li', { hasText: 'README.md' }).count())
    .toBe(1);
  await panel.locator('li', { hasText: 'README.md' }).first().click();
  await expect(header).toContainText('已暂存');
  await expect(patch).toContainText(WORKING_PATCH_LINE);
  await expect(patch).not.toContainText(STAGED_PATCH_LINE);

  // 5) 切回提交来源：工作区文件高亮与差异都让位给提交
  await page.getByTestId('commit-row').first().click();
  await expect(header).toContainText('提交');
  await expect(page.locator('text=个文件变更')).toBeVisible();
  await expect(selectedRows).toHaveCount(0);
});

/** E-11：暂存区文件列表的右边界（列表 ↔ 提交信息框）可拖拽并持久化 */
test('E-11 暂存区文件列表宽度可拖拽并持久化', async ({ page }) => {
  // 用宽窗口：只有在右列足够宽时，「拖到上限仍比提交信息框窄」才会复现
  // （窄窗口下动态上限本来就小于静态上限，旧缺陷被掩盖）
  await page.setViewportSize({ width: 1920, height: 800 });
  await ensureRepoOpen(page);

  const list = page.getByTestId('commit-file-list');
  // 保底宽度约束的是「提交信息区」这个容器；textarea 还要再扣掉 padding 与组件内边距
  const messageArea = page.getByTestId('commit-message-area');
  const width = async (): Promise<number> => Math.round((await list.boundingBox())?.width ?? -1);

  const resetBaseline = async (): Promise<void> => {
    await page.request.put('/api/settings', {
      data: { layout: { commitFileListWidth: DEFAULT_LAYOUT.commitFileListWidth } },
    });
    await page.reload();
    await expect(page.getByTestId('commit-panel')).toBeVisible();
  };

  // 基线固定：上一轮若把宽度留在上限附近，「+90」会被钳住，断言就不成立了
  await resetBaseline();
  const before = await width();
  expect(before).toBe(DEFAULT_LAYOUT.commitFileListWidth);

  // 1) 向右拖 90px → 列表变宽，提交信息框仍有可用宽度
  await dragHandle(page, '调整暂存区文件列表宽度', 90, 0);
  await expect.poll(width).toBeGreaterThan(before + 70);
  const after = await width();
  expect(Math.round((await messageArea.boundingBox())?.width ?? 0)).toBeGreaterThan(100);
  expect((await layoutMetrics(page)).pageScrolls).toBe(false);

  // 2) 后端为准
  await expect
    .poll(async () => (await backendLayout(page)).commitFileListWidth)
    .toBeGreaterThanOrEqual(after - 2);
  await expect
    .poll(async () => (await backendLayout(page)).commitFileListWidth)
    .toBeLessThanOrEqual(after + 2);

  // 3) 刷新后保持
  await page.reload();
  await expect(page.getByTestId('commit-panel')).toBeVisible();
  expect(Math.abs((await width()) - after)).toBeLessThanOrEqual(2);

  // 4) 一路向右拖到上限：列表必须能比提交信息框更宽，且信息框仍可用
  //    （回归：静态上限 480 在宽窗口下会把列表卡住，永远比信息框窄）
  await dragHandle(page, '调整暂存区文件列表宽度', 2000, 0);
  const maxedList = await width();
  const maxedBox = Math.round((await messageArea.boundingBox())?.width ?? 0);
  expect(maxedList).toBeGreaterThan(after);
  expect(maxedList).toBeGreaterThan(maxedBox);
  expect(maxedBox).toBeGreaterThanOrEqual(COMMIT_MESSAGE_MIN_WIDTH - 5);
  expect((await layoutMetrics(page)).pageScrolls).toBe(false);

  // 5) 用 API 直接写回默认值复原（避免差值拖拽在上限附近被钳住而留下残留）
  await resetBaseline();
  expect(await width()).toBe(DEFAULT_LAYOUT.commitFileListWidth);
});

/**
 * E-12：暂存区树形展示；暂存/取消暂存整个文件夹 = 其下所有文件。
 *
 * 覆盖：默认扁平、「未跟踪」独立 tab、切树形后的目录节点与计数、整目录暂存/取消暂存、
 * 以及展示偏好的 localStorage 持久化。
 */
test('E-12 暂存区树形展示与整目录暂存', async ({ page }) => {
  await ensureRepoOpen(page);

  const panel = page.getByTestId('commit-panel');
  const toggle = page.getByTestId('commit-file-view-toggle');

  // 基线：清掉偏好并刷新，确认默认是扁平列表
  await page.evaluate(() => localStorage.removeItem('synclavis.commitFileView'));
  await page.reload();
  await expect(panel).toBeVisible();

  // 「未跟踪」是独立 tab：扁平模式下逐文件一行，没有目录节点
  await page.getByTestId('commit-tab-untracked').click();
  const untrackedList = page.getByTestId('commit-file-list-untracked');
  await expect(untrackedList).toHaveAttribute('data-section', 'untracked');
  await expect(untrackedList.locator('li[data-node-type="dir"]')).toHaveCount(0);
  await expect(untrackedList.locator(`li[data-path="${UNTRACKED_DIR}/deep/two.txt"]`)).toBeVisible();

  // 切树形：nest 目录节点出现，计数为 2（one.txt + deep/two.txt）
  await toggle.click();
  const nestDir = untrackedList.locator(`li[data-path="${UNTRACKED_DIR}"]`);
  await expect(nestDir).toBeVisible();
  await expect(nestDir).toContainText('2');
  await expect(untrackedList.locator(`li[data-path="${UNTRACKED_DIR}/deep"]`)).toBeVisible();

  // 整目录暂存：nest 下所有文件离开「未跟踪」
  await nestDir.hover();
  await nestDir.locator('button[title="暂存整个目录"]').click();
  await expect
    .poll(async () => untrackedList.locator(`li[data-path^="${UNTRACKED_DIR}/"]`).count())
    .toBe(0);

  // 「变更」tab 的已暂存块出现 nest（树形，计数 2）
  await page.getByTestId('commit-tab-changes').click();
  const stagedList = page.getByTestId('commit-file-list-staged');
  const stagedNest = stagedList.locator(`li[data-path="${UNTRACKED_DIR}"]`);
  await expect(stagedNest).toBeVisible();
  await expect(stagedNest).toContainText('2');

  // 整目录取消暂存：全部退回「未跟踪」
  await stagedNest.hover();
  await stagedNest.locator('button[title="取消暂存整个目录"]').click();
  await expect.poll(async () => stagedList.locator(`li[data-path="${UNTRACKED_DIR}"]`).count()).toBe(0);
  await page.getByTestId('commit-tab-untracked').click();
  await expect(untrackedList.locator(`li[data-path="${UNTRACKED_DIR}"]`)).toBeVisible();

  // 偏好持久化：刷新后仍是树形
  await page.reload();
  await expect(panel).toBeVisible();
  await page.getByTestId('commit-tab-untracked').click();
  await expect(
    page.getByTestId('commit-file-list-untracked').locator(`li[data-path="${UNTRACKED_DIR}"]`),
  ).toBeVisible();

  // 复原为列表，避免影响其它用例
  await toggle.click();
  await expect(page.getByTestId('commit-file-list-untracked').locator('li[data-node-type="dir"]')).toHaveCount(0);
});

/**
 * E-13：超长文件路径不能把行内操作按钮或 Commit/Amend 按钮挤出视口。
 *
 * 回归：`truncate` 用在 flex 子项上时必须配 `min-w-0`，否则长路径会把行内
 * `ml-auto` 的暂存按钮顶到裁剪区域外（macOS 覆盖式滚动条又不显眼）；
 * 提交信息区也必须把按钮钉住，不能被长内容/矮面板挤出去。
 */
test('E-13 超长路径不撑破布局，行内与提交按钮仍可见可点', async ({ page }) => {
  await ensureRepoOpen(page);

  await page.getByTestId('commit-tab-untracked').click();
  const list = page.getByTestId('commit-file-list-untracked');
  const row = list.locator(`li[data-path="${LONG_PATH_FILE}"]`);
  await expect(row).toBeVisible();

  // 1) 行内「暂存」按钮必须留在列表容器内（长路径只截断，不把按钮顶出去）
  const addButton = row.locator('button[title="暂存"]');
  await expect(addButton).toBeVisible();
  const listBox = await list.boundingBox();
  const addBox = await addButton.boundingBox();
  expect(listBox).not.toBeNull();
  expect(addBox).not.toBeNull();
  expect(addBox!.x).toBeGreaterThanOrEqual(listBox!.x - 1);
  expect(addBox!.x + addBox!.width).toBeLessThanOrEqual(listBox!.x + listBox!.width + 1);

  // 2) 查看该文件 diff：头部路径同样截断，Commit/Amend 完整落在视口内
  await row.click();
  await expect(page.getByTestId('diff-header')).toContainText(LONG_PATH_FILE);
  const viewport = page.viewportSize();
  expect(viewport).not.toBeNull();
  for (const name of ['Commit', 'Amend']) {
    const box = await page.getByRole('button', { name, exact: true }).boundingBox();
    expect(box, `${name} 按钮不可见`).not.toBeNull();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(viewport!.width + 1);
    expect(box!.y).toBeGreaterThanOrEqual(0);
    expect(box!.y + box!.height).toBeLessThanOrEqual(viewport!.height + 1);
  }
  expect((await layoutMetrics(page)).pageScrolls).toBe(false);

  // 3) 超长 diff 行：只能横向滚动，不能把右列/提交按钮撑出视口
  //    （回归：RepoWorkspace 根节点缺 min-w-0 时，长行会把整行撑到 2069px）
  const longLineRow = list.locator(`li[data-path="${LONG_LINE_FILE}"]`);
  await expect(longLineRow).toBeVisible();
  await longLineRow.click();
  await expect(page.getByTestId('diff-header')).toContainText(LONG_LINE_FILE);

  const widened = await page.getByTestId('commit-panel').boundingBox();
  expect(widened).not.toBeNull();
  expect(widened!.x + widened!.width).toBeLessThanOrEqual(viewport!.width + 1);
  for (const name of ['Commit', 'Amend']) {
    const box = await page.getByRole('button', { name, exact: true }).boundingBox();
    expect(box, `${name} 按钮被长行撑出视口`).not.toBeNull();
    expect(box!.x + box!.width).toBeLessThanOrEqual(viewport!.width + 1);
  }
  const patchMetrics = await page.getByTestId('diff-patch').evaluate((el) => {
    const scroller = el.parentElement;
    return scroller ? { clientW: scroller.clientWidth, scrollW: scroller.scrollWidth } : null;
  });
  expect(patchMetrics).not.toBeNull();
  expect(patchMetrics!.scrollW).toBeGreaterThan(patchMetrics!.clientW + 100);

  // 4) 行内按钮可正常点击（说明既可见也未被遮住）
  await addButton.click();
  await expect
    .poll(async () => list.locator(`li[data-path="${LONG_PATH_FILE}"]`).count())
    .toBe(0);

  // 复原：从「已暂存」取消暂存，避免污染其它用例与重复运行
  await page.getByTestId('commit-tab-changes').click();
  const stagedList = page.getByTestId('commit-file-list-staged');
  const stagedRow = stagedList.locator(`li[data-path="${LONG_PATH_FILE}"]`);
  await expect(stagedRow).toBeVisible();
  await stagedRow.hover();
  await stagedRow.locator('button[title="取消暂存"]').click();
  await expect
    .poll(async () => stagedList.locator(`li[data-path="${LONG_PATH_FILE}"]`).count())
    .toBe(0);

  // 5) 提交区压到最小高度 + 多行提交信息：按钮行 sticky，仍不能被挤到面板外
  await page.request.put('/api/settings', {
    data: { layout: { commitPanelHeight: 120 } },
  });
  await page.reload();
  const panel = page.getByTestId('commit-panel');
  await expect(panel).toBeVisible();
  await page.getByPlaceholder('提交信息（Ctrl+Enter 提交）').fill('line1\nline2\nline3\nline4');

  const panelBox = await panel.boundingBox();
  expect(panelBox).not.toBeNull();
  for (const name of ['Commit', 'Amend']) {
    const box = await page.getByRole('button', { name, exact: true }).boundingBox();
    expect(box, `${name} 按钮被挤出提交区`).not.toBeNull();
    expect(box!.y).toBeGreaterThanOrEqual(panelBox!.y - 1);
    expect(box!.y + box!.height).toBeLessThanOrEqual(panelBox!.y + panelBox!.height + 1);
  }

  // 复原布局基线，避免影响重复运行
  await page.request.put('/api/settings', {
    data: { layout: { commitPanelHeight: DEFAULT_LAYOUT.commitPanelHeight } },
  });
  await page.reload();
});

test('E-14 打开仓库可浏览选择目录，隐藏项默认隐藏', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: '打开仓库' }).first().click();

  const openDialog = page.locator('.n-modal').filter({ hasText: '打开仓库' });
  await expect(openDialog).toBeVisible();
  await expect(openDialog.getByTestId('open-repo-key-browse')).toBeVisible();
  await openDialog.getByTestId('open-repo-browse').click();

  const picker = page.locator('.n-modal').filter({ hasText: '选择目录' });
  await expect(picker).toBeVisible();

  const pathInput = picker.getByPlaceholder('输入绝对路径后回车');
  await pathInput.fill(dirname(repoRealPath));
  await pathInput.press('Enter');

  const repoEntry = picker
    .locator('[data-testid="path-picker-entry"]')
    .filter({ hasText: basename(repoRealPath) });
  await expect(repoEntry).toBeVisible();
  await expect(repoEntry.getByText('仓库', { exact: true })).toBeVisible();

  // 单击目录即进入
  await repoEntry.click();
  await expect(pathInput).toHaveValue(repoRealPath);

  // 隐藏项默认不显示，开关后出现 .git
  const hiddenEntry = picker
    .locator('[data-testid="path-picker-entry"]')
    .filter({ hasText: '.git' });
  await expect(hiddenEntry).toHaveCount(0);
  await picker.getByTestId('path-picker-show-hidden').click();
  await expect(hiddenEntry).toBeVisible();

  // 选择当前目录 → 回填打开仓库的路径输入框
  await picker.getByTestId('path-picker-choose').click();
  await expect(picker).toBeHidden();
  await expect(openDialog.getByPlaceholder('/Users/me/projects/my-repo')).toHaveValue(repoRealPath);
  await openDialog.getByRole('button', { name: '取消', exact: true }).click();

  // 克隆目标路径与 SSH 私钥也提供浏览入口
  await page.getByRole('button', { name: '打开仓库' }).first().click();
  await openDialog.getByRole('button', { name: '克隆远程仓库' }).click();
  await expect(openDialog).toBeHidden();
  const cloneDialog = page.locator('.n-modal').filter({ hasText: '远程地址' });
  await expect(cloneDialog).toBeVisible();
  await expect(cloneDialog.getByTestId('clone-path-browse')).toBeVisible();
  await expect(cloneDialog.getByTestId('clone-key-browse')).toBeVisible();

  await cloneDialog.getByTestId('clone-key-browse').click();
  const filePicker = page.locator('.n-modal').filter({ hasText: '选择文件' });
  await expect(filePicker).toBeVisible();
  await filePicker.getByRole('button', { name: '取消', exact: true }).click();
  await expect(filePicker).toBeHidden();
  await cloneDialog.getByRole('button', { name: '取消', exact: true }).click();
});
