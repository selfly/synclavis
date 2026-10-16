package com.sonsure.synclavis4j.git.dto;

/**
 * 分支信息。
 */
public class BranchInfoDto {

    private String name;
    private String fullName;
    private boolean local;
    private boolean current;
    private int ahead;
    private int behind;

    public BranchInfoDto() {
    }

    public BranchInfoDto(String name, String fullName, boolean local, boolean current) {
        this.name = name;
        this.fullName = fullName;
        this.local = local;
        this.current = current;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public boolean isLocal() {
        return local;
    }

    public void setLocal(boolean local) {
        this.local = local;
    }

    public boolean isCurrent() {
        return current;
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }

    public int getAhead() {
        return ahead;
    }

    public void setAhead(int ahead) {
        this.ahead = ahead;
    }

    public int getBehind() {
        return behind;
    }

    public void setBehind(int behind) {
        this.behind = behind;
    }
}
