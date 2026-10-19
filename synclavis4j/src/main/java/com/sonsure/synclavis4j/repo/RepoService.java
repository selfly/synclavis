package com.sonsure.synclavis4j.repo;

import com.sonsure.synclavis4j.repo.dto.RepoSettingsDto;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import com.sonsure.synclavis4j.shared.util.RepoId;
import com.sonsure.synclavis4j.state.AppState;
import com.sonsure.synclavis4j.state.GlobalSettings;
import com.sonsure.synclavis4j.state.RecentRepo;
import com.sonsure.synclavis4j.state.RepoSettings;
import com.sonsure.synclavis4j.state.SessionState;
import com.sonsure.synclavis4j.state.SessionTab;
import com.sonsure.synclavis4j.state.StateStore;
import java.io.File;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 仓库会话业务逻辑：打开 / 关闭 / 列表 / 最近 / 设置。
 *
 * <p>持久化以后端为唯一真相（见《详细设计文档》§5.9）。</p>
 */
@Service
public class RepoService {

    private static final Logger log = LoggerFactory.getLogger(RepoService.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final int MAX_RECENT = 20;

    private final StateStore stateStore;
    private final RepoRepositoryManager repositories;

    public RepoService(StateStore stateStore, RepoRepositoryManager repositories) {
        this.stateStore = stateStore;
        this.repositories = repositories;
    }

    public SessionState session() {
        return stateStore.load().getSession();
    }

    public GlobalSettings globalSettings() {
        return stateStore.load().getGlobalSettings();
    }

    /** 打开（或聚焦）一个仓库。 */
    public SessionTab open(String rawPath, String sshKeyPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "仓库路径不能为空");
        }
        String path = RepoId.canonicalize(rawPath.trim());
        String repoId = RepoId.of(path);

        // 打开即校验是否为合法 Git 仓库（失败会抛 NOT_A_REPO / INVALID_PATH）
        repositories.acquire(repoId, path);

        AppState state = stateStore.load();
        SessionState session = state.getSession();

        SessionTab tab = session.find(repoId);
        if (tab == null) {
            tab = new SessionTab();
            tab.setId(repoId);
            tab.setPath(path);
            tab.setName(new File(path).getName());
            tab.setPullStrategy(RepoSettings.DEFAULT_PULL_STRATEGY);
            tab.setOrder(session.getTabs().size());
            session.getTabs().add(tab);
        }
        if (sshKeyPath != null && !sshKeyPath.trim().isEmpty()) {
            tab.setSshKeyPath(sshKeyPath.trim());
        }

        // 仓库设置以 repos map 为准，会话 Tab 同步镜像
        RepoSettings settings = state.settingsOf(repoId);
        settings.setSshKeyPath(tab.getSshKeyPath());
        settings.setPullStrategy(tab.getPullStrategy());

        session.setActiveId(repoId);
        touchRecent(state.getGlobalSettings(), path, tab.getName());
        stateStore.save(state);

