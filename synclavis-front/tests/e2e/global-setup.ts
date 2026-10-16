import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// 项目是 ESM（"type": "module"），不能使用 __dirname
const here = dirname(fileURLToPath(import.meta.url));

/**
 * 创建 E2E 用的临时仓库，并把路径写入 .repo-path。
 *
 * 仓库带一个 bare 远程并已 push -u，这样 Fetch / Pull / Push 按钮才是可用状态
 * （无远程时按需求约定会被禁用）。
 */
export default function globalSetup(): void {
  const base = mkdtempSync(join(tmpdir(), 'synclavis-e2e-'));
  const repo = join(base, 'e2e-repo');
  const origin = join(base, 'origin.git');

  const git = (args: string[], cwd?: string): void => {
    execFileSync('git', args, cwd ? { cwd } : undefined);
  };

  git(['-c', 'init.defaultBranch=main', 'init', '-q', repo]);
  git(['config', 'user.email', 'e2e@synclavis.local'], repo);
  git(['config', 'user.name', 'e2e'], repo);
  writeFileSync(join(repo, 'README.md'), '# synclavis e2e\n');
  git(['add', '.'], repo);
  git(['commit', '-qm', 'init'], repo);

  // bare 远程 + 上游跟踪
  git(['-c', 'init.defaultBranch=main', 'clone', '-q', '--bare', repo, origin]);
  git(['remote', 'add', 'origin', origin], repo);
  git(['push', '-q', '-u', 'origin', 'main'], repo);

  writeFileSync(join(here, '.repo-path'), repo, 'utf8');
}
