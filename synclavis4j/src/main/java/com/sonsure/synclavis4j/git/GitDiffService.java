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
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
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
                String newPath = DiffEntry.DEV_NULL.equals(entry.getNewPath())
                        ? entry.getOldPath() : entry.getNewPath();
                String oldPath = DiffEntry.DEV_NULL.equals(entry.getOldPath())
                        ? null : entry.getOldPath();

                DiffEntryDto dto = new DiffEntryDto();
                dto.setChangeType(entry.getChangeType().name());
                dto.setPath(newPath);
                dto.setOldPath(oldPath);

                String text = render(formatter, sink, entry);
                if (isBinary(text)) {
                    dto.setBinary(true);
                } else {
                    int[] stats = countChangedLines(text);
                    dto.setInsertions(stats[0]);
                    dto.setDeletions(stats[1]);
                }
                dtos.add(dto);

                if (patch == null && target != null && !target.isEmpty()
                        && (target.equals(newPath) || target.equals(entry.getOldPath()))) {
                    patch = text.length() > MAX_PATCH_CHARS
                            ? text.substring(0, MAX_PATCH_CHARS) + "\n... (patch 已截断)"
                            : text;
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
