package io.jenkins.plugins.bitbucketcloud.auth;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.security.ACL;
import java.util.Optional;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

public final class AuthResolver {
    private AuthResolver() {}

    public static Optional<BitbucketAuth> resolve(String credentialsId) {
        if (credentialsId == null || credentialsId.isBlank()) {
            return Optional.empty();
        }
        StandardCredentials c = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM2,
                        URIRequirementBuilder.fromUri("https://bitbucket.org").build()),
                CredentialsMatchers.withId(credentialsId));
        if (c instanceof StandardUsernamePasswordCredentials up) {
            return Optional.of(new BasicAuth(up.getUsername(), up.getPassword()));
        }
        if (c instanceof StringCredentials s) {
            return Optional.of(new BearerAuth(s.getSecret()));
        }
        if (c instanceof BitbucketOAuthCredentials o) {
            return Optional.of(new OAuthClientCredentialsAuth(o.getClientId(), o.getClientSecret()));
        }
        return Optional.empty();
    }
}
