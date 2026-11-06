package io.jenkins.plugins.bitbucketcloud.auth;

import hudson.util.Secret;
import java.net.http.HttpRequest;

public final class BearerAuth implements BitbucketAuth {
    private final Secret token;

    public BearerAuth(Secret token) {
        this.token = token;
    }

    @Override
    public void applyTo(HttpRequest.Builder b) {
        b.setHeader("Authorization", "Bearer " + token.getPlainText());
    }
}
