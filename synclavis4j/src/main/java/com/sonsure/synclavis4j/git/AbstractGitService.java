package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.repo.RepoRepositoryManager;
import com.sonsure.synclavis4j.repo.RepoService;
import org.eclipse.jgit.lib.Repository;

/**
 * Git 服务基类：统一从缓存的 Repository 实例获取仓库。
 */
public abstract class AbstractGitService {

    protected final RepoRepositoryManager repositories;
    protected final RepoService repoService;

    protected AbstractGitService(RepoRepositoryManager repositories, RepoService repoService) {
        this.repositories = repositories;
        this.repoService = repoService;
    }

    /** 取（必要时构建）仓库实例；repoId 未打开时抛 404。 */
    protected Repository repo(String repoId) {
        return repositories.acquire(repoId, repoService.pathOf(repoId));
    }
}
