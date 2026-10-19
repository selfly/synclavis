import { readFileSync, realpathSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';
import { COMMIT_MESSAGE_MIN_WIDTH, DEFAULT_LAYOUT, DIFF_MIN_HEIGHT } from '../../src/utils/layout';
import { DEFAULT_LANE_WIDTH } from '../../src/utils/gitGraph';
import {
  BRANCH_REPO_POINT,
  CHECKOUT_CONFLICT_REPO_POINT,
  CONFLICT_BRANCH,
  FILES_HTML_FILE,
  FILES_IGNORED_DIR,
  FILES_IGNORED_FILE,
  FILES_JAVA_FILE,
  FILES_PROPERTIES_FILE,
  FILES_REPO_POINT,
  FILES_VISIBLE_CONTENT,
  FILES_VISIBLE_FILE,
  FILES_VUE_FILE,
  FILES_XML_FILE,
  FILES_YAML_FILE,
  HISTORY_COMMITS,
  LOCAL_BARE_POINT,
  LOCAL_REPO_POINT,
  LONG_LINE_FILE,
  LONG_PATH_FILE,
  MERGE_FEATURE_SUBJECT,
  MERGE_REPO_POINT,
  MERGE_SUBJECT,
  PUSH_REPO_POINT,
  ORDER_DATE_SUBJECTS,
  ORDER_REPO_POINT,
  ORDER_TOPO_SUBJECTS,
  REMOTE_ONLY_ALL_COMMITS,
  REMOTE_ONLY_BRANCH,
  REMOTE_ONLY_BRANCH_COMMITS,
  REMOTE_ONLY_MAIN_SUBJECT,
  REMOTE_ONLY_REPO_POINT,
  REMOTE_ONLY_SUBJECT,
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

/** 读取后端会话（顺序即 Tab 展示顺序） */
interface SessionSnapshot {
  activeId: string | null;
  tabs: Array<{ id: string; name: string; path: string }>;
}

async function readSession(page: Page): Promise<SessionSnapshot> {
  const response = await page.request.get('/api/session');
  const payload = (await response.json()) as { data: SessionSnapshot };
  return payload.data;
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

test('E-23 Tab 拖拽排序并持久化为后端会话顺序', async ({ page }) => {
  await ensureRepoOpen(page);

  const original = await readSession(page);
  const mainTab = original.tabs.find((tab) => tab.path === repoRealPath);
  if (!mainTab) {
    throw new Error('主仓库 Tab 未打开');
  }

  const branchPath = readFileSync(join(here, BRANCH_REPO_POINT), 'utf8').trim();
  const openResponse = await page.request.post('/api/repos/open', { data: { path: branchPath } });
  const opened = (await openResponse.json()) as { data: { id: string } };
  const branchId = opened.data.id;

  try {
    // 打开新仓库会把激活项切过去；先切回主仓库，用来验证拖拽本身不改变激活项
    await page.request.put('/api/session/active', { data: { repoId: mainTab.id } });
    await page.reload();

    const source = page.locator(`[data-testid="repo-tab"][data-repo-id="${mainTab.id}"]`);
    const target = page.locator(`[data-testid="repo-tab"][data-repo-id="${branchId}"]`);
    await expect(source).toBeVisible();
    await expect(target).toBeVisible();

    const box = await target.boundingBox();
    if (!box) {
      throw new Error('找不到目标 Tab 的位置');
    }
    // 落在右半区 = 插到该 Tab 之后
    await source.dragTo(target, { targetPosition: { x: box.width - 4, y: box.height / 2 } });

    const expectedIds = original.tabs
      .map((tab) => tab.id)
      .filter((id) => id !== mainTab.id)
      .concat([branchId, mainTab.id]);

    await expect
      .poll(async () => (await readSession(page)).tabs.map((tab) => tab.id))
      .toEqual(expectedIds);
    expect((await readSession(page)).activeId).toBe(mainTab.id);

    // 刷新：localStorage 镜像与后端顺序一致
    await page.reload();
    await expect
      .poll(() =>
        page
          .getByTestId('repo-tab')
          .evaluateAll((elements) =>
            elements.map((element) => (element as HTMLElement).dataset.repoId),
          ),
      )
      .toEqual(expectedIds);

    // 清掉本地缓存：顺序仍由后端恢复（后端为唯一真相）
    await page.evaluate(() => localStorage.removeItem('synclavis.session.v1'));
    await page.reload();
    await expect
      .poll(() =>
        page
          .getByTestId('repo-tab')
          .evaluateAll((elements) =>
            elements.map((element) => (element as HTMLElement).dataset.repoId),
          ),
      )
      .toEqual(expectedIds);
  } finally {
    // 只清理本次新增的临时 Tab，并把真实会话的顺序/激活项原样还原
    await page.request.delete(`/api/repos/${branchId}`);
    await page.request.put('/api/session/order', {
      data: { ids: original.tabs.map((tab) => tab.id) },
    });
    if (original.activeId) {
      await page.request.put('/api/session/active', { data: { repoId: original.activeId } });
    }
    await page.reload();
  }
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

/**
 * E-15：本地 `git init` 的仓库没有 remote，必须能就地添加后再推送。
 *
 * 回归覆盖四件事：①无 remote 时 Push 不置灰而是引导到设置；②添加远程后自动 Fetch 一次；
 * ③首次推送走「设置上游并推送」且文案用实际 remote 名；④侧栏远程分支按 remote 分组。
 */
test('E-15 本地 init 仓库添加远程后自动 Fetch，首次推送成功且侧栏按 remote 分组', async ({
  page,
}) => {
  const localRepoPath = realpathSync(readFileSync(join(here, LOCAL_REPO_POINT), 'utf8').trim());
  const localBarePath = realpathSync(readFileSync(join(here, LOCAL_BARE_POINT), 'utf8').trim());

  // 打开一个没有任何 remote 的本地仓库
  await page.goto('/');
  await page.getByRole('button', { name: '打开仓库' }).first().click();
  const openDialog = page.locator('.n-modal').filter({ hasText: '打开仓库' });
  await expect(openDialog).toBeVisible();
  await openDialog.getByPlaceholder('/Users/me/projects/my-repo').fill(localRepoPath);
  await openDialog.getByRole('button', { name: '打开', exact: true }).click();
  await expect(openDialog).toBeHidden();
  await waitForNoOverlay(page);
  await expect(page.getByText('共 1 个提交')).toBeVisible();

  // 无远程时 Push 不置灰：点击直接把「仓库设置 → 远程仓库」摆到用户面前
  const settingsDialog = page.locator('.n-modal').filter({ hasText: '仓库设置' });
  await expect(page.getByTestId('push-button')).toBeEnabled();
  await page.getByTestId('push-button').click();
  await expect(settingsDialog).toBeVisible();
  await expect(settingsDialog.getByText('还没有远程仓库')).toBeVisible();

  // 添加 origin → 指向本地 bare 远程
  await settingsDialog.getByTestId('remote-add').click();
  await settingsDialog.getByPlaceholder('origin').fill('origin');
  await settingsDialog.getByPlaceholder('https://host/repo.git').fill(localBarePath);
  await settingsDialog.getByTestId('remote-save').click();

  const remoteRow = settingsDialog.locator('[data-testid="remote-row"]');
  await expect(remoteRow).toHaveCount(1);
  await expect(remoteRow).toHaveAttribute('data-name', 'origin');
  await expect(remoteRow).toContainText(localBarePath);

  await settingsDialog.getByRole('button', { name: '取消', exact: true }).click();
  await expect(settingsDialog).toBeHidden();
  await waitForNoOverlay(page);

  // 添加成功后自动 Fetch 一次，进度进操作日志
  await page.locator('button:has-text("操作日志")').first().click();
  await expect(page.locator('text=开始 fetch origin').first()).toBeVisible();
  // 等自动 Fetch 收尾，否则紧接着的 Push 会撞上每仓库写锁（REPO_BUSY）
  await expect(page.locator('text=执行中…')).toHaveCount(0, { timeout: 20000 });

  // 有 remote 之后 Push 变成「无 upstream」：不再单独弹「未设置上游分支」，
  // 而是同一个「推送预览」弹窗——列出待推送提交、说明将设置 upstream、按钮为「设置并推送」
  await expect(page.getByText('无 upstream')).toBeVisible();
  await page.getByTestId('push-button').click();
  const pushPreview = page.getByTestId('push-preview-dialog');
  await expect(pushPreview).toBeVisible();
  await expect(pushPreview.getByTestId('push-preview-summary')).toContainText('origin/main');
  await expect(pushPreview.getByTestId('push-preview-upstream')).toContainText('origin/main');
  await expect(pushPreview.getByTestId('push-preview-commit')).toHaveCount(1);
  await expect(pushPreview.getByTestId('push-preview-commit')).toContainText('init');
  const previewConfirm = pushPreview.getByTestId('push-preview-confirm');
  await expect(previewConfirm).toContainText('设置并推送');
  await previewConfirm.click();
  await expect(pushPreview).toBeHidden();

  // 推送成功后上游建立，「无 upstream」标记消失
  await expect(page.getByText('无 upstream')).toHaveCount(0, { timeout: 20000 });

  // 刷新后会话恢复，侧栏远程分支按 remote 分组展示（首次 push 建立了 origin/main）
  await page.reload();
  await expect(page.getByText('共 1 个提交')).toBeVisible();
  await expect(
    page.locator('[data-testid="remote-group"]').filter({ hasText: 'origin' }),
  ).toBeVisible({ timeout: 20000 });
  await expect(
    page.locator('[data-testid="remote-branch"]').filter({ hasText: 'main' }),
  ).toBeVisible();
});

/**
 * E-16：双击侧栏分支即可切换。
 *
 * 覆盖三件事：①本地分支双击切换（当前分支标记、提交历史、「无 upstream」标记都跟着变）；
 * ②双击远程分支检出同名本地跟踪分支并自动建立 upstream（对话框按「检出」呈现、起点只读）；
 * ③工作区有改动时切换被拒绝、列出会被覆盖的文件且 HEAD 保持不变。
 */
test('E-16 双击分支切换：本地切换 / 远程检出跟踪分支 / 脏工作区冲突', async ({ page }) => {
  const branchRepoPath = realpathSync(readFileSync(join(here, BRANCH_REPO_POINT), 'utf8').trim());

  await page.goto('/');
  await page.getByRole('button', { name: '打开仓库' }).first().click();
  const openDialog = page.locator('.n-modal').filter({ hasText: '打开仓库' });
  await expect(openDialog).toBeVisible();
  await openDialog.getByPlaceholder('/Users/me/projects/my-repo').fill(branchRepoPath);
  await openDialog.getByRole('button', { name: '打开', exact: true }).click();
  await expect(openDialog).toBeHidden();
  await waitForNoOverlay(page);

  const localBranch = (name: string) =>
    page.locator(`[data-testid="local-branch"][data-name="${name}"]`);
  const currentBranch = () => page.locator('[data-testid="local-branch"][data-current="true"]');

  // 切到「当前分支」范围：本用例核对的是「检出后历史跟着换」，全引用下另一条分支的提交
  // 本来就一直在列表里（E-20 专门覆盖那个默认），这里必须收紧到 HEAD 可达才有判别力。
  const scopeToggle = page.getByTestId('log-scope-toggle');
  await expect(scopeToggle).toHaveAttribute('data-scope', 'all');
  await scopeToggle.click();
  await expect(scopeToggle).toHaveAttribute('data-scope', 'branch');

  // 就绪：夹具仓库当前在 main（1 条提交）
  await expect(currentBranch()).toHaveAttribute('data-name', 'main');
  await expect(page.getByText('共 1 个提交')).toBeVisible();

  // ① 双击 feature → 切过去：当前分支标记、提交历史、「无 upstream」标记都跟着变
  await localBranch('feature').dblclick();
  await expect(currentBranch()).toHaveAttribute('data-name', 'feature');
  await expect(page.getByText('共 2 个提交')).toBeVisible();
  await expect(page.getByTestId('commit-row').filter({ hasText: 'branch-feature-1' })).toBeVisible();
  // feature 是本地分支且没有 upstream（仓库有 origin，所以该标记会显示）
  await expect(page.getByText('无 upstream')).toBeVisible();

  // ② 双击 main 切回来 → 提交历史与 upstream 标记都复原
  await localBranch('main').dblclick();
  await expect(currentBranch()).toHaveAttribute('data-name', 'main');
  await expect(page.getByText('共 1 个提交')).toBeVisible();
  await expect(page.getByText('无 upstream')).toHaveCount(0);

  // ③ 双击远程分支 origin/dev → 打开「检出分支」对话框：起点只读、预填短名 → 确认后检出本地 dev
  const remoteDev = page.locator('[data-testid="remote-branch"][data-name="origin/dev"]');
  await expect(remoteDev).toBeVisible();
  await remoteDev.dblclick();
  const checkoutDialog = page.locator('.n-modal').filter({ hasText: '检出分支' });
  await expect(checkoutDialog).toBeVisible();
  await expect(checkoutDialog.getByPlaceholder('feature/login')).toHaveValue('dev');
  // 检出语义：标题/按钮为「检出」，来源只读展示 origin/dev，不出现起点选择器
  await expect(checkoutDialog.getByTestId('new-branch-confirm')).toHaveText('检出');
  await expect(checkoutDialog.getByTestId('new-branch-source')).toHaveText('origin/dev');
  await expect(checkoutDialog.getByTestId('new-branch-start')).toHaveCount(0);
  await checkoutDialog.getByTestId('new-branch-confirm').click();
  await expect(checkoutDialog).toBeHidden();

  // 本地 dev 出现且为当前分支；起点是 origin/dev，因此 JGit 自动建立了 upstream
  await expect(currentBranch()).toHaveAttribute('data-name', 'dev');
  await expect(page.getByText('共 2 个提交')).toBeVisible();
  await expect(page.getByText('无 upstream')).toHaveCount(0);

  // ③b 「本地分支」标题旁的 `+` 仍是「新建分支」语义：按钮「创建并切换」、起点可选
  await page.getByTestId('sidebar-new-branch').click();
  const createDialog = page.locator('.n-modal').filter({ hasText: '新建分支' });
  await expect(createDialog).toBeVisible();
  await expect(createDialog.getByTestId('new-branch-confirm')).toHaveText('创建并切换');
  await expect(createDialog.getByTestId('new-branch-start')).toBeVisible();
  await createDialog.getByRole('button', { name: '取消', exact: true }).click();
  await expect(createDialog).toBeHidden();

  // ④ 脏工作区：主仓库里双击一个会被 README 本地改动覆盖的分支 → 被拒绝、HEAD 不变
  await ensureRepoOpen(page);
  await localBranch(CONFLICT_BRANCH).dblclick();
  const conflictDialog = page.locator('.n-dialog').filter({ hasText: '无法切换到' });
  await expect(conflictDialog).toBeVisible();
  await expect(page.locator('[data-testid="checkout-conflict"]')).toBeVisible();
  await expect(conflictDialog).toContainText('README.md');
  await expect(conflictDialog).toContainText('请先提交或 Stash');
  // HEAD 未变：仍是 main
  await expect(currentBranch()).toHaveAttribute('data-name', 'main');
  await conflictDialog.getByRole('button', { name: '知道了' }).click();
  await expect(conflictDialog).toBeHidden();
});

/**
 * E-17：新建/检出分支失败后**不留半成品分支**（缺陷 20 的闸门）。
 *
 * JGit 的 `CheckoutCommand` 是「先建分支、再切工作区」，冲突时分支已经落盘。
 * 不回滚的话，用户按提示 commit/Stash 后原地重试会撞 `BRANCH_EXISTS`，
 * 看到「本地已存在同名分支」—— 与事实不符，只能手工删分支。
 *
 * 断言方式：同一弹窗连点两次「检出」，两次都必须报「会被覆盖」的文件清单。
 */
test('E-17 检出冲突后不残留半成品分支：原地重试仍是冲突而非同名分支', async ({ page }) => {
  const repoPath = realpathSync(
    readFileSync(join(here, CHECKOUT_CONFLICT_REPO_POINT), 'utf8').trim(),
  );

  await page.goto('/');
  await page.getByRole('button', { name: '打开仓库' }).first().click();
  const openDialog = page.locator('.n-modal').filter({ hasText: '打开仓库' });
  await expect(openDialog).toBeVisible();
  await openDialog.getByPlaceholder('/Users/me/projects/my-repo').fill(repoPath);
  await openDialog.getByRole('button', { name: '打开', exact: true }).click();
  await expect(openDialog).toBeHidden();
  await waitForNoOverlay(page);

  // 就绪：当前在 main、仓库有 `origin/topic`
  await expect(page.locator('[data-testid="local-branch"][data-current="true"]')).toHaveAttribute(
    'data-name',
    'main',
  );
  const remoteTopic = page.locator('[data-testid="remote-branch"][data-name="origin/topic"]');
  await expect(remoteTopic).toBeVisible();

  // 本地 README 已改脏 → 检出 origin/topic 必然冲突
  await remoteTopic.dblclick();
  const checkoutDialog = page.locator('.n-modal').filter({ hasText: '检出分支' });
  await expect(checkoutDialog).toBeVisible();
  const confirm = checkoutDialog.getByTestId('new-branch-confirm');
  await confirm.click();
  await expect(checkoutDialog).toContainText('会被覆盖');

  // 关键：原地重试仍是冲突。若后端没回滚，这里会变成「本地已存在同名分支：topic」
  await confirm.click();
  await expect(checkoutDialog).toContainText('会被覆盖');
  await expect(checkoutDialog).not.toContainText('本地已存在同名分支');

  // 侧栏里没有留下本地 topic，当前分支仍是 main
  await checkoutDialog.getByRole('button', { name: '取消', exact: true }).click();
  await expect(checkoutDialog).toBeHidden();
  await expect(page.locator('[data-testid="local-branch"][data-name="topic"]')).toHaveCount(0);
  await expect(page.locator('[data-testid="local-branch"][data-current="true"]')).toHaveAttribute(
    'data-name',
    'main',
  );
});

/**
 * E-18：推送前必须能看清「这次要推什么」。
 *
 * 夹具 `main` 已 `push -u`（1 条提交），本地另有 2 条未推送提交。覆盖四件事：
 * ①预览弹窗按新→旧列出这 2 条（短 hash + subject）并写清目标 `origin/main`；
 * ②取消不推（远端不变，再点仍列出 2 条）；③确认后推送成功、`ahead` 归零；
 * ④没有待推送提交时提示「没有需要推送的提交」并禁用确认。
 */
test('E-18 推送预览列出待推送提交、取消不推、确认后归零', async ({ page }) => {
  const pushRepoPath = realpathSync(readFileSync(join(here, PUSH_REPO_POINT), 'utf8').trim());

  await page.goto('/');
  await page.getByRole('button', { name: '打开仓库' }).first().click();
  const openDialog = page.locator('.n-modal').filter({ hasText: '打开仓库' });
  await expect(openDialog).toBeVisible();
  await openDialog.getByPlaceholder('/Users/me/projects/my-repo').fill(pushRepoPath);
  await openDialog.getByRole('button', { name: '打开', exact: true }).click();
  await expect(openDialog).toBeHidden();
  await waitForNoOverlay(page);

  // 就绪：3 条提交、本地领先远端 2 条（有 upstream，所以没有「无 upstream」标记）
  await expect(page.getByText('共 3 个提交')).toBeVisible();
  await expect(page.getByText('↑2').first()).toBeVisible();
  await expect(page.getByText('无 upstream')).toHaveCount(0);

  const dialog = page.getByTestId('push-preview-dialog');
  await page.getByTestId('push-button').click();
  await expect(dialog).toBeVisible();
  await expect(dialog.getByTestId('push-preview-summary')).toContainText('将推送 2 个提交');
  await expect(dialog.getByTestId('push-preview-summary')).toContainText('origin/main');
  // 已有 upstream → 不再提示「将设置 upstream」
  await expect(dialog.getByTestId('push-preview-upstream')).toHaveCount(0);

  const rows = dialog.getByTestId('push-preview-commit');
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(0)).toContainText('push-local-2');
  await expect(rows.nth(1)).toContainText('push-local-1');
  await expect(rows.nth(0)).toContainText(/[0-9a-f]{8}/);

  // 取消：不推送，远端没变，再点仍列出同样的 2 条
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  await expect(dialog).toBeHidden();
  await page.getByTestId('push-button').click();
  await expect(dialog.getByTestId('push-preview-commit')).toHaveCount(2);

  // 确认推送（勾选清单里的就是实际推的 2 条）
  await dialog.getByTestId('push-preview-confirm').click();
  await expect(dialog).toBeHidden();
  await page.locator('button:has-text("操作日志")').first().click();
  await expect(page.locator('text=开始 push origin/main').first()).toBeVisible();
  await expect(page.locator('text=执行中…')).toHaveCount(0, { timeout: 20000 });

  // 推送成功后 JGit 会更新远程跟踪引用（`PushProcess.updateTrackingRefs`），
  // 状态里的 ahead 归零 → 再点 Push 已无待推送提交，确认按钮禁用
  await expect(page.getByText('↑2')).toHaveCount(0, { timeout: 20000 });
  await page.getByTestId('push-button').click();
  await expect(dialog.getByTestId('push-preview-empty')).toBeVisible();
  await expect(dialog.getByTestId('push-preview-commit')).toHaveCount(0);
  await expect(dialog.getByTestId('push-preview-confirm')).toBeDisabled();
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  await expect(dialog).toBeHidden();
});

/**
 * E-19 提交泳道图（timeline，P13）。
 *
 * ① merge 提交行有两条出边（首父本列竖线 + 到第二父的曲线），整列 2 lane；
 * ② 同一列颜色恒定（按 lane 索引取模 → `--sl-graph-N`）；
 * ③ 过滤后父提交不在集合里 → 该行**断线**（没有任何线段，只剩圆点）；
 * ④ 线性长历史仓库仍是 1 列，且窗口化行数受限、整页无滚动条（E-07 闸门在新图列下仍成立）。
 */
test('E-19 提交泳道图：merge 两条出边、同列同色、过滤断线、不破坏布局', async ({ page }) => {
  const mergeRepoPath = realpathSync(readFileSync(join(here, MERGE_REPO_POINT), 'utf8').trim());

  await page.goto('/');
  await page.getByRole('button', { name: '打开仓库' }).first().click();
  const openDialog = page.locator('.n-modal').filter({ hasText: '打开仓库' });
  await expect(openDialog).toBeVisible();
  await openDialog.getByPlaceholder('/Users/me/projects/my-repo').fill(mergeRepoPath);
  await openDialog.getByRole('button', { name: '打开', exact: true }).click();
  await expect(openDialog).toBeHidden();
  await waitForNoOverlay(page);

  // 就绪：6 条提交（merge-base + 2 main + 2 feature + 1 merge），整列 2 lane
  await expect(page.getByText('共 6 个提交')).toBeVisible();
  const list = page.getByTestId('commit-list');
  await expect(list).toHaveAttribute('data-lane-count', '2');

  // ① merge 行：两个 parent；svg 宽 = 2 列；2 条竖线 + 1 条曲线 + 1 个圆点
  const mergeRow = page.getByTestId('commit-row').filter({ hasText: MERGE_SUBJECT });
  await expect(mergeRow).toHaveAttribute('data-parents', '2');
  const mergeSvg = mergeRow.locator('svg');
  await expect(mergeSvg).toHaveAttribute('data-lane', '0');
  await expect(mergeSvg).toHaveAttribute('width', String(2 * DEFAULT_LANE_WIDTH));
  await expect(mergeSvg).toHaveAttribute('height', '44');
  await expect(mergeSvg.locator('path')).toHaveCount(3);
  // ② 第 0 列 = 第 1 色，第 1 列 = 第 2 色；到第二父的曲线跟随父所在列取色
  await expect(mergeSvg.locator('path').nth(0)).toHaveAttribute('stroke', 'var(--sl-graph-1)');
  await expect(mergeSvg.locator('path').nth(1)).toHaveAttribute('stroke', 'var(--sl-graph-2)');
  await expect(mergeSvg.locator('path').nth(2)).toHaveAttribute('stroke', 'var(--sl-graph-2)');
  await expect(mergeSvg.locator('path').nth(2)).toHaveAttribute('d', /C/);
  await expect(mergeSvg.locator('circle')).toHaveAttribute('fill', 'var(--sl-graph-1)');

  // ② 同列同色：feature 链两行都落在第 1 列 → 圆点都是第 2 色
  const featureOne = page.getByTestId('commit-row').filter({ hasText: `${MERGE_FEATURE_SUBJECT}1` });
  await expect(featureOne.locator('svg')).toHaveAttribute('data-lane', '1');
  await expect(featureOne.locator('svg').locator('circle')).toHaveAttribute('fill', 'var(--sl-graph-2)');
  const featureTwo = page.getByTestId('commit-row').filter({ hasText: `${MERGE_FEATURE_SUBJECT}2` });
  await expect(featureTwo.locator('svg')).toHaveAttribute('data-lane', '1');

  // ③ 过滤断线：只留 feature-1，它的父提交不在集合里 → 本行没有任何线段
  const search = page.getByPlaceholder('搜索提交信息');
  await search.fill(`${MERGE_FEATURE_SUBJECT}1`);
  await search.press('Enter');
  await expect(page.getByText('共 1 个提交')).toBeVisible();
  const onlyRow = page.getByTestId('commit-row');
  await expect(onlyRow).toHaveCount(1);
  await expect(page.getByTestId('commit-list')).toHaveAttribute('data-lane-count', '1');
  await expect(onlyRow.locator('svg').locator('path')).toHaveCount(0);
  await expect(onlyRow.locator('svg').locator('circle')).toHaveCount(1);
  await page.getByRole('button', { name: '重置' }).click();
  await expect(page.getByText('共 6 个提交')).toBeVisible();

  // ④ 长历史（线性，61 条）仓库：1 列 + 窗口化 + 整页无滚动条
  await page.getByRole('button', { name: '打开仓库' }).first().click();
  const openDialog2 = page.locator('.n-modal').filter({ hasText: '打开仓库' });
  await expect(openDialog2).toBeVisible();
  await openDialog2.getByPlaceholder('/Users/me/projects/my-repo').fill(repoPath);
  await openDialog2.getByRole('button', { name: '打开', exact: true }).click();
  await expect(openDialog2).toBeHidden();
  await waitForNoOverlay(page);

  await expect(page.getByText(`共 ${TOTAL_COMMITS} 个提交`)).toBeVisible();
  const longList = page.getByTestId('commit-list');
  await expect(longList).toHaveAttribute('data-lane-count', '1');
  // 线性历史：每行只有「上半段 + 下半段」两条竖线（root 行只有上半段）→ 列宽 1 个 lane
  await expect(page.getByTestId('commit-row').first().locator('svg')).toHaveAttribute(
    'width',
    String(DEFAULT_LANE_WIDTH),
  );
  // 窗口化仍生效，且图列没有把整页撑出滚动条（E-07 的核心闸门）
  const metrics = await page.evaluate(() => ({
    rows: document.querySelectorAll('[data-testid="commit-row"]').length,
    docScrollHeight: document.documentElement.scrollHeight,
    docClientHeight: document.documentElement.clientHeight,
  }));
  expect(metrics.rows).toBeLessThanOrEqual(40);
  expect(metrics.rows).toBeGreaterThan(10);
  expect(metrics.docScrollHeight).toBeLessThanOrEqual(metrics.docClientHeight + 1);
});

/**
 * E-20 默认全引用（P13 引用范围契约）。
 *
 * 回归的是实测缺陷：`b8a099f888` 只被 `refs/remotes/origin/1.0.1` 指向、当前分支不可达，
 * 只从 `HEAD` 遍历时整条链都不出现在列表里（SourceGit 因为覆盖全部引用而能看到）。
 *
 * ① 缺省 `data-scope="all"`：能看到只被远程分支指向的提交，且带 `origin/extra` 徽标、独立一列；
 * ② 切「当前分支」：该行消失、total 减少、泳道收敛为 1 列；
 * ③ 切回「全部引用」：复现（证明不是「加载一次」蒙对的）。
 */
test('E-20 默认全引用：HEAD 不可达的远程分支也能看到，可切回当前分支', async ({ page }) => {
  const remoteOnlyPath = realpathSync(readFileSync(join(here, REMOTE_ONLY_REPO_POINT), 'utf8').trim());

  await page.goto('/');
  await page.getByRole('button', { name: '打开仓库' }).first().click();
  const openDialog = page.locator('.n-modal').filter({ hasText: '打开仓库' });
  await expect(openDialog).toBeVisible();
  await openDialog.getByPlaceholder('/Users/me/projects/my-repo').fill(remoteOnlyPath);
  await openDialog.getByRole('button', { name: '打开', exact: true }).click();
  await expect(openDialog).toBeHidden();
  await waitForNoOverlay(page);

  // ① 缺省全引用：2 条 main + 1 条只被 origin/extra 指向 = 3 条；两个尖端互为兄弟 → 2 列
  await expect(page.getByText(`共 ${REMOTE_ONLY_ALL_COMMITS} 个提交`)).toBeVisible();
  const toggle = page.getByTestId('log-scope-toggle');
  await expect(toggle).toHaveAttribute('data-scope', 'all');
  const list = page.getByTestId('commit-list');
  await expect(list).toHaveAttribute('data-lane-count', '2');

  const remoteRow = page.getByTestId('commit-row').filter({ hasText: REMOTE_ONLY_SUBJECT });
  await expect(remoteRow).toHaveCount(1);
  await expect(remoteRow).toContainText(`origin/${REMOTE_ONLY_BRANCH}`);

  // ② 切「当前分支」：远程独占的提交消失，total 减到 2，泳道收敛到 1 列
  await toggle.click();
  await expect(toggle).toHaveAttribute('data-scope', 'branch');
  await expect(page.getByText(`共 ${REMOTE_ONLY_BRANCH_COMMITS} 个提交`)).toBeVisible();
  await expect(page.getByTestId('commit-row').filter({ hasText: REMOTE_ONLY_SUBJECT })).toHaveCount(0);
  await expect(page.getByTestId('commit-row').filter({ hasText: REMOTE_ONLY_MAIN_SUBJECT })).toHaveCount(1);
  await expect(page.getByTestId('commit-list')).toHaveAttribute('data-lane-count', '1');

  // ③ 切回「全部引用」：复现
  await toggle.click();
  await expect(toggle).toHaveAttribute('data-scope', 'all');
  await expect(page.getByText(`共 ${REMOTE_ONLY_ALL_COMMITS} 个提交`)).toBeVisible();
  await expect(page.getByTestId('commit-row').filter({ hasText: REMOTE_ONLY_SUBJECT })).toHaveCount(1);

  // 布局不被「多一列」破坏（E-07 同源闸门）
  const metrics = await page.evaluate(() => ({
    docScrollHeight: document.documentElement.scrollHeight,
    docClientHeight: document.documentElement.clientHeight,
  }));
  expect(metrics.docScrollHeight).toBeLessThanOrEqual(metrics.docClientHeight + 1);
});

/**
 * E-21 排序切换（P13 排序契约）。
 *
 * 回归的是实测差异：SourceGit 默认按提交时间排，`b8a099f8`（1.0.1 尖端）会被夹在 master 的
 * 更晚提交之间、两条泳道平行贯穿；我们此前固定拓扑序，把整条分支扎堆挪走，看起来「完全不一样」。
 *
 * ① 缺省 `data-order="date"`：两分支的提交按时间**交错**；
 * ② 切「按拓扑」：同分支提交**扎堆**；
 * ③ 切回「按时间」：复现（证明不是只加载一次蒙对的）。
 */
test('E-21 排序切换：缺省按时间交错，可切按拓扑扎堆', async ({ page }) => {
  const orderRepoPath = realpathSync(readFileSync(join(here, ORDER_REPO_POINT), 'utf8').trim());

  await page.goto('/');
  await page.getByRole('button', { name: '打开仓库' }).first().click();
  const openDialog = page.locator('.n-modal').filter({ hasText: '打开仓库' });
  await expect(openDialog).toBeVisible();
  await openDialog.getByPlaceholder('/Users/me/projects/my-repo').fill(orderRepoPath);
  await openDialog.getByRole('button', { name: '打开', exact: true }).click();
  await expect(openDialog).toBeHidden();
  await waitForNoOverlay(page);

  await expect(page.getByText('共 6 个提交')).toBeVisible();
  await expect(page.getByTestId('commit-list')).toHaveAttribute('data-lane-count', '2');

  /** 把每行文本映射回夹具里已知的 subject（行内还含 hash / 作者 / 时间） */
  const subjects = async (): Promise<string[]> => {
    const texts = await page
      .getByTestId('commit-row')
      .evaluateAll((els) => els.map((el) => (el.textContent || '').replace(/\s+/g, ' ').trim()));
    const known = [...ORDER_DATE_SUBJECTS];
    return texts.map((text) => known.find((subject) => text.includes(subject)) ?? text);
  };

  // ① 缺省按时间：两条分支交错
  const toggle = page.getByTestId('log-order-toggle');
  await expect(toggle).toHaveAttribute('data-order', 'date');
  // 列表是异步重查的：用 poll 等到顺序真正变化，避免读到切换前的旧行
  await expect.poll(subjects).toEqual(ORDER_DATE_SUBJECTS);

  // ② 切「按拓扑」：同分支扎堆
  await toggle.click();
  await expect(toggle).toHaveAttribute('data-order', 'topo');
  await expect.poll(subjects).toEqual(ORDER_TOPO_SUBJECTS);
  // 两种顺序都不影响提交总数
  await expect(page.getByText('共 6 个提交')).toBeVisible();

  // ③ 切回「按时间」：复现
  await toggle.click();
  await expect(toggle).toHaveAttribute('data-order', 'date');
  await expect.poll(subjects).toEqual(ORDER_DATE_SUBJECTS);

  // 布局不被多一列/顺序切换破坏（E-07 同源闸门）
  const metrics = await page.evaluate(() => ({
    docScrollHeight: document.documentElement.scrollHeight,
    docClientHeight: document.documentElement.clientHeight,
  }));
  expect(metrics.docScrollHeight).toBeLessThanOrEqual(metrics.docClientHeight + 1);
});

/**
 * E-22：侧栏「文件」模式的项目文件树（只读浏览）。
 *
 * 覆盖：进入文件模式 → 根目录懒加载（未展开时看不到子项）→ 默认隐藏 .gitignore 忽略项
 * → 单击目录前的箭头展开/折叠、双击目录行同样切换 → 双击文件在右列查看内容（树里高亮当前文件）
 * → 关闭后回到 Diff → 打开「显示忽略项」后忽略项出现。
 * 最后复用 E-07 的闸门：树再长也不能把整页撑出滚动条。
 */
test('E-22 项目文件树：懒加载浏览、箭头展开、双击查看只读内容', async ({ page }) => {
  const filesRepoPath = realpathSync(readFileSync(join(here, FILES_REPO_POINT), 'utf8').trim());

  await page.goto('/');
  await page.getByRole('button', { name: '打开仓库' }).first().click();
  const openDialog = page.locator('.n-modal').filter({ hasText: '打开仓库' });
  await expect(openDialog).toBeVisible();
  await openDialog.getByPlaceholder('/Users/me/projects/my-repo').fill(filesRepoPath);
  await openDialog.getByRole('button', { name: '打开', exact: true }).click();
  await expect(openDialog).toBeHidden();
  await waitForNoOverlay(page);

  await expect(page.getByText('共 1 个提交')).toBeVisible();

  // 切到「文件」模式
  await page.getByTestId('sidebar-mode-files').click();
  const tree = page.getByTestId('project-file-tree');
  await expect(tree).toBeVisible();

  // 根目录：src / README.md / .gitignore 可见；.git 永远不出现
  await expect(tree.locator('li[data-path="src"]')).toBeVisible();
  await expect(tree.locator('li[data-path="README.md"]')).toBeVisible();
  await expect(tree.locator('li[data-path=".gitignore"]')).toBeVisible();
  await expect(tree.locator('li[data-path=".git"]')).toHaveCount(0);

  // 懒加载：src 没展开前，子项不在树里
  await expect(tree.locator(`li[data-path="${FILES_VISIBLE_FILE}"]`)).toHaveCount(0);

  // 默认隐藏被忽略的条目
  await expect(tree.locator(`li[data-path="${FILES_IGNORED_FILE}"]`)).toHaveCount(0);
  await expect(tree.locator(`li[data-path="${FILES_IGNORED_DIR}"]`)).toHaveCount(0);

  const chevron = tree.locator(`[data-testid="project-file-chevron"][data-path="src"]`);

  // 单击目录前的箭头：展开并懒加载子项（箭头是独立热区，不用先选中行）
  await expect(chevron).toHaveAttribute('aria-expanded', 'false');
  await chevron.click();
  await expect(chevron).toHaveAttribute('aria-expanded', 'true');
  await expect(tree.locator(`li[data-path="${FILES_VISIBLE_FILE}"]`)).toBeVisible();
  // 展开/折叠只动树，不该影响右列（此时仍应是 Diff 的初始态：没有 viewer）
  await expect(page.getByTestId('file-viewer')).toHaveCount(0);

  // 再点一次箭头：折叠，子项消失但目录行还在
  await chevron.click();
  await expect(chevron).toHaveAttribute('aria-expanded', 'false');
  await expect(tree.locator(`li[data-path="${FILES_VISIBLE_FILE}"]`)).toHaveCount(0);
  await expect(tree.locator('li[data-path="src"]')).toBeVisible();

  // 双击目录行同样能切换（方案 A 保留的行为）
  await tree.locator('li[data-path="src"]').dblclick();
  await expect(tree.locator(`li[data-path="${FILES_VISIBLE_FILE}"]`)).toBeVisible();

  // 双击文件：右列从 Diff 换成只读文件内容
  const fileRow = tree.locator(`li[data-path="${FILES_VISIBLE_FILE}"]`);
  await fileRow.dblclick();
  const viewer = page.getByTestId('file-viewer');
  await expect(viewer).toBeVisible();
  await expect(viewer).toContainText('只读');
  await expect(page.getByTestId('file-content')).toContainText(FILES_VISIBLE_CONTENT);
  await expect(page.getByTestId('diff-viewer')).toHaveCount(0);
  // 正在查看的文件在树里带高亮标记
  await expect(fileRow).toHaveAttribute('data-current', 'true');
  // 并且高亮必须真的渲染出**半透明**底色（回归闸门：token 若退回裸 `var(--sl-*)`，
  // Tailwind v3 无法套 alpha，`bg-accent/15` 会被静默丢弃、类名在产物 CSS 里根本不存在）。
  // 先把指针移开，排除行 hover 底色的干扰。
  await page.mouse.move(900, 400);
  const highlightBg = await fileRow.evaluate((el) => getComputedStyle(el).backgroundColor);
  const highlightAlpha = Number(/rgba\([^)]*,\s*([\d.]+)\)$/.exec(highlightBg)?.[1] ?? '1');
  expect(highlightAlpha).toBeGreaterThan(0);
  expect(highlightAlpha).toBeLessThan(1);

  // 布局闸门：打开文件后整页仍不滚动
  const metrics = await page.evaluate(() => ({
    docScrollHeight: document.documentElement.scrollHeight,
    docClientHeight: document.documentElement.clientHeight,
  }));
  expect(metrics.docScrollHeight).toBeLessThanOrEqual(metrics.docClientHeight + 1);

  // 右列互斥：点提交即从文件内容切回 Diff（打开文件时已清掉 Diff 选择，所以点同一个提交也生效）
  await page.getByTestId('commit-row').first().click();
  await expect(page.getByTestId('file-viewer')).toHaveCount(0);
  await expect(page.getByTestId('diff-viewer')).toBeVisible();
  // 查看已结束，树里的高亮随之消失
  await expect(fileRow).toHaveAttribute('data-current', 'false');

  // 再打开一次，用 X 关闭同样回到 Diff
  await fileRow.dblclick();
  await expect(viewer).toBeVisible();
  await expect(fileRow).toHaveAttribute('data-current', 'true');
  await page.getByTestId('file-viewer-close').click();
  await expect(page.getByTestId('file-viewer')).toHaveCount(0);
  await expect(page.getByTestId('diff-viewer')).toBeVisible();
  await expect(fileRow).toHaveAttribute('data-current', 'false');

  // 「显示忽略项」：忽略项出现（弱化样式由 CSS 负责，此处只断言可见性）
  await page.getByTestId('project-file-toggle-ignored').click();
  await expect(tree.locator(`li[data-path="${FILES_IGNORED_FILE}"]`)).toBeVisible();
  await expect(tree.locator(`li[data-path="${FILES_IGNORED_DIR}"]`)).toBeVisible();

  // 过滤框走后端搜索：此时 src 已因切换忽略项被收起，树里根本看不到它的子项，
  // 但搜索仍能命中 —— 这正是「本地过滤」做不到的事。
  await expect(tree.locator(`li[data-path="${FILES_VISIBLE_FILE}"]`)).toHaveCount(0);
  await page.getByTestId('project-file-filter').fill('main.ts');
  const results = page.getByTestId('project-file-results');
  await expect(results).toBeVisible();
  await expect(results.locator(`div[data-path="${FILES_VISIBLE_FILE}"]`)).toBeVisible();
  // 按路径匹配：README.md 不命中，同时树视图让位给结果列表
  await expect(results.locator('div[data-path="README.md"]')).toHaveCount(0);
  await expect(tree).toHaveCount(0);

  // 搜索结果同样双击即可查看内容，并带同一个高亮标记
  const resultRow = results.locator(`div[data-path="${FILES_VISIBLE_FILE}"]`);
  await resultRow.dblclick();
  await expect(page.getByTestId('file-content')).toContainText(FILES_VISIBLE_CONTENT);
  await expect(resultRow).toHaveAttribute('data-current', 'true');

  // 清空过滤回到树视图
  await page.getByTestId('project-file-filter').fill('');
  await expect(tree).toBeVisible();
  await expect(results).toHaveCount(0);

  /*
   * 语法高亮（只读 CodeMirror 6）。
   *
   * 门禁思路：`.hl-*` 是 HighlightStyle 运行时输出的**固定**类名，配色只认
   * styles/index.css 里的纯 CSS 规则（写成 Tailwind 类会被 content 扫描漏掉、静默失效）。
   * 所以除了断类名与文本，还要断「token 颜色 ≠ 正文颜色」——类名或 CSS 任一掉链子，
   * token 都会退化成继承来的正文色，这里必红。
   *
   * 抽查 7 种语言（其余语言由 tests/unit/codeLanguage.test.ts 的表驱动覆盖）。
   */
  const tokenColor = (selector: string): Promise<string> =>
    page.getByTestId('file-code').locator(selector).first().evaluate((el) => getComputedStyle(el).color);
  const plainColor = (): Promise<string> =>
    page.getByTestId('file-code').locator('.cm-content').evaluate((el) => getComputedStyle(el).color);

  const openBySearch = async (file: string): Promise<void> => {
    await page.getByTestId('project-file-filter').fill(file);
    const row = page.getByTestId('project-file-results').locator(`div[data-path="${file}"]`);
    await expect(row).toBeVisible();
    await row.dblclick();
    await expect(page.getByTestId('file-code')).toBeVisible();
  };

  // 此刻右列仍停在上面的 main.ts（TypeScript）
  const checkTokens = async (file: string, label: string, token: string, firstText: string): Promise<void> => {
    if (file !== FILES_VISIBLE_FILE) {
      await openBySearch(file);
    }
    await expect(page.getByTestId('file-language')).toHaveText(`· ${label}`);
    await expect(page.getByTestId('file-code').locator(token).first()).toHaveText(firstText);
    expect(await tokenColor(token), `${file} 的 ${token} 应上色`).not.toBe(await plainColor());
  };

  await checkTokens(FILES_VISIBLE_FILE, 'TypeScript', '.hl-keyword', 'export');
  await checkTokens(FILES_JAVA_FILE, 'Java', '.hl-keyword', 'package');
  await checkTokens(FILES_XML_FILE, 'XML', '.hl-tag', 'project');
  await checkTokens(FILES_HTML_FILE, 'HTML', '.hl-tag', 'html');
  await checkTokens(FILES_YAML_FILE, 'YAML', '.hl-property', 'server');
  await checkTokens(FILES_PROPERTIES_FILE, 'Properties', '.hl-property', 'app.name');
  await checkTokens(FILES_VUE_FILE, 'Vue', '.hl-tag', 'template');

  // 切回文件树视图，避免给后续用例留下过滤状态
  await page.getByTestId('project-file-filter').fill('');
});

