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
/** 超长文件名：验证长路径不会把行内按钮/Commit 按钮挤出视口 */
export const LONG_PATH_DIR = 'longpath';
export const LONG_PATH_FILE = `${LONG_PATH_DIR}/${'a'.repeat(80)}.txt`;
/** 超长 diff 行：验证宽内容只能横向滚动，不能把右列/提交按钮撑出视口 */
export const LONG_LINE_FILE = `${LONG_PATH_DIR}/long-line.txt`;

/** E-15：本地 init 且无 remote 的仓库路径点文件 */
export const LOCAL_REPO_POINT = '.repo-path-local';
/** E-15：留给该仓库添加的 bare 远程路径点文件 */
export const LOCAL_BARE_POINT = '.repo-bare-local';

/** E-16：分支切换夹具（干净仓库、main 与 feature 内容不同、带 bare 远程的 dev 分支）点文件 */
export const BRANCH_REPO_POINT = '.repo-path-branch';
/**
 * E-17：检出冲突夹具点文件。
 *
 * 远程 `origin/topic` 改过 README，本地 README 又已改脏 —— 检出它必然冲突，
 * 用来验证「失败后不留半成品分支」（同一入口原地重试仍是冲突而不是同名分支）。
 */
export const CHECKOUT_CONFLICT_REPO_POINT = '.repo-path-checkout-conflict';
/**
 * E-16：主仓库上故意造的「切过去会覆盖本地改动」的分支名。
 *
 * 它指向 `HEAD~5`，而 README.md 在工作区有未提交改动 —— 双击它必然被拒绝。
 */
