package io.jenkins.plugins.bitbucketcloud.auth;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.util.Secret;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

public class BitbucketAuthenticationCredentials extends BaseStandardCredentials {
    private static final long serialVersionUID = 1L;

    private final String username;
    private final Secret apiToken;

    @DataBoundConstructor
    public BitbucketAuthenticationCredentials(
            CredentialsScope scope, String id, String description, String username, Secret apiToken) {
        super(scope, id, description);
        this.username = username;
        this.apiToken = apiToken;
    }

    public String getUsername() {
        return username;
    }

    public Secret getApiToken() {
        return apiToken;
    }

    @Extension
    @Symbol("bitbucketAuthentication")
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Bitbucket Authentication";
        }
    }
}
