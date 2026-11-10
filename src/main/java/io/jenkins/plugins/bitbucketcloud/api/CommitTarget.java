package io.jenkins.plugins.bitbucketcloud.api;

/** The commit a build status is published to. */
public record CommitTarget(String workspace, String repo, String commit) {}