export const CONFLICT_BRANCH = 'e2e-conflict';

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
  // 超长路径（未跟踪）：E-13 的布局回归前置
  mkdirSync(join(repo, LONG_PATH_DIR), { recursive: true });
  writeFileSync(join(repo, LONG_PATH_FILE), 'long path file\n');
  writeFileSync(join(repo, LONG_LINE_FILE), `${'x'.repeat(320)}\n`);

  // E-16 前置：造一个 README 与当前 HEAD 有差异的分支。
  // 上面已经把 README.md 改脏且未暂存，双击该分支必然触发 CHECKOUT_CONFLICT。
  git(['branch', CONFLICT_BRANCH, 'HEAD~5'], repo);

  writeFileSync(join(here, '.repo-path'), repo, 'utf8');

  // E-15 前置：本地 `git init` 且**没有任何 remote** 的仓库，外加一个空的 bare 远程。
  // 无 remote 时 Push 应可点击并引导打开「仓库设置 → 远程仓库」；
  // 添加 origin 后自动 Fetch 一次，Push 才真正可用（首次 push 需 setUpstream）。
  const localRepo = join(base, 'local-repo');
  const localBare = join(base, 'local-bare.git');
  git(['-c', 'init.defaultBranch=main', 'init', '-q', localRepo]);
  git(['config', 'user.email', 'e2e@synclavis.local'], localRepo);
  git(['config', 'user.name', 'e2e'], localRepo);
  writeFileSync(join(localRepo, 'README.md'), '# local only repo\n');
  git(['add', '.'], localRepo);
  git(['commit', '-qm', 'init'], localRepo);
  git(['-c', 'init.defaultBranch=main', 'init', '-q', '--bare', localBare]);

  writeFileSync(join(here, LOCAL_REPO_POINT), localRepo, 'utf8');
  writeFileSync(join(here, LOCAL_BARE_POINT), localBare, 'utf8');

  // E-16 前置：一个**干净**的仓库，用来验证双击切换分支。
  //   - `main` 与 `feature` 内容不同（提交历史会随 HEAD 变化，可直接断言）；
  //   - bare 远程上有 `dev` 分支，而本地**故意不留** dev 分支，
  //     这样双击侧栏的远程分支 `origin/dev` 才能真正演示「检出为本地跟踪分支」。
  const branchRepo = join(base, 'branch-repo');
  const branchBare = join(base, 'branch-remote.git');
  git(['-c', 'init.defaultBranch=main', 'init', '-q', branchRepo]);
  git(['config', 'user.email', 'e2e@synclavis.local'], branchRepo);
  git(['config', 'user.name', 'e2e'], branchRepo);
  writeFileSync(join(branchRepo, 'README.md'), '# branch repo main\n');
  git(['add', '.'], branchRepo);
  git(['commit', '-qm', 'branch-main-1'], branchRepo);
  git(['-c', 'init.defaultBranch=main', 'init', '-q', '--bare', branchBare]);
  git(['remote', 'add', 'origin', branchBare], branchRepo);
  git(['push', '-q', '-u', 'origin', 'main'], branchRepo);

  // 远程上的 dev 分支（双击远程分支 → 创建本地跟踪分支的前置）
  git(['checkout', '-q', '-b', 'dev'], branchRepo);
  writeFileSync(join(branchRepo, 'dev.txt'), 'dev only\n');
  git(['add', '.'], branchRepo);
  git(['commit', '-qm', 'branch-dev-1'], branchRepo);
  git(['push', '-q', '-u', 'origin', 'dev'], branchRepo);

  // feature 分支：内容与 main 有差异（切过去后提交历史与「无 upstream」标记都会变）
  git(['checkout', '-q', 'main'], branchRepo);
  git(['checkout', '-q', '-b', 'feature'], branchRepo);
  writeFileSync(join(branchRepo, 'feature.txt'), 'feature only\n');
  git(['add', '.'], branchRepo);
  git(['commit', '-qm', 'branch-feature-1'], branchRepo);

  // 回到 main；删掉本地 dev，只保留 origin/dev。
  // 顺序很重要：先切回 main 再删 dev，工作区最终与 main 完全一致（干净）。
  git(['checkout', '-q', 'main'], branchRepo);
  git(['branch', '-q', '-D', 'dev'], branchRepo);
  writeFileSync(join(here, BRANCH_REPO_POINT), branchRepo, 'utf8');

  // E-17 前置：远程分支 `origin/topic` 改过 README，本地 README 又已改脏
  //   → 双击它检出为本地跟踪分支必然冲突（README 会被覆盖），
  //     用来验证检出失败后会回滚掉刚建出的半成品分支。
  const checkoutRepo = join(base, 'checkout-conflict-repo');
  const checkoutBare = join(base, 'checkout-conflict-remote.git');
  git(['-c', 'init.defaultBranch=main', 'init', '-q', checkoutRepo]);
  git(['config', 'user.email', 'e2e@synclavis.local'], checkoutRepo);
  git(['config', 'user.name', 'e2e'], checkoutRepo);
  writeFileSync(join(checkoutRepo, 'README.md'), '# checkout conflict main\n');
  git(['add', '.'], checkoutRepo);
  git(['commit', '-qm', 'co-main-1'], checkoutRepo);
  git(['-c', 'init.defaultBranch=main', 'init', '-q', '--bare', checkoutBare]);
  git(['remote', 'add', 'origin', checkoutBare], checkoutRepo);
  git(['push', '-q', '-u', 'origin', 'main'], checkoutRepo);
  // topic 分支：README 与 main 不同
  git(['checkout', '-q', '-b', 'topic'], checkoutRepo);
  writeFileSync(join(checkoutRepo, 'README.md'), '# checkout conflict topic\n');
  git(['add', '.'], checkoutRepo);
  git(['commit', '-qm', 'co-topic-1'], checkoutRepo);
  git(['push', '-q', '-u', 'origin', 'topic'], checkoutRepo);
  // 回到 main 并删掉本地 topic，只保留 origin/topic
  git(['checkout', '-q', 'main'], checkoutRepo);
  git(['branch', '-q', '-D', 'topic'], checkoutRepo);
  // 本地 README 改脏（未暂存）：检出 origin/topic 会覆盖它
  writeFileSync(join(checkoutRepo, 'README.md'), '# checkout conflict dirty\n');
  writeFileSync(join(here, CHECKOUT_CONFLICT_REPO_POINT), checkoutRepo, 'utf8');

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

