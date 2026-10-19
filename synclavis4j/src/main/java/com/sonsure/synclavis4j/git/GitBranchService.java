package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.git.dto.BranchInfoDto;
import com.sonsure.synclavis4j.git.dto.CheckoutRequest;
import com.sonsure.synclavis4j.git.dto.RepoStatusDto;
import com.sonsure.synclavis4j.git.dto.TagInfoDto;
import com.sonsure.synclavis4j.repo.RepoRepositoryManager;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 分支与标签：查询 + 切换。
 */
@Service
public class GitBranchService extends AbstractGitService {

    private static final Logger log = LoggerFactory.getLogger(GitBranchService.class);

    private static final int MAX_TAGS = 1000;

    private final GitWriteExecutor writeExecutor;
    private final GitStatusService statusService;

    public GitBranchService(RepoRepositoryManager repositories, RepoService repoService,
                            GitWriteExecutor writeExecutor, GitStatusService statusService) {
        super(repositories, repoService);
        this.writeExecutor = writeExecutor;
        this.statusService = statusService;
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

    /**
     * 切换分支（{@code create=true} 时新建并切换）。
     *
     * <p>走统一写锁（{@code kind="checkout"}），因此异步写操作进行中会报 {@link ErrorCode#REPO_BUSY}；
     * {@code checkout} 已加入 rebase 中间态拦截集合（切换会破坏 rebase 状态）。</p>
     *
     * <p>工作区有改动时<b>不做预判</b>：能带走的改动会被带走，只有会被覆盖的文件才失败
     * （JGit 默认 {@code failOnConflict=true}），此时返回 {@link ErrorCode#CHECKOUT_CONFLICT}
     * 并在 {@code details.files} 里给出被覆盖的路径。</p>
     *
     * <p><b>所有 JGit 异常必须在此捕获</b>：{@code GlobalExceptionHandler} 把
     * {@code CheckoutConflictException} 当作 merge/pull 的合并冲突映射为
     * {@link ErrorCode#MERGE_CONFLICT}，逃出去会显示成「合并存在冲突」。</p>
     *
     * <p>{@code create=true} 失败时会<b>回滚刚建出的分支</b>（含 upstream 配置）：JGit 的
     * {@code CheckoutCommand} 是先建分支、再切工作区，冲突时分支已经落盘；不回滚的话用户
     * 改完工作区原地重试会撞 {@link ErrorCode#BRANCH_EXISTS}。</p>
     */
    public RepoStatusDto checkout(final String repoId, final CheckoutRequest request) {
        final CheckoutRequest req = request == null ? new CheckoutRequest() : request;
        final String branch = req.getBranch() == null ? null : req.getBranch().trim();
        if (branch == null || branch.isEmpty()) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "分支名不能为空");
        }
        validateBranchName(branch);
        final String startPoint = req.getStartPoint() == null ? null : req.getStartPoint().trim();

