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
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
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

    public LogResponseDto log(String repoId, int skip, int limit, String author, String keyword, String ref) {
        Repository repository = repo(repoId);
        try (Git git = Git.wrap(repository)) {
            ObjectId start = (ref == null || ref.trim().isEmpty())
                    ? repository.resolve(Constants.HEAD)
                    : repository.resolve(ref.trim());
            if (start == null) {
                return new LogResponseDto(0, new ArrayList<CommitDto>());
            }

            RevFilter filter = buildFilter(author, keyword);
            Map<String, List<String>> decorations = decorations(git);

            LogCommand command = git.log()
                    .setSkip(Math.max(skip, 0))
                    .setMaxCount(limit <= 0 ? DEFAULT_LIMIT : limit);
            if (filter != null) {
                command.setRevFilter(filter);
            }
            command.add(start);

            List<CommitDto> commits = new ArrayList<CommitDto>();
            for (RevCommit commit : command.call()) {
                commits.add(toDto(commit, decorations));
            }
            return new LogResponseDto(count(repository, start, filter), commits);
        } catch (GitAPIException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "读取提交历史失败：" + e.getMessage(), null, e);
        } catch (IOException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "读取提交历史失败：" + e.getMessage(), null, e);
        }
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

    private int count(Repository repository, ObjectId start, RevFilter filter) throws IOException {
        RevWalk walk = new RevWalk(repository);
        try {
            walk.setRevFilter(filter == null ? RevFilter.ALL : filter);
            walk.markStart(walk.parseCommit(start));
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

    private CommitDto toDto(RevCommit commit, Map<String, List<String>> decorations) {
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
        List<String> refs = decorations.get(commit.getName());
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
