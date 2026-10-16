package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.git.dto.BranchInfoDto;
import com.sonsure.synclavis4j.git.dto.TagInfoDto;
import com.sonsure.synclavis4j.repo.RepoRepositoryManager;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Service;

/**
 * 分支与标签查询。
 */
@Service
public class GitBranchService extends AbstractGitService {

    private static final int MAX_TAGS = 1000;

    public GitBranchService(RepoRepositoryManager repositories, RepoService repoService) {
        super(repositories, repoService);
    }

    /** 本地 + 远程分支；当前分支附带 ahead/behind。 */
    public List<BranchInfoDto> branches(String repoId) {
        Repository repository = repo(repoId);
        try (Git git = Git.wrap(repository)) {
            List<Ref> refs = git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call();
            String current = repository.getBranch();
            List<BranchInfoDto> result = new ArrayList<BranchInfoDto>();
            for (Ref ref : refs) {
                String fullName = ref.getName();
                boolean local = fullName.startsWith(Constants.R_HEADS);
                String name = Repository.shortenRefName(fullName);
                boolean isCurrent = local && name.equals(current);
                BranchInfoDto dto = new BranchInfoDto(name, fullName, local, isCurrent);
                if (isCurrent) {
                    BranchTrackingStatus tracking = BranchTrackingStatus.of(repository, name);
                    if (tracking != null) {
                        dto.setAhead(tracking.getAheadCount());
                        dto.setBehind(tracking.getBehindCount());
                    }
                }
                result.add(dto);
            }
            return result;
        } catch (GitAPIException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "读取分支失败：" + e.getMessage(), null, e);
        } catch (IOException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "读取分支失败：" + e.getMessage(), null, e);
        }
    }

    /** 标签列表（附指向的 commit 与注解消息）。JGit 中命令类名为 ListTagCommand。 */
    public List<TagInfoDto> tags(String repoId) {
        Repository repository = repo(repoId);
        List<TagInfoDto> result = new ArrayList<TagInfoDto>();
        try (Git git = Git.wrap(repository)) {
            List<Ref> refs = git.tagList().call();
            RevWalk walk = new RevWalk(repository);
            try {
                for (Ref ref : refs) {
                    if (result.size() >= MAX_TAGS) {
                        break;
                    }
                    String name = Repository.shortenRefName(ref.getName());
                    String commitId = ref.getObjectId() == null ? null : ref.getObjectId().getName();
                    String message = null;
                    if (ref.getObjectId() != null) {
                        RevObject any = walk.parseAny(ref.getObjectId());
                        if (any instanceof RevTag) {
                            RevTag tag = (RevTag) any;
                            RevObject target = walk.parseAny(tag.getObject());
                            commitId = target.getId().getName();
                            message = tag.getFullMessage();
                        }
                    }
                    result.add(new TagInfoDto(name, commitId, message));
                }
            } finally {
                walk.close();
            }
            return result;
        } catch (GitAPIException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "读取标签失败：" + e.getMessage(), null, e);
        } catch (IOException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "读取标签失败：" + e.getMessage(), null, e);
        }
    }
}
