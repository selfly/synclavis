package com.sonsure.synclavis4j.files.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 仓库内文件搜索（见《详细设计文档》§6.2「仓库文件浏览（只读）」）。
 *
 * <p>与 {@link RepoFileListDto} 的懒加载互补：树是逐目录加载的，搜索必须能命中
 * <b>尚未展开</b>的目录，因此这里返回扁平的文件列表。</p>
 */
public class RepoFileSearchDto {

    /** 已规范化的关键字（去空白 + 小写），原样回传便于前端对账 */
    private String query;
    private List<RepoFileEntryDto> matches = new ArrayList<RepoFileEntryDto>();
    /** 命中数或遍历量触顶时为 true */
    private boolean truncated;

    public RepoFileSearchDto() {
    }

    public RepoFileSearchDto(String query, List<RepoFileEntryDto> matches, boolean truncated) {
        this.query = query;
        this.matches = matches;
        this.truncated = truncated;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public List<RepoFileEntryDto> getMatches() {
        return matches;
    }

    public void setMatches(List<RepoFileEntryDto> matches) {
        this.matches = matches;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }
}
