import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';

const here = dirname(fileURLToPath(import.meta.url));
const repoPath = readFileSync(join(here, '.repo-path'), 'utf8').trim();

/** 打开应用；若当前没有仓库则先打开 E2E 仓库，并等待工作区数据加载完成。 */
async function ensureRepoOpen(page: Page): Promise<void> {
  await page.goto('/');

  const fetchButton = page.locator('button:has-text("Fetch")').first();
  const openButton = page.locator('button:has-text("打开仓库")').first();

  await expect(fetchButton.or(openButton)).toBeVisible();

  if (await openButton.isVisible()) {
    await openButton.click();
    await page.getByPlaceholder('/Users/me/projects/my-repo').fill(repoPath);
    await page.locator('.n-modal').getByRole('button', { name: '打开', exact: true }).click();
  }

  await expect(fetchButton).toBeVisible();
  // 等待异步加载完成：提交历史里出现 init 提交
  await expect(page.locator('li', { hasText: 'init' }).first()).toBeVisible();
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
  await expect(page.locator('li', { hasText: 'init' }).first()).toBeVisible();
  await expect(page.locator('text=个提交').first()).toBeVisible();
});

test('E-02 选择提交后展示文件变更与 Diff', async ({ page }) => {
  await ensureRepoOpen(page);

  await page.locator('li', { hasText: 'init' }).first().click();

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
