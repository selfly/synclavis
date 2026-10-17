import type { FsEntryDto } from '@/api/fs';

/** 路径选择器的本地偏好（仅前端，localStorage 前缀 `synclavis.`） */
export interface PickerPrefs {
  lastDir: string | null;
  showHidden: boolean;
}

export const PICKER_PREFS_KEY = 'synclavis.pathPicker';

const EMPTY_PREFS: PickerPrefs = { lastDir: null, showHidden: false };

/** 默认隐藏 `.` 开头的条目 */
export function visibleEntries(entries: FsEntryDto[], showHidden: boolean): FsEntryDto[] {
  return showHidden ? entries : entries.filter((entry) => !entry.hidden);
}

/** 起点：输入框里的路径优先，其次上次浏览目录；都为空时交给后端回退用户主目录 */
export function resolvePickerPath(
  initialPath: string | null | undefined,
  prefs: PickerPrefs,
): string | null {
  const trimmed = (initialPath ?? '').trim();
  return trimmed.length > 0 ? trimmed : prefs.lastDir;
}

/** 取所在目录；没有分隔符（相对路径）时返回 null */
export function dirnameOf(path: string | null | undefined): string | null {
  const value = (path ?? '').trim();
  if (value.length === 0) {
    return null;
  }
  const index = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
  if (index < 0) {
    return null;
  }
  if (index === 0) {
    return value.slice(0, 1);
  }
  // Windows 盘符根（C:\）：保留分隔符，避免退化成 "C:"
  if (index === 2 && value.charAt(1) === ':') {
    return value.slice(0, 3);
  }
  return value.slice(0, index);
}

export function basenameOf(path: string | null | undefined): string {
  const value = (path ?? '').trim();
  if (value.length === 0) {
    return '';
  }
  const index = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
  return index >= 0 ? value.slice(index + 1) : value;
}

/** 容错读取：非法 JSON / 非法字段一律回退默认值（隐藏项默认关闭） */
export function readPickerPrefs(raw: string | null): PickerPrefs {
  if (!raw) {
    return { ...EMPTY_PREFS };
  }
  try {
    const parsed = JSON.parse(raw) as Partial<PickerPrefs> | null;
    if (!parsed || typeof parsed !== 'object') {
      return { ...EMPTY_PREFS };
    }
    return {
      lastDir:
        typeof parsed.lastDir === 'string' && parsed.lastDir.length > 0 ? parsed.lastDir : null,
      showHidden: parsed.showHidden === true,
    };
  } catch {
    return { ...EMPTY_PREFS };
  }
}

export function writePickerPrefs(prefs: PickerPrefs): string {
  return JSON.stringify({ lastDir: prefs.lastDir, showHidden: prefs.showHidden });
}
