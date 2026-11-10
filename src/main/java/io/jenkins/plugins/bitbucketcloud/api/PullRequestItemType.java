package io.jenkins.plugins.bitbucketcloud.api;

/** Kinds of resolvable items that can be created on a pull request. */
public enum PullRequestItemType {
    /** A top-level comment, which starts a comment thread. */
    THREAD,
    /** A pull request task. */
    TASK
}
