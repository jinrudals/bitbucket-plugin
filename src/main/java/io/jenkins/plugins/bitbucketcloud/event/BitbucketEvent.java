package io.jenkins.plugins.bitbucketcloud.event;

public sealed interface BitbucketEvent permits PushEvent, PullRequestEvent {
    String repoFullName();

    String actor();

    String commitHash();
}
