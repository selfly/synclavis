package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.git.dto.DiffEntryDto;
import com.sonsure.synclavis4j.git.dto.DiffResponseDto;
import com.sonsure.synclavis4j.repo.RepoRepositoryManager;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.springframework.stereotype.Service;

/**
 * Diff 计算：结构化变更列表 + 可选单文件 patch。
 */
@Service
public class GitDiffService extends AbstractGitService {

    private static final int MAX_ENTRIES = 2000;
    private static final int MAX_PATCH_CHARS = 2000000;

    public GitDiffService(RepoRepositoryManager repositories, RepoService repoService) {
        super(repositories, repoService);
    }

    public DiffResponseDto commitDiff(String repoId, String commitId, String path) {
        Repository repository = repo(repoId);
        RevWalk walk = new RevWalk(repository);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        DiffFormatter formatter = new DiffFormatter(sink);
        try {
            ObjectId id = repository.resolve(commitId);
            if (id == null) {
                throw new GitOperationException(ErrorCode.NOT_FOUND, "提交不存在：" + commitId);
            }
            RevCommit commit;
            try {
                commit = walk.parseCommit(id);
            } catch (MissingObjectException e) {
                // 合法格式但对象不存在：应是 404，而不是 500
                throw new GitOperationException(ErrorCode.NOT_FOUND, "提交不存在：" + commitId, null, e);
            } catch (IncorrectObjectTypeException e) {
                throw new GitOperationException(ErrorCode.NOT_FOUND, "不是提交对象：" + commitId, null, e);
            }
            RevCommit parent = commit.getParentCount() > 0 ? walk.parseCommit(commit.getParent(0)) : null;

            ObjectReader reader = walk.getObjectReader();
            AbstractTreeIterator oldTree = parent == null
                    ? new EmptyTreeIterator()
                    : new CanonicalTreeParser(null, reader, parent.getTree());
            AbstractTreeIterator newTree = new CanonicalTreeParser(null, reader, commit.getTree());

            formatter.setRepository(repository);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);

            List<DiffEntry> entries = formatter.scan(oldTree, newTree);
            String target = path == null ? null : path.trim();

            List<DiffEntryDto> dtos = new ArrayList<DiffEntryDto>();
            String patch = null;
            for (DiffEntry entry : entries) {
                if (dtos.size() >= MAX_ENTRIES) {
                    break;
                }
                String text = render(formatter, sink, entry);
                dtos.add(toDto(entry, text));

                if (patch == null && target != null && !target.isEmpty() && matches(entry, target)) {
                    patch = truncatePatch(text);
                }
            }
            return new DiffResponseDto(dtos, patch);
        } catch (IOException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "计算 Diff 失败：" + e.getMessage(), null, e);
        } finally {
            formatter.close();
            walk.close();
        }
    }

    /**
     * 暂存区/工作区单文件差异。
     *
     * <p>{@code staged=true} 比较 HEAD ↔ 索引（"已暂存"的内容）；
     * {@code staged=false} 比较 索引 ↔ 工作区（"未暂存"的内容，未跟踪文件按 ADD 呈现）。
     * 复用 {@link DiffResponseDto}，但 {@code entries} 只含被请求文件这一条，
     * 前端因此不需要第二套渲染路径。</p>
     *
     * <p>该路径当前没有差异时返回空 entries + null patch（200），不报错：
     * 状态刷新与点击之间存在竞态，陈旧选择不应变成错误提示。</p>
     */
    public DiffResponseDto worktreeDiff(String repoId, String path, boolean staged) {
        Repository repository = repo(repoId);
        String target = normalizeWorktreePath(path);

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        DiffFormatter formatter = new DiffFormatter(sink);
        DirCacheIterator indexIterator = null;
        FileTreeIterator worktreeIterator = null;
        try {
            formatter.setRepository(repository);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);

            List<DiffEntry> entries;
            if (staged) {
                // HEAD ↔ 索引
                AbstractTreeIterator headTree = headTreeIterator(repository);
                indexIterator = new DirCacheIterator(repository.readDirCache());
                entries = formatter.scan(headTree, indexIterator);
            } else {
                // 索引 ↔ 工作区
                indexIterator = new DirCacheIterator(repository.readDirCache());
                worktreeIterator = new FileTreeIterator(repository);
                entries = formatter.scan(indexIterator, worktreeIterator);
            }

            for (DiffEntry entry : entries) {
                if (!matches(entry, target)) {
                    continue;
                }
                String text = render(formatter, sink, entry);
                List<DiffEntryDto> single = new ArrayList<DiffEntryDto>();
                single.add(toDto(entry, text));
                return new DiffResponseDto(single, truncatePatch(text));
            }
            return new DiffResponseDto(new ArrayList<DiffEntryDto>(), null);
        } catch (IOException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "计算工作区 Diff 失败：" + e.getMessage(), null, e);
        } finally {
            formatter.close();
        }
    }

    /** HEAD 树迭代器；无 HEAD（全新仓库）时用空树，使索引内容整体呈现为 ADD。 */
    private AbstractTreeIterator headTreeIterator(Repository repository) throws IOException {
        ObjectId head = repository.resolve(Constants.HEAD);
        if (head == null) {
            return new EmptyTreeIterator();
        }
        RevWalk walk = new RevWalk(repository);
        try {
            RevCommit commit = walk.parseCommit(head);
            return new CanonicalTreeParser(null, walk.getObjectReader(), commit.getTree());
        } finally {
            walk.close();
        }
    }

    /**
     * 校验并规范化仓库内相对路径。
     *
     * <p>路径来自前端，必须挡掉绝对路径与 {@code ..} 越界；
     * 其余情况即使匹配不到条目也只会得到空结果，不会触碰仓库外的文件。</p>
     */
    private String normalizeWorktreePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "文件路径不能为空");
        }
        String value = path.trim().replace('\\', '/');
        if (value.startsWith("/") || value.contains(":") || value.contains("\u0000")) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "只接受仓库内相对路径：" + path);
        }
        for (String segment : value.split("/")) {
            if ("..".equals(segment)) {
                throw new GitOperationException(ErrorCode.INVALID_PATH, "路径不能越出仓库：" + path);
            }
        }
        return value;
    }

    private boolean matches(DiffEntry entry, String target) {
        return target.equals(entry.getNewPath()) || target.equals(entry.getOldPath());
    }

    private DiffEntryDto toDto(DiffEntry entry, String text) {
        String newPath = DiffEntry.DEV_NULL.equals(entry.getNewPath())
                ? entry.getOldPath() : entry.getNewPath();
        String oldPath = DiffEntry.DEV_NULL.equals(entry.getOldPath())
                ? null : entry.getOldPath();

        DiffEntryDto dto = new DiffEntryDto();
        dto.setChangeType(entry.getChangeType().name());
        dto.setPath(newPath);
        dto.setOldPath(oldPath);
        if (isBinary(text)) {
            dto.setBinary(true);
        } else {
            int[] stats = countChangedLines(text);
            dto.setInsertions(stats[0]);
            dto.setDeletions(stats[1]);
        }
        return dto;
    }

    private String truncatePatch(String text) {
        return text.length() > MAX_PATCH_CHARS
                ? text.substring(0, MAX_PATCH_CHARS) + "\n... (patch 已截断)"
                : text;
    }

    private String render(DiffFormatter formatter, ByteArrayOutputStream sink, DiffEntry entry) throws IOException {
        sink.reset();
        formatter.format(entry);
        return new String(sink.toByteArray(), StandardCharsets.UTF_8);
    }

    private boolean isBinary(String patch) {
        return patch.contains("Binary files") || patch.contains("GIT binary patch");
    }

    /** 统计 hunk 内的新增/删除行数（忽略 +++/--- 头）。 */
    private int[] countChangedLines(String patch) {
        int added = 0;
        int deleted = 0;
        boolean inHunk = false;
        String[] lines = patch.split("\n", -1);
        for (String line : lines) {
            if (line.startsWith("@@")) {
                inHunk = true;
                continue;
            }
            if (!inHunk || line.startsWith("+++") || line.startsWith("---")) {
                continue;
            }
            if (line.startsWith("+")) {
                added++;
            } else if (line.startsWith("-")) {
                deleted++;
            }
        }
        return new int[]{added, deleted};
    }
}
