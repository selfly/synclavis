package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.git.dto.CommitDto;
import com.sonsure.synclavis4j.git.dto.LogResponseDto;
import com.sonsure.synclavis4j.repo.RepoRepositoryManager;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.AndRevFilter;
import org.eclipse.jgit.revwalk.filter.AuthorRevFilter;
import org.eclipse.jgit.revwalk.filter.MessageRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.springframework.stereotype.Service;

/**
 * 提交历史查询（分页 + 作者/关键字过滤 + ref 装饰）。
 */
@Service
public class GitLogService extends AbstractGitService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final int COUNT_CAP = 10000;
    private static final int DEFAULT_LIMIT = 100;

    public GitLogService(RepoRepositoryManager repositories, RepoService repoService) {
        super(repositories, repoService);
    }

    public LogResponseDto log(String repoId, int skip, int limit, String author, String keyword, String ref,
            boolean all, String order) {
        Repository repository = repo(repoId);
        try (Git git = Git.wrap(repository)) {
            List<ObjectId> starts = resolveStarts(repository, ref, all);
            if (starts.isEmpty()) {
                return new LogResponseDto(0, new ArrayList<CommitDto>());
            }

            RevFilter filter = buildFilter(author, keyword);
            Map<String, List<String>> decorations = decorations(git);

            int from = Math.max(skip, 0);
            int max = limit <= 0 ? DEFAULT_LIMIT : limit;
            List<CommitDto> commits = new ArrayList<CommitDto>();
            RevWalk walk = new RevWalk(repository);
            try {
                // 显式排序（见《详细设计文档》§6.2 排序契约）：LogCommand 内部自建 RevWalk，注入不了排序
                applyOrder(walk, order);
                walk.setRevFilter(filter == null ? RevFilter.ALL : filter);
                if (markStarts(walk, starts) == 0) {
                    return new LogResponseDto(0, new ArrayList<CommitDto>());
                }
                int index = 0;
                for (RevCommit commit : walk) {
                    if (index++ < from) {
                        continue;
                    }
                    if (commits.size() >= max) {
                        break;
                    }
                    commits.add(toDto(commit, decorations));
                }
            } finally {
                walk.close();
            }
            return new LogResponseDto(count(repository, starts, filter), commits);
        } catch (GitAPIException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "读取提交历史失败：" + e.getMessage(), null, e);
        } catch (IOException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "读取提交历史失败：" + e.getMessage(), null, e);
        }
    }

    /**
     * 遍历起点（见《详细设计文档》§6.2「引用范围契约」）。
     *
     * <p>`ref` 非空时以它为准、忽略 `all`；否则缺省从 `HEAD` 开始，`all=true` 时再加上
     * `refs/heads/*`、`refs/remotes/*`、`refs/tags/*`（annotated tag 先 peel），等价 `git log --all`——
     * 这样「只被某个远程分支指向、当前分支不可达」的提交也能出现在列表里。</p>
     */
    private List<ObjectId> resolveStarts(Repository repository, String ref, boolean all) throws IOException {
        List<ObjectId> starts = new ArrayList<ObjectId>();
        if (ref != null && !ref.trim().isEmpty()) {
            addStart(starts, repository.resolve(ref.trim()));
            return starts;
        }
        addStart(starts, repository.resolve(Constants.HEAD));
        if (!all) {
            return starts;
        }
        for (Ref candidate : repository.getRefDatabase()
                .getRefsByPrefix(Constants.R_HEADS, Constants.R_REMOTES, Constants.R_TAGS)) {
            addStart(starts, peel(repository, candidate));
        }
        return starts;
    }

    private void addStart(List<ObjectId> starts, ObjectId id) {
        if (id != null && !starts.contains(id)) {
            starts.add(id);
        }
    }

    /** 返回成功 markStart 的起点数；指向 blob/tree 的 tag 与已失联的对象直接跳过。 */
    private int markStarts(RevWalk walk, List<ObjectId> starts) throws IOException {
        int marked = 0;
        for (ObjectId start : starts) {
            try {
                walk.markStart(walk.parseCommit(start));
                marked++;
            } catch (IncorrectObjectTypeException e) {
                // tag 指向 blob/tree：不是提交，跳过
            } catch (MissingObjectException e) {
                // 对象已缺失（例如坏引用）：跳过
            }
        }
        return marked;
    }

    /**
     * 提交列表排序（见《详细设计文档》§6.2 排序契约）。
     *
     * <ul>
     *   <li>{@code date}（缺省）：{@link RevSort#TOPO} + {@link RevSort#COMMIT_TIME_DESC}，等价
     *       {@code git log --date-order}——父必在子之后，其余按提交时间倒序，同一分支的提交会被其它
     *       分支穿插（SourceGit 的默认观感）。{@code COMMIT_TIME_DESC} 必须与 {@code TOPO} 一起开：
     *       只开日期排序在「时间戳倒挂」的链上会先吐更旧的父提交。</li>
     *   <li>{@code topo}：{@link RevSort#TOPO_KEEP_BRANCH_TOGETHER}（≈ {@code git log --topo-order}），
     *       同一分支的提交扎堆连续。**不能与 {@code TOPO} 同时启用**：JGit 的 StartGenerator 会直接抛
     *       {@code IllegalStateException}。</li>
     * </ul>
     *
     * <p>未知取值（含 null / 空串）一律回落 {@code date}，不报错（契约里写明）。</p>
     */
    private void applyOrder(RevWalk walk, String order) {
        if ("topo".equalsIgnoreCase(order == null ? null : order.trim())) {
            walk.sort(RevSort.TOPO_KEEP_BRANCH_TOGETHER, true);
            return;
        }
        walk.sort(RevSort.COMMIT_TIME_DESC, true);
        walk.sort(RevSort.TOPO, true);
    }

    private RevFilter buildFilter(String author, String keyword) {
        RevFilter filter = null;
        if (author != null && !author.trim().isEmpty()) {
            filter = AuthorRevFilter.create(author.trim());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            RevFilter message = MessageRevFilter.create(keyword.trim());
            filter = filter == null ? message : AndRevFilter.create(filter, message);
        }
        return filter;
    }

    private int count(Repository repository, List<ObjectId> starts, RevFilter filter) throws IOException {
        RevWalk walk = new RevWalk(repository);
        try {
            walk.setRevFilter(filter == null ? RevFilter.ALL : filter);
            if (markStarts(walk, starts) == 0) {
                return 0;
            }
            int total = 0;
            for (RevCommit ignored : walk) {
                total++;
                if (total >= COUNT_CAP) {
                    break;
                }
            }
            return total;
        } finally {
            walk.close();
        }
    }

    /** 无 ref 装饰的提交信息（推送预览等只需提交本身的地方复用）。 */
    static CommitDto toDto(RevCommit commit) {
        return toDto(commit, null);
    }

    /** {@code decorations} 为 {@code null} 时 {@code refs} 恒为空数组。 */
    static CommitDto toDto(RevCommit commit, Map<String, List<String>> decorations) {
        CommitDto dto = new CommitDto();
        dto.setId(commit.getName());
        dto.setShortId(commit.abbreviate(8).name());
        dto.setSubject(commit.getShortMessage());
        dto.setAuthor(commit.getAuthorIdent().getName());
        dto.setEmail(commit.getAuthorIdent().getEmailAddress());
        dto.setTime(OffsetDateTime
                .ofInstant(commit.getAuthorIdent().getWhen().toInstant(), ZoneId.systemDefault())
                .format(TIME_FORMAT));
        List<String> parents = new ArrayList<String>();
        for (RevCommit parent : commit.getParents()) {
            parents.add(parent.getName());
        }
        dto.setParents(parents);
        List<String> refs = decorations == null ? null : decorations.get(commit.getName());
        dto.setRefs(refs == null ? new ArrayList<String>() : refs);
        return dto;
    }

    /** commitId -> [分支/标签/HEAD 短名] */
    private Map<String, List<String>> decorations(Git git) throws GitAPIException, IOException {
        Repository repository = git.getRepository();
        Map<String, List<String>> map = new HashMap<String, List<String>>();
        for (Ref ref : git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()) {
            addDecoration(map, peel(repository, ref), Repository.shortenRefName(ref.getName()));
        }
        for (Ref ref : git.tagList().call()) {
            addDecoration(map, peel(repository, ref), Repository.shortenRefName(ref.getName()));
        }
        if (repository.getBranch() != null) {
            addDecoration(map, repository.resolve(Constants.HEAD), "HEAD");
        }
        return map;
    }

    private ObjectId peel(Repository repository, Ref ref) {
        try {
            Ref peeled = repository.getRefDatabase().peel(ref);
            if (peeled.getPeeledObjectId() != null) {
                return peeled.getPeeledObjectId();
            }
            return peeled.getObjectId();
        } catch (IOException e) {
            return ref.getObjectId();
        }
    }

    private void addDecoration(Map<String, List<String>> map, ObjectId id, String name) {
        if (id == null) {
            return;
        }
        String key = id.getName();
        List<String> list = map.get(key);
        if (list == null) {
            list = new ArrayList<String>();
            map.put(key, list);
        }
        if (!list.contains(name)) {
            list.add(name);
        }
    }
}
