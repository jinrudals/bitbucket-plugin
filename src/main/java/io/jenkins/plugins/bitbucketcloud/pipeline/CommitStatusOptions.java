package io.jenkins.plugins.bitbucketcloud.pipeline;

import java.io.Serializable;

/**
 * Step arguments; every field is optional and falls back to a default or a resolved value. Public
 * because pipeline serialization cannot access a non-public record.
 */
public record CommitStatusOptions(
        String name, String key, String description, String url, String workspace, String repo, String commit)
        implements Serializable {}
