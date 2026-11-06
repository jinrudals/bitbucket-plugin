package io.jenkins.plugins.bitbucketcloud.event;

public record PullRequestEvent(
        String repoFullName,
        String actor,
        String type,
        String prId,
        String sourceBranch,
        String targetBranch,
        String commitHash,
        boolean draft,
        boolean fromFork)
        implements BitbucketEvent {}