        log.info("已打开仓库 repoId={} path={}", repoId, path);
        return tab;
    }

    public void close(String repoId) {
        AppState state = stateStore.load();
        SessionState session = state.getSession();

        List<SessionTab> remaining = new ArrayList<SessionTab>();
        for (SessionTab tab : session.getTabs()) {
            if (!tab.getId().equals(repoId)) {
                remaining.add(tab);
            }
        }
        if (remaining.size() == session.getTabs().size()) {
            throw new GitOperationException(ErrorCode.NOT_FOUND, "仓库未打开：" + repoId);
        }
        // 重排 order，保持连续
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setOrder(i);
        }
        session.setTabs(remaining);

        if (repoId.equals(session.getActiveId())) {
            session.setActiveId(remaining.isEmpty() ? null : remaining.get(0).getId());
        }

        stateStore.save(state);
        repositories.release(repoId);
        log.info("已关闭仓库 repoId={}（未删除磁盘文件）", repoId);
    }

    public SessionState setActive(String repoId) {
        AppState state = stateStore.load();
        SessionState session = state.getSession();
        if (repoId != null && session.find(repoId) == null) {
            throw new GitOperationException(ErrorCode.NOT_FOUND, "仓库未打开：" + repoId);
        }
        session.setActiveId(repoId);
        stateStore.save(state);
        return session;
    }

    /**
     * 重排已打开 Tab：{@code ids} 必须是当前全部 Tab id 的一个排列。
     *
     * <p>整体校验后再落盘，避免半途失败留下不一致的 order（见《详细设计文档》§6.2）。</p>
     */
    public SessionState reorder(List<String> ids) {
        AppState state = stateStore.load();
        SessionState session = state.getSession();
        List<SessionTab> current = session.getTabs();
        if (ids == null || ids.size() != current.size()) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "Tab 顺序必须包含全部已打开仓库");
        }

        Map<String, SessionTab> remaining = new LinkedHashMap<String, SessionTab>();
        for (SessionTab tab : current) {
            remaining.put(tab.getId(), tab);
        }

        List<SessionTab> ordered = new ArrayList<SessionTab>();
        for (String id : ids) {
            SessionTab tab = remaining.remove(id);
            if (tab == null) {
                throw new GitOperationException(ErrorCode.INVALID_PARAM, "Tab 顺序包含未知或重复的仓库：" + id);
            }
            ordered.add(tab);
        }
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setOrder(i);
        }

        session.setTabs(ordered);
        stateStore.save(state);
        log.info("已重排会话 Tab：{}", ids);
        return session;
    }

    public List<RecentRepo> recent(int limit) {
        List<RecentRepo> all = stateStore.load().getGlobalSettings().getRecentRepos();
        int size = limit <= 0 ? all.size() : Math.min(limit, all.size());
        return new ArrayList<RecentRepo>(all.subList(0, size));
    }

    public RepoSettings repoSettingsEntity(String repoId) {
        requireOpen(repoId);
        return stateStore.load().settingsOf(repoId);
    }

    /** API 视图：不含密码原文，仅返回 hasHttpsPassword。 */
    public RepoSettingsDto repoSettings(String repoId) {
        return RepoSettingsDto.from(repoSettingsEntity(repoId));
    }

    public RepoSettingsDto updateRepoSettings(String repoId, String sshKeyPath, String pullStrategy,
                                              String httpsUsername, String httpsPassword) {
        requireOpen(repoId);
        AppState state = stateStore.load();
        RepoSettings settings = state.settingsOf(repoId);
        if (sshKeyPath != null) {
            settings.setSshKeyPath(sshKeyPath.trim().isEmpty() ? null : sshKeyPath.trim());
        }
        if (pullStrategy != null && !pullStrategy.trim().isEmpty()) {
            settings.setPullStrategy(pullStrategy.trim());
        }
        if (httpsUsername != null) {
            settings.setHttpsUsername(httpsUsername.trim().isEmpty() ? null : httpsUsername.trim());
        }
        if (httpsPassword != null) {
            // null=不改动；空串=清除；非空=设置
            settings.setHttpsPassword(httpsPassword.isEmpty() ? null : httpsPassword);
        }
        // 会话 Tab 同步镜像（不含敏感字段）
        SessionTab tab = state.getSession().find(repoId);
        if (tab != null) {
            tab.setSshKeyPath(settings.getSshKeyPath());
            tab.setPullStrategy(settings.getPullStrategy());
        }
        stateStore.save(state);
        return RepoSettingsDto.from(settings);
    }

    public String pathOf(String repoId) {
        SessionTab tab = stateStore.load().getSession().find(repoId);
        if (tab == null) {
            throw new GitOperationException(ErrorCode.NOT_FOUND, "仓库未打开：" + repoId);
        }
        return tab.getPath();
    }

    private void requireOpen(String repoId) {
        if (stateStore.load().getSession().find(repoId) == null) {
            throw new GitOperationException(ErrorCode.NOT_FOUND, "仓库未打开：" + repoId);
        }
    }

    private void touchRecent(GlobalSettings settings, String path, String name) {
        List<RecentRepo> recents = settings.getRecentRepos();
        Iterator<RecentRepo> iterator = recents.iterator();
        while (iterator.hasNext()) {
            if (path.equals(iterator.next().getPath())) {
                iterator.remove();
            }
        }
        recents.add(0, new RecentRepo(path, name, OffsetDateTime.now().format(TIME_FORMAT)));
        while (recents.size() > MAX_RECENT) {
            recents.remove(recents.size() - 1);
        }
    }
}
