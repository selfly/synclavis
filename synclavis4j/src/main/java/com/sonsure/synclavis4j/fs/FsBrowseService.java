package com.sonsure.synclavis4j.fs;

import com.sonsure.synclavis4j.fs.dto.FsBreadcrumbDto;
import com.sonsure.synclavis4j.fs.dto.FsEntryDto;
import com.sonsure.synclavis4j.fs.dto.FsListDto;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import com.sonsure.synclavis4j.shared.util.RepoId;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 只读的目录浏览：给前端路径选择器提供本机目录列表。
 *
 * <p>浏览器拿不到本机绝对路径（File System Access API 只给目录名/句柄），
 * 所以「选择路径」必须由后端列目录。这里只返回名称与类型，<b>不读取任何文件内容</b>。</p>
 */
@Service
public class FsBrowseService {

    /** 单次返回的条目上限，避免超大目录拖垮界面 */
    private static final int MAX_ENTRIES = 2000;

    private static final String KIND_DIR = "dir";
    private static final String KIND_FILE = "file";

    public FsListDto list(String rawPath, String rawKind) {
        String kind = normalizeKind(rawKind);
        boolean includeFiles = KIND_FILE.equals(kind);
        File dir = resolveDir(rawPath);

        FsListDto dto = new FsListDto();
        dto.setPath(dir.getAbsolutePath());
        File parent = dir.getParentFile();
        dto.setParent(parent == null ? null : parent.getAbsolutePath());
        dto.setRoots(listRoots());
        dto.setBreadcrumbs(buildBreadcrumbs(dir));

        File[] children = dir.listFiles();
        if (children == null) {
            throw new GitOperationException(ErrorCode.PERMISSION_DENIED, "没有权限读取目录：" + dir.getAbsolutePath());
        }

        List<File> dirs = new ArrayList<File>();
        List<File> files = new ArrayList<File>();
        for (File child : children) {
            if (child.isDirectory()) {
                dirs.add(child);
            } else if (includeFiles && child.isFile()) {
                files.add(child);
            }
        }
        sortByName(dirs);
        sortByName(files);

        List<FsEntryDto> entries = new ArrayList<FsEntryDto>();
        boolean truncated = false;
        for (File child : dirs) {
            if (entries.size() >= MAX_ENTRIES) {
                truncated = true;
                break;
            }
            entries.add(toEntry(child, true));
        }
        if (!truncated) {
            for (File child : files) {
                if (entries.size() >= MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                entries.add(toEntry(child, false));
            }
        }

        dto.setEntries(entries);
        dto.setTruncated(truncated);
        return dto;
    }

    private String normalizeKind(String rawKind) {
        if (rawKind == null || rawKind.trim().isEmpty()) {
            return KIND_DIR;
        }
        String kind = rawKind.trim().toLowerCase(Locale.ROOT);
        if (KIND_DIR.equals(kind) || KIND_FILE.equals(kind)) {
            return kind;
        }
        throw new GitOperationException(ErrorCode.INVALID_PATH, "kind 只能是 dir 或 file：" + rawKind);
    }

    private File resolveDir(String rawPath) {
        String path = rawPath == null ? "" : rawPath.trim();
        String home = System.getProperty("user.home");
        if (path.isEmpty() || "~".equals(path)) {
            path = home;
        } else if (path.startsWith("~/") || path.startsWith("~\\")) {
            path = home + path.substring(1);
        }

        File candidate = new File(path);
        if (!candidate.isAbsolute()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "路径必须是绝对路径：" + path);
        }

        File dir = new File(RepoId.canonicalize(path));
        if (!dir.exists()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "路径不存在：" + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "不是目录：" + dir.getAbsolutePath());
        }
        if (!dir.canRead()) {
            throw new GitOperationException(ErrorCode.PERMISSION_DENIED, "没有权限读取目录：" + dir.getAbsolutePath());
        }
        return dir;
    }

    private List<String> listRoots() {
        List<String> roots = new ArrayList<String>();
        File[] files = File.listRoots();
        if (files != null) {
            for (File root : files) {
                roots.add(root.getAbsolutePath());
            }
        }
        if (roots.isEmpty()) {
            roots.add(File.separator);
        }
        return roots;
    }

    private List<FsBreadcrumbDto> buildBreadcrumbs(File dir) {
        List<FsBreadcrumbDto> crumbs = new ArrayList<FsBreadcrumbDto>();
        File cursor = dir;
        while (cursor != null) {
            String name = cursor.getName();
            if (name == null || name.isEmpty()) {
                name = cursor.getPath();
            }
            crumbs.add(0, new FsBreadcrumbDto(name, cursor.getAbsolutePath()));
            cursor = cursor.getParentFile();
        }
        return crumbs;
    }

    private void sortByName(List<File> files) {
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
    }

    private FsEntryDto toEntry(File file, boolean isDir) {
        String name = file.getName();
        boolean hidden = name.startsWith(".");
        boolean repo = isDir && isGitRepository(file);
        return new FsEntryDto(name, file.getAbsolutePath(), isDir, repo, hidden);
    }

    /**
     * 与 JGit 的 findGitDir 对齐：工作区/子模块（.git 目录或文件）与裸仓库（HEAD + objects + refs）。
     */
    private boolean isGitRepository(File dir) {
        if (new File(dir, ".git").exists()) {
            return true;
        }
        return new File(dir, "HEAD").isFile()
                && new File(dir, "objects").isDirectory()
                && new File(dir, "refs").isDirectory();
    }
}
