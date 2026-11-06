package io.jenkins.plugins.bitbucketcloud.event;

public record PushEvent(String repoFullName, String actor, String branch, String commitHash)
        implements BitbucketEvent {}
