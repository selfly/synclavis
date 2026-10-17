package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.git.dto.RemoteInfoDto;
import com.sonsure.synclavis4j.repo.RepoRepositoryManager;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.URIish;
import org.springframework.stereotype.Service;

/**
 * 远程仓库配置管理（见《详细设计文档》§6.2「远程仓库」）。
 *
 * <p>本地 {@code git init} 出来的仓库没有 remote，必须能就地添加后才能推送。
 * 增 / 改 / 删都是本地配置写操作，走 {@link GitWriteExecutor#runSync} 拿每仓库写锁，
 * 成功后自动推 status 事件，前端 {@code status.remotes} 与 Push 按钮随之更新。</p>
 */
@Service
public class GitRemoteService extends AbstractGitService {

    /** 远程名禁止出现的字符：空白与 ref 规则里的特殊字符（`/` 也禁用，避免 REST 路径变量歧义）。 */
    private static final Pattern INVALID_NAME_CHARS = Pattern.compile("[\\s/~^:?*\\[\\\\]");

    /** scp 形式（user@host:path）或带 scheme 的地址。 */
    private static final Pattern REMOTE_URI =
            Pattern.compile("^(?:[a-zA-Z][a-zA-Z0-9+.\\-]*://.+|[^/@\\s]+@[^/\\s:]+:.+)$");

    private final GitWriteExecutor writeExecutor;

    public GitRemoteService(RepoRepositoryManager repositories, RepoService repoService,
                            GitWriteExecutor writeExecutor) {
        super(repositories, repoService);
        this.writeExecutor = writeExecutor;
    }

    /** 远程仓库列表（只读，不加写锁）。 */
    public List<RemoteInfoDto> list(String repoId) {
        Repository repository = repo(repoId);
        StoredConfig config = repository.getConfig();
        Set<String> names = repository.getRemoteNames();
        List<RemoteInfoDto> result = new ArrayList<RemoteInfoDto>();
        for (String name : names) {
            result.add(new RemoteInfoDto(name,
                    config.getString("remote", name, "url"),
                    config.getString("remote", name, "pushurl")));
        }
        Collections.sort(result, new Comparator<RemoteInfoDto>() {
            @Override
            public int compare(RemoteInfoDto left, RemoteInfoDto right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        return result;
    }

    /** 新增远程；重名报 {@code REMOTE_EXISTS}。 */
    public List<RemoteInfoDto> add(String repoId, String rawName, String rawUrl) {
        final String name = validateName(rawName);
        final String url = validateUrl(rawUrl);
        writeExecutor.runSync(repoId, "remotes", new GitWriteExecutor.SyncTask<Void>() {
            @Override
            public Void run(String operationId) throws Exception {
                Repository repository = repo(repoId);
                if (repository.getRemoteNames().contains(name)) {
                    throw new GitOperationException(ErrorCode.REMOTE_EXISTS, "远程已存在：" + name);
                }
                try (Git git = Git.wrap(repository)) {
                    git.remoteAdd().setName(name).setUri(new URIish(url)).call();
                } catch (GitAPIException e) {
                    throw new GitOperationException(ErrorCode.INTERNAL,
                            "添加远程失败：" + e.getMessage(), null, e);
                }
                return null;
            }
        });
        return list(repoId);
    }

    /** 修改远程 URL（不做重命名：删除 + 新增即可）。 */
    public List<RemoteInfoDto> updateUrl(String repoId, String rawName, String rawUrl) {
        final String name = validateName(rawName);
        final String url = validateUrl(rawUrl);
        writeExecutor.runSync(repoId, "remotes", new GitWriteExecutor.SyncTask<Void>() {
            @Override
            public Void run(String operationId) throws Exception {
                Repository repository = repo(repoId);
                requireExisting(repository, name);
                StoredConfig config = repository.getConfig();
                config.setString("remote", name, "url", url);
                config.save();
                return null;
            }
        });
        return list(repoId);
    }

    /** 删除远程。不清理 {@code branch.<b>.remote}，与 {@code git remote remove} 行为一致。 */
    public List<RemoteInfoDto> remove(String repoId, String rawName) {
        final String name = validateName(rawName);
        writeExecutor.runSync(repoId, "remotes", new GitWriteExecutor.SyncTask<Void>() {
            @Override
            public Void run(String operationId) throws Exception {
                Repository repository = repo(repoId);
                requireExisting(repository, name);
                try (Git git = Git.wrap(repository)) {
                    git.remoteRemove().setRemoteName(name).call();
                } catch (GitAPIException e) {
                    throw new GitOperationException(ErrorCode.INTERNAL,
                            "删除远程失败：" + e.getMessage(), null, e);
                }
                return null;
            }
        });
        return list(repoId);
    }

    private void requireExisting(Repository repository, String name) {
        if (!repository.getRemoteNames().contains(name)) {
            throw new GitOperationException(ErrorCode.NOT_FOUND, "远程不存在：" + name);
        }
    }

    /** 远程名校验：对齐 git 的 remote 名规则，并额外禁用 `/`。 */
    private String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "远程名不能为空");
        }
        if (name.startsWith("-")) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "远程名不能以 - 开头：" + name);
        }
        if (name.contains("..") || name.contains("@{")) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "远程名不能包含 .. 或 @{：" + name);
        }
        if (INVALID_NAME_CHARS.matcher(name).find()) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM,
                    "远程名不能包含空白或 / ~ ^ : ? * [ \\ 等字符：" + name);
        }
        return name;
    }

    /** URL 校验：scp 形式 / 带 scheme 的地址 / 本地绝对路径，一律不允许空白。 */
    private String validateUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.isEmpty()) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "url 不能为空");
        }
        if (containsWhitespace(url)) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "url 不能包含空白字符");
        }
        if (isLocalPath(url) || REMOTE_URI.matcher(url).matches()) {
            return url;
        }
        throw new GitOperationException(ErrorCode.INVALID_PARAM,
                "url 形如 https://host/repo.git、git@host:path/repo.git 或本地绝对路径：" + url);
    }

    private boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** POSIX 绝对路径 / `~` 开头 / Windows 盘符（`C:\`、`C:/`）。 */
    private boolean isLocalPath(String url) {
        if (url.startsWith("/") || url.startsWith("~")) {
            return true;
        }
        return url.length() > 2
                && Character.isLetter(url.charAt(0))
                && url.charAt(1) == ':'
                && (url.charAt(2) == '/' || url.charAt(2) == '\\');
    }
}
