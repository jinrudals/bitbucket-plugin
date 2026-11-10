package io.jenkins.plugins.bitbucketcloud.pipeline;

import java.io.Serializable;

/** Step arguments; everything except the content is optional and resolved when left empty. */
public record PullRequestCommentOptions(
        String content, String parentId, String pullRequestId, String workspace, String repo)
        implements Serializable {}
