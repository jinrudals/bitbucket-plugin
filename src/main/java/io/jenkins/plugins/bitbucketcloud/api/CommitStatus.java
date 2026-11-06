package io.jenkins.plugins.bitbucketcloud.api;

public record CommitStatus(String key, String name, String state, String url, String description) {}
