package com.sonsure.synclavis4j.git.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 仓库状态（契约见《详细设计文档》§4.1）。
 */
public class RepoStatusDto {

    private String branch;
    private boolean detached;
    private int ahead;
    private int behind;
    private List<FileChangeDto> staged = new ArrayList<FileChangeDto>();
    private List<FileChangeDto> unstaged = new ArrayList<FileChangeDto>();
    private List<String> untracked = new ArrayList<String>();
    private List<String> conflicts = new ArrayList<String>();
    private List<String> remotes = new ArrayList<String>();
    private boolean hasUpstream;
    private String upstream;
    private boolean rebaseInProgress;
    private String repositoryState;

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public boolean isDetached() {
        return detached;
    }

    public void setDetached(boolean detached) {
        this.detached = detached;
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

    public List<FileChangeDto> getStaged() {
        return staged;
    }

    public void setStaged(List<FileChangeDto> staged) {
        this.staged = staged;
    }

    public List<FileChangeDto> getUnstaged() {
        return unstaged;
    }

    public void setUnstaged(List<FileChangeDto> unstaged) {
        this.unstaged = unstaged;
    }

    public List<String> getUntracked() {
        return untracked;
    }

    public void setUntracked(List<String> untracked) {
        this.untracked = untracked;
    }

    public List<String> getConflicts() {
        return conflicts;
    }

    public void setConflicts(List<String> conflicts) {
        this.conflicts = conflicts;
    }

    public List<String> getRemotes() {
        return remotes;
    }

    public void setRemotes(List<String> remotes) {
        this.remotes = remotes;
    }

    public boolean isHasUpstream() {
        return hasUpstream;
    }

    public void setHasUpstream(boolean hasUpstream) {
        this.hasUpstream = hasUpstream;
    }

    public String getUpstream() {
        return upstream;
    }

    public void setUpstream(String upstream) {
        this.upstream = upstream;
    }

    public boolean isRebaseInProgress() {
        return rebaseInProgress;
    }

    public void setRebaseInProgress(boolean rebaseInProgress) {
        this.rebaseInProgress = rebaseInProgress;
    }

    public String getRepositoryState() {
        return repositoryState;
    }

    public void setRepositoryState(String repositoryState) {
        this.repositoryState = repositoryState;
    }
}
