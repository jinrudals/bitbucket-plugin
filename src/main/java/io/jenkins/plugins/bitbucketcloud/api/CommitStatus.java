package io.jenkins.plugins.bitbucketcloud.api;

import java.util.Objects;

/** A build status as published to Bitbucket Cloud; {@code name}, {@code description} and {@code url} are optional. */
public record CommitStatus(State state, String key, String name, String description, String url) {
    public CommitStatus {
        Objects.requireNonNull(state);
        Objects.requireNonNull(key);
    }

    /** The build states accepted by the Bitbucket Cloud commit status API. */
    public enum State {
        INPROGRESS,
        SUCCESSFUL,
        FAILED,
        STOPPED
    }
}
