package io.jenkins.plugins.bitbucketcloud.auth;

import java.net.http.HttpRequest;

public interface BitbucketAuth {
    void applyTo(HttpRequest.Builder b);
}
