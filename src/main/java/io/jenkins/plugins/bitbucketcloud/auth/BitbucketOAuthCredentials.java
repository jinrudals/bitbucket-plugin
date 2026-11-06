package io.jenkins.plugins.bitbucketcloud.auth;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.util.Secret;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

public class BitbucketOAuthCredentials extends BaseStandardCredentials {
    private static final long serialVersionUID = 1L;

    private final String clientId;
    private final Secret clientSecret;

    @DataBoundConstructor
    public BitbucketOAuthCredentials(
            CredentialsScope scope, String id, String description, String clientId, Secret clientSecret) {
        super(scope, id, description);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public String getClientId() {
        return clientId;
    }

    public Secret getClientSecret() {
        return clientSecret;
    }

    @Extension
    @Symbol("bitbucketOAuth")
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Bitbucket OAuth consumer";
        }
    }
}
