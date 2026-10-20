package com.sonsure.synclavis4j.terminal;

/**
 * 创建终端会话请求（见《详细设计文档》§6.6.2）。
 *
 * <p>客户端不能指定 shell、命令或 cwd；cwd 只能由已打开仓库的 repoId 解析。</p>
 */
public class CreateTerminalRequest {

    private String repoId;
    private Integer cols;
    private Integer rows;

    public String getRepoId() {
        return repoId;
    }

    public void setRepoId(String repoId) {
        this.repoId = repoId;
    }

    public Integer getCols() {
        return cols;
    }

    public void setCols(Integer cols) {
        this.cols = cols;
    }

    public Integer getRows() {
        return rows;
    }

    public void setRows(Integer rows) {
        this.rows = rows;
    }
}
