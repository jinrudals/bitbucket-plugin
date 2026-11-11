package io.jenkins.plugins.bitbucketcloud.auth;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.security.ACL;
import java.util.Optional;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

public final class AuthResolver {
    private AuthResolver() {}

    public static Optional<BitbucketAuth> resolveWorkspaceCredential(String credentialsId) {
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
        if (c instanceof BitbucketAuthenticationCredentials a) {
            return Optional.of(new BasicAuth(a.getUsername(), a.getApiToken()));
        }
        if (c instanceof StringCredentials t) {
            return Optional.of(new BearerAuth(t.getSecret()));
        }
        return Optional.empty();
    }
}
