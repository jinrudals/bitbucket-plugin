package io.jenkins.plugins.bitbucketcloud.config;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketOAuthCredentials;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class BitbucketWorkspaceConnection extends AbstractDescribableImpl<BitbucketWorkspaceConnection> {
    private static final Pattern SAFE_SLUG = Pattern.compile("^(?!\\.{1,2}$)[A-Za-z0-9._~-]+$");
    private static final String BITBUCKET_AUTH_URI = "https://bitbucket.org";

    private final String workspace;
    private final String credentialsId;

    @DataBoundConstructor
    public BitbucketWorkspaceConnection(String workspace, String credentialsId) {
        this.workspace = workspace == null ? null : workspace.trim();
        this.credentialsId = credentialsId == null || credentialsId.isBlank() ? null : credentialsId.trim();
    }

    public String getWorkspace() {
        return workspace;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public static boolean isValidWorkspaceSlug(String workspace) {
        return workspace != null && SAFE_SLUG.matcher(workspace.trim()).matches();
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<BitbucketWorkspaceConnection> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Bitbucket Cloud workspace";
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            StandardListBoxModel model = new StandardListBoxModel();
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return model.includeCurrentValue(credentialsId);
            }
            return model.includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            Jenkins.get(),
                            StandardCredentials.class,
                            URIRequirementBuilder.fromUri(BITBUCKET_AUTH_URI).build(),
                            CredentialsMatchers.anyOf(
                                    CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                                    CredentialsMatchers.instanceOf(StringCredentials.class),
                                    CredentialsMatchers.instanceOf(BitbucketOAuthCredentials.class)))
                    .includeCurrentValue(credentialsId);
        }
    }
}
