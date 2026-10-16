import { defineConfig, devices } from '@playwright/test';

/**
 * E2E 前置条件：
 * 1) 后端已启动（`cd ../synclavis4j && mvn spring-boot:run`，默认 127.0.0.1:18080）
 * 2) 浏览器已安装（`pnpm exec playwright install chromium`）
 *
 * 前端 dev server 由 Playwright 自动拉起（若已在运行则复用）。
 */
export default defineConfig({
  testDir: './tests/e2e',
  timeout: 45_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  globalSetup: './tests/e2e/global-setup.ts',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'pnpm dev',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: true,
    timeout: 90_000,
  },
});