        return writeExecutor.runSync(repoId, "checkout", new GitWriteExecutor.SyncTask<RepoStatusDto>() {
            @Override
            public RepoStatusDto run(String operationId) throws Exception {
                Repository repository = repo(repoId);
                // unborn HEAD：git init 之后一次都没提交过，既没有分支可切也没有起点可用
                if (repository.resolve(Constants.HEAD) == null) {
                    throw new GitOperationException(ErrorCode.INVALID_PARAM,
                            "仓库还没有任何提交，请先创建一次提交");
                }
                try (Git git = Git.wrap(repository)) {
                    if (req.isCreate()) {
                        // 不能同时存在 `feature` 与 `feature/x`（git 的目录/文件冲突）。
                        // 不拦的话 JGit 会抛 JGitInternalException(LOCK_FAILURE)，变成 500。
                        ensureNoBranchNameConflict(git, branch);
                    } else if (repository.exactRef(Constants.R_HEADS + branch) == null) {
                        // create=false 只允许切到「已存在的本地分支」。
                        // 放过去的话 JGit 会把 HEAD / origin/dev / commit id 解析成 detached HEAD。
                        throw new GitOperationException(ErrorCode.INVALID_PARAM,
                                "本地分支不存在：" + branch);
                    }
                    // 失败回滚的前提：这个分支是本次调用新建的。
                    // existedBefore=true 时失败只可能发生在建分支之前，那个 ref 不是我们的，绝不能删。
                    final boolean existedBefore = repository.exactRef(Constants.R_HEADS + branch) != null;
                    CheckoutCommand command = git.checkout()
                            .setName(branch)
                            .setCreateBranch(req.isCreate());
                    if (req.isCreate() && startPoint != null && !startPoint.isEmpty()) {
                        // 起点为远程跟踪名（origin/dev）时，CreateBranchCommand 会按
                        // branch.autoSetupMerge 的默认行为自动建立 upstream
                        command.setStartPoint(startPoint);
                    }
                    boolean switched = false;
                    try {
                        command.call();
                        switched = true;
                    } finally {
                        if (req.isCreate() && !switched && !existedBefore) {
                            deleteHalfCreatedBranch(git, Constants.R_HEADS + branch);
                        }
                    }
                } catch (CheckoutConflictException e) {
                    throw new GitOperationException(ErrorCode.CHECKOUT_CONFLICT,
                            "本地改动会被覆盖，请先提交或 Stash",
                            GitOperationException.conflictDetails(e.getConflictingPaths()), e);
                } catch (RefAlreadyExistsException e) {
                    throw new GitOperationException(ErrorCode.BRANCH_EXISTS,
                            "本地已存在同名分支：" + branch, null, e);
                } catch (RefNotFoundException e) {
                    throw new GitOperationException(ErrorCode.INVALID_PARAM,
                            "分支或起点不存在：" + branch, null, e);
                } catch (InvalidRefNameException e) {
                    throw new GitOperationException(ErrorCode.INVALID_PARAM,
                            "非法的分支名：" + branch, null, e);
                } catch (UnsupportedOperationException e) {
                    // 兜底：JGit 对 unborn HEAD 会抛这个（正常情况下已被上面 resolve(HEAD) 的校验拦住）
                    throw new GitOperationException(ErrorCode.INVALID_PARAM,
                            "仓库还没有任何提交，请先创建一次提交", null, e);
                } catch (GitAPIException | JGitInternalException e) {
                    throw new GitOperationException(ErrorCode.INTERNAL,
                            "切换分支失败：" + e.getMessage(), null, e);
                }
                return statusService.status(repoId);
            }
        });
    }

    /**
     * 分支名合法性（契约见《详细设计文档》§6.2）。
     *
     * <p>与 JGit 的校验保持一致：{@code refs/heads/} + name 必须是合法引用名，
     * 且禁用 {@code HEAD} 与前导 {@code -}。**允许含 `/`**（如 {@code feature/x}）。</p>
     */
    private static void validateBranchName(String branch) {
        if ("HEAD".equals(branch)) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "HEAD 不是可切换的分支名");
        }
        if (branch.startsWith("-")) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "分支名不能以 - 开头：" + branch);
        }
        if (!Repository.isValidRefName(Constants.R_HEADS + branch)) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "非法的分支名：" + branch);
        }
    }

    /**
     * 回滚「新建分支 + 切换」失败后残留的半成品分支。
     *
     * <p>JGit 的 {@code CheckoutCommand} 在 {@code createBranch=true} 时先建分支、再切工作区，
     * 切换失败（{@code CheckoutConflictException}）时分支引用与 {@code branch.<name>} 的
     * upstream 配置已经写下去了，所以必须由调用方清理。</p>
     *
     * <p>传<b>完整引用名</b>（{@code refs/heads/<name>}）：短名会走 JGit 的 SEARCH_PATH，
     * 形如 {@code origin/dev} 的分支名可能解析到远程跟踪引用上。{@code DeleteBranchCommand}
     * 会一并清掉 upstream 配置，也不会删当前分支。</p>
     */
    private static void deleteHalfCreatedBranch(Git git, String fullRef) {
        try {
            git.branchDelete().setBranchNames(fullRef).setForce(true).call();
        } catch (GitAPIException e) {
            // 尽力而为：清理失败不能掩盖真正的切换错误
            log.warn("回滚半成品分支失败 ref={}", fullRef, e);
        }
    }

    /**
     * 拦截 git 的目录/文件冲突：不能同时存在 `feature` 与 `feature/x`。
     *
     * <p>JGit 在这种情况下对 ref 更新返回 {@code LOCK_FAILURE}，并被包装成
     * {@code JGitInternalException}（500）。提前校验即可给出可读的 409。</p>
     */
    private static void ensureNoBranchNameConflict(Git git, String branch) throws GitAPIException {
        String target = Constants.R_HEADS + branch;
        // 不设 ListMode 时只列本地分支
        List<Ref> locals = git.branchList().call();
        for (Ref ref : locals) {
            String name = ref.getName();
            if (target.equals(name)) {
                // 同名由 JGit 抛 RefAlreadyExistsException，交给既有映射
                continue;
            }
            if (name.startsWith(target + "/") || target.startsWith(name + "/")) {
                throw new GitOperationException(ErrorCode.BRANCH_EXISTS,
                        "分支名与已有分支冲突：" + Repository.shortenRefName(name)
                                + "（不能同时存在分支与其子分支）");
            }
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
