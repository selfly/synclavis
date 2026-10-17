/**
 * 分支的纯函数：本地分支名校验与切换失败的冲突解析。
 *
 * 校验规则必须与后端 `GitBranchService#checkout` 的 JGit 校验保持一致
 * （`Repository.isValidRefName("refs/heads/" + name)` + `CreateBranchCommand.isValidBranchName`）：
 * **分支名允许含 `/`**（如 `feature/x`），这与 remote 名校验规则（明确禁 `/`）不同，不能共用。
 * 前端只做即时提示，最终以后端为准。
 */

/** 分支是否合法：非空、非 `HEAD`、不以 `-` 开头、不含 `..`/`@{`、不以 `.` 开头或结尾的层级、无空白与 `~^:?*[\`。 */
export function isValidBranchName(name: string): boolean {
  const value = name.trim();
  if (value.length === 0) {
    return false;
  }
  if (value === 'HEAD' || value === '@') {
    return false;
  }
  if (value.startsWith('-') || value.startsWith('/') || value.endsWith('/')) {
    return false;
  }
  if (value.includes('//') || value.includes('..') || value.includes('@{')) {
    return false;
  }
  if (value.endsWith('.lock')) {
    return false;
  }
  // 控制字符、空白与 git 禁用符号
  if (/[\u0000-\u0020\u007f~^:?*[\\]/.test(value)) {
    return false;
  }
  // 每一层级都不得以 `.` 开头或结尾
  return value.split('/').every((part) => part.length > 0 && !part.startsWith('.') && !part.endsWith('.'));
}

/**
 * 从切换失败的异常里取出「会被覆盖的文件」。
 *
 * 后端 `CHECKOUT_CONFLICT` 的 `details` 形如 `{ files: ["README.md"] }`。
 */
export function extractConflictFiles(error: unknown): string[] {
  const details = (error as { details?: unknown } | null | undefined)?.details;
  if (!details || typeof details !== 'object') {
    return [];
  }
  const files = (details as { files?: unknown }).files;
  if (!Array.isArray(files)) {
    return [];
  }
  return files.map((file) => String(file));
}
