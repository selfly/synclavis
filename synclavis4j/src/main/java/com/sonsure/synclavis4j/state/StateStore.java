package com.sonsure.synclavis4j.state;

/**
 * 状态持久化接口。
 *
 * <p>首版实现为 {@link JsonStateStore}；后续可无缝替换为 H2/SQLite 等实现，
 * 上层 Controller/Service 只依赖本接口（见《详细设计文档》§5.9）。</p>
 */
public interface StateStore {

    /** 读取全量状态；文件缺失或损坏时返回默认值并落盘。 */
    AppState load();

    /** 全量原子写。 */
    void save(AppState state);

    /** 便捷方法：更新会话并落盘。 */
    void updateSession(SessionState session);

    /** 便捷方法：更新全局设置并落盘。 */
    void updateGlobalSettings(GlobalSettings settings);

    /** 便捷方法：更新某仓库设置并落盘。 */
    void updateRepoSettings(String repoId, RepoSettings settings);
}
