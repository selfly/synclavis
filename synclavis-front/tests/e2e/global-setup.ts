import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// 项目是 ESM（"type": "module"），不能使用 __dirname
const here = dirname(fileURLToPath(import.meta.url));

/** 额外制造的提交数：必须让提交列表高度明显超过视口，才能暴露「整页被撑高」类缺陷 */
export const HISTORY_COMMITS = 60;

/** 工作区脏状态：点击暂存区文件查看差异的用例需要一个同时含三种状态的仓库 */
export const TRACKED_FILE = 'tracked.txt';
export const STAGED_PATCH_LINE = 'staged change line';
export const WORKING_PATCH_LINE = 'working change line';
export const UNTRACKED_FILE = 'notes.txt';
export const UNTRACKED_CONTENT = 'untracked file content';
/** 未跟踪的嵌套目录：用于验证树形展示与「整目录暂存 = 其下所有文件」 */
export const UNTRACKED_DIR = 'nest';
export const UNTRACKED_DIR_CONTENT = 'nested untracked file';

/**
 * 创建 E2E 用的临时仓库，并把路径写入 .repo-path。
 *
 * 仓库带一个 bare 远程并已 push -u，这样 Fetch / Pull / Push 按钮才是可用状态
 * （无远程时按需求约定会被禁用）。
 *
 * 另外制造 {@link HISTORY_COMMITS} 条提交（共 HISTORY_COMMITS + 1 条）：
 * 长提交历史是布局缺陷（提交流被推出视口）与虚拟滚动的必要前置条件。
 */
export default async function globalSetup(): Promise<void> {
  const base = mkdtempSync(join(tmpdir(), 'synclavis-e2e-'));
  const repo = join(base, 'e2e-repo');
  const origin = join(base, 'origin.git');

  const git = (args: string[], cwd?: string): void => {
    if (cwd) {
      execFileSync('git', args, { cwd });
    } else {
      execFileSync('git', args);
    }
  };

  git(['-c', 'init.defaultBranch=main', 'init', '-q', repo]);
  git(['config', 'user.email', 'e2e@synclavis.local'], repo);
  git(['config', 'user.name', 'e2e'], repo);
  writeFileSync(join(repo, 'README.md'), '# synclavis e2e\n');
  // 这个文件在最后会被改出一份「已暂存」的变更
  writeFileSync(join(repo, TRACKED_FILE), 'tracked base\n');
  git(['add', '.'], repo);
  git(['commit', '-qm', 'init'], repo);

  // 长历史：每条提交都真实改 README.md（避免 --allow-empty 掩盖 diff 相关问题）
  for (let i = 1; i <= HISTORY_COMMITS; i += 1) {
    const seq = String(i).padStart(3, '0');
    appendFileSync(join(repo, 'README.md'), `history line ${seq}\n`);
    git(['add', '.'], repo);
    git(['commit', '-qm', `history-${seq}`], repo);
  }

  // bare 远程 + 上游跟踪
  git(['-c', 'init.defaultBranch=main', 'clone', '-q', '--bare', repo, origin]);
  git(['remote', 'add', 'origin', origin], repo);
  git(['push', '-q', '-u', 'origin', 'main'], repo);

  // 工作区脏状态（push 之后再制造，避免进远程）：
  //   已暂存：TRACKED_FILE 追加一行并 add
  //   未暂存：README.md 追加一行不 add
  //   未跟踪：UNTRACKED_FILE
  appendFileSync(join(repo, TRACKED_FILE), `${STAGED_PATCH_LINE}\n`);
  git(['add', TRACKED_FILE], repo);
  appendFileSync(join(repo, 'README.md'), `${WORKING_PATCH_LINE}\n`);
  writeFileSync(join(repo, UNTRACKED_FILE), `${UNTRACKED_CONTENT}\n`);
  //   未跟踪目录：nest/{one.txt, deep/two.txt}（E-12 树形与整目录暂存的前置）
  mkdirSync(join(repo, UNTRACKED_DIR, 'deep'), { recursive: true });
  writeFileSync(join(repo, UNTRACKED_DIR, 'one.txt'), `${UNTRACKED_DIR_CONTENT} one\n`);
  writeFileSync(join(repo, UNTRACKED_DIR, 'deep', 'two.txt'), `${UNTRACKED_DIR_CONTENT} two\n`);

  writeFileSync(join(here, '.repo-path'), repo, 'utf8');

  // 记录运行前的主题，交给 global-teardown 还原：
  // E-03 会切换主题并持久化到后端，不还原的话每跑一次 E2E 都会翻转用户的主题偏好。
  try {
    const response = await fetch(`${process.env.SYNCLAVIS_BASE_URL ?? 'http://127.0.0.1:18080'}/api/settings`);
    const payload = (await response.json()) as { data: { theme: string } };
    writeFileSync(join(here, '.repo-theme'), payload.data.theme, 'utf8');
  } catch {
    writeFileSync(join(here, '.repo-theme'), '', 'utf8');
  }
}

