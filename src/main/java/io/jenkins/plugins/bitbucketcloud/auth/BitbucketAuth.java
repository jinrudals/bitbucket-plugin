package io.jenkins.plugins.bitbucketcloud.auth;

import java.net.http.HttpRequest;

public interface BitbucketAuth {
    void applyTo(HttpRequest.Builder b);

    /** Obtains fresh credentials; returns true if the auth changed and the request may be retried. */
    default boolean refresh() {
        return false;
    }
}
