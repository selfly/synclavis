package com.sonsure.synclavis4j.ai;

/**
 * 把已打开仓库的 repoId 解析为工作目录。客户端不能绕过该接口直接指定 cwd。
 */
public interface AiRepoPathResolver {

    String pathOf(String repoId);
}
