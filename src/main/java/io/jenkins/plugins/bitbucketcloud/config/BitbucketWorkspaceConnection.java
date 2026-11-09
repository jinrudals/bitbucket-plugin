package io.jenkins.plugins.bitbucketcloud.config;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.bitbucketcloud.api.BitbucketWorkspaceClient;
import io.jenkins.plugins.bitbucketcloud.auth.AuthResolver;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketAuth;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketAuthenticationCredentials;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
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
        private static final BitbucketWorkspaceClient API = new BitbucketWorkspaceClient();

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
                            CredentialsMatchers.instanceOf(BitbucketAuthenticationCredentials.class))
                    .includeCurrentValue(credentialsId);
        }

        @POST
        public FormValidation doTestConnection(
                @QueryParameter String workspace, @QueryParameter String credentialsId) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.error("Administrator permission is required.");
            }
            BitbucketWorkspaceConnection connection = new BitbucketWorkspaceConnection(workspace, credentialsId);
            FormValidation validation = BitbucketGlobalConfig.validateWorkspaceConnections(List.of(connection));
            if (validation.kind == FormValidation.Kind.ERROR) {
                return validation;
            }
            Optional<BitbucketAuth> auth = AuthResolver.resolveWorkspaceCredential(connection.getCredentialsId());
            if (auth.isEmpty()) {
                return FormValidation.error("The selected Bitbucket credential is unavailable or unsupported.");
            }
            return toFormValidation(API.testWorkspace(connection.getWorkspace(), auth.orElseThrow()));
        }

        static FormValidation toFormValidation(BitbucketWorkspaceClient.WorkspaceTestResult result) {
            return switch (result.outcome()) {
                case SUCCESS -> FormValidation.ok("Bitbucket Cloud connection succeeded.");
                case UNAUTHORIZED -> FormValidation.error("Bitbucket Cloud rejected the credential (HTTP 401).");
                case FORBIDDEN -> FormValidation.error("The credential cannot read this workspace (HTTP 403).");
                case NOT_FOUND -> FormValidation.error("The workspace was not found or is not accessible (HTTP 404).");
                case HTTP_ERROR -> result.httpStatus().isPresent()
                        ? FormValidation.error("Bitbucket Cloud returned HTTP " + result.httpStatus().getAsInt() + ".")
                        : FormValidation.error("Bitbucket Cloud returned an unexpected response.");
                case AUTHENTICATION_ERROR -> FormValidation.error("Bitbucket authentication could not be established.");
                case TIMEOUT -> FormValidation.error("The Bitbucket Cloud connection test timed out.");
                case NETWORK_ERROR -> FormValidation.error("Could not connect to Bitbucket Cloud.");
                case INTERRUPTED -> FormValidation.error("The Bitbucket Cloud connection test was interrupted.");
            };
        }
    }
}
