package io.jenkins.plugins.bitbucketcloud.auth;

import hudson.util.Secret;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class BasicAuth implements BitbucketAuth {
    private final String user;
    private final Secret password;

    public BasicAuth(String user, Secret password) {
        this.user = user;
        this.password = password;
    }

    @Override
    public void applyTo(HttpRequest.Builder b) {
        String raw = user + ":" + password.getPlainText();
        b.setHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
