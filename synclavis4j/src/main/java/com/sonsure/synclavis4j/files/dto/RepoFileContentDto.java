package com.sonsure.synclavis4j.files.dto;

/**
 * 仓库内单个文件的内容（见《详细设计文档》§6.2「文件内容语义」）。
 *
 * <p>只读。二进制或超大文件不返回内容，由 {@code binary} / {@code tooLarge} 标记。</p>
 */
public class RepoFileContentDto {

    private String path;
    private long size;
    private boolean binary;
    private boolean tooLarge;
    /** 实际使用的字符集：UTF-8 / GBK / ISO-8859-1 */
    private String encoding;
    private String content;

    public RepoFileContentDto() {
    }

    public RepoFileContentDto(String path, long size, boolean binary, boolean tooLarge,
                              String encoding, String content) {
        this.path = path;
        this.size = size;
        this.binary = binary;
        this.tooLarge = tooLarge;
        this.encoding = encoding;
        this.content = content;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public boolean isBinary() {
        return binary;
    }

    public void setBinary(boolean binary) {
        this.binary = binary;
    }

    public boolean isTooLarge() {
        return tooLarge;
    }

    public void setTooLarge(boolean tooLarge) {
        this.tooLarge = tooLarge;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
