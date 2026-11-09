package io.jenkins.plugins.bitbucketcloud.config;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketOAuthCredentials;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

@Extension
@Symbol("bitbucketCloud")
public class BitbucketGlobalConfig extends GlobalConfiguration {
    public static final String DEFAULT_API_BASE_URL = "https://api.bitbucket.org";

    private static final Logger LOGGER = Logger.getLogger(BitbucketGlobalConfig.class.getName());
    private static final String AUTH_HOST_URI = "https://bitbucket.org";

    private String webhookSecretId;
    private String defaultCredentialsId;
    private boolean allowUnsignedWebhooks;
    private String apiBaseUrl = DEFAULT_API_BASE_URL;
    private List<BitbucketWorkspaceConnection> workspaceConnections = new ArrayList<>();

    public BitbucketGlobalConfig() {
        load();
    }

    public static BitbucketGlobalConfig get() {
        return GlobalConfiguration.all().getInstance(BitbucketGlobalConfig.class);
    }

    public List<BitbucketWorkspaceConnection> getWorkspaceConnections() {
        return workspaceConnections == null ? List.of() : List.copyOf(workspaceConnections);
    }

    @DataBoundSetter
    public void setWorkspaceConnections(List<BitbucketWorkspaceConnection> workspaceConnections)
            throws Descriptor.FormException {
        List<BitbucketWorkspaceConnection> candidate =
                workspaceConnections == null ? List.of() : new ArrayList<>(workspaceConnections);
        FormValidation validation = validateWorkspaceConnections(candidate);
        if (validation.kind == FormValidation.Kind.ERROR) {
            throw new Descriptor.FormException(validation.getMessage(), "workspaceConnections");
        }
        this.workspaceConnections = List.copyOf(candidate);
        save();
    }

    public static FormValidation validateWorkspaceConnections(List<BitbucketWorkspaceConnection> connections) {
        if (connections == null) {
            return FormValidation.ok();
        }
        Set<String> seen = new HashSet<>();
        for (BitbucketWorkspaceConnection connection : connections) {
            if (connection == null || !BitbucketWorkspaceConnection.isValidWorkspaceSlug(connection.getWorkspace())) {
                return FormValidation.error("Enter a valid Bitbucket Cloud workspace slug.");
            }
            if (connection.getCredentialsId() == null || connection.getCredentialsId().isBlank()) {
                return FormValidation.error("Select a credential for workspace " + connection.getWorkspace() + ".");
            }
            String normalizedWorkspace = connection.getWorkspace().toLowerCase(Locale.ROOT);
            if (!seen.add(normalizedWorkspace)) {
                return FormValidation.error(
                        "Workspace " + connection.getWorkspace() + " is listed more than once.");
            }
        }
        return FormValidation.ok();
    }

    public String getWebhookSecretId() {
        return webhookSecretId;
    }

    @DataBoundSetter
    public void setWebhookSecretId(String webhookSecretId) {
        this.webhookSecretId = blankToNull(webhookSecretId);
        save();
    }

    public String getDefaultCredentialsId() {
        return defaultCredentialsId;
    }

    @DataBoundSetter
    public void setDefaultCredentialsId(String defaultCredentialsId) {
        this.defaultCredentialsId = blankToNull(defaultCredentialsId);
        save();
    }

    public boolean isAllowUnsignedWebhooks() {
        return allowUnsignedWebhooks;
    }

    @DataBoundSetter
    public void setAllowUnsignedWebhooks(boolean allowUnsignedWebhooks) {
        this.allowUnsignedWebhooks = allowUnsignedWebhooks;
        save();
    }

    public String getApiBaseUrl() {
        return apiBaseUrl == null || apiBaseUrl.isBlank() ? DEFAULT_API_BASE_URL : apiBaseUrl;
    }

    @DataBoundSetter
    public void setApiBaseUrl(String apiBaseUrl) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            this.apiBaseUrl = DEFAULT_API_BASE_URL;
        } else {
            String candidate = apiBaseUrl.trim();
            URI uri = parse(candidate);
            if (uri != null && (isHttps(uri) || isLocal(uri))) {
                this.apiBaseUrl = candidate;
            } else {
                LOGGER.log(
                        Level.WARNING,
                        "Rejecting invalid Bitbucket API base URL {0}; using default",
                        describe(candidate, uri));
                this.apiBaseUrl = DEFAULT_API_BASE_URL;
            }
        }
        save();
    }

    private static URI parse(String s) {
        try {
            URI u = new URI(s);
            return u.getHost() == null || u.getScheme() == null ? null : u;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    private static boolean isHttps(URI u) {
        return "https".equalsIgnoreCase(u.getScheme());
    }

    private static boolean isLocal(URI u) {
        String h = u.getHost();
        return "http".equalsIgnoreCase(u.getScheme())
                && ("localhost".equalsIgnoreCase(h) || "127.0.0.1".equals(h) || "[::1]".equals(h));
    }

    private static String describe(String raw, URI uri) {
        if (uri == null) {
            return "(unparseable or host-less)";
        }
        return uri.getScheme() + "://" + uri.getHost();
    }

    @POST
    public FormValidation doCheckApiBaseUrl(@QueryParameter String value) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return FormValidation.ok();
        }
        if (value == null || value.isBlank()) {
            return FormValidation.ok();
        }
        URI uri = parse(value.trim());
        if (uri == null) {
            return FormValidation.error("Not a valid URL with a host");
        }
        if (!isHttps(uri) && !isLocal(uri)) {
            return FormValidation.warning("Credentials would be sent over a non-https URL; it will be ignored");
        }
        return FormValidation.ok();
    }

    public Optional<String> webhookSecret() {
        if (webhookSecretId == null) {
            return Optional.empty();
        }
        StringCredentials c = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of()),
                CredentialsMatchers.withId(webhookSecretId));
        return c == null ? Optional.empty() : Optional.of(c.getSecret().getPlainText());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @POST
    public ListBoxModel doFillWebhookSecretIdItems(@QueryParameter String webhookSecretId) {
        StandardListBoxModel m = new StandardListBoxModel();
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return m.includeCurrentValue(webhookSecretId);
        }
        return m.includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2,
                        Jenkins.get(),
                        StringCredentials.class,
                        List.<com.cloudbees.plugins.credentials.domains.DomainRequirement>of(),
                        CredentialsMatchers.always())
                .includeCurrentValue(webhookSecretId);
    }

    @POST
    public ListBoxModel doFillDefaultCredentialsIdItems(@QueryParameter String defaultCredentialsId) {
        StandardListBoxModel m = new StandardListBoxModel();
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return m.includeCurrentValue(defaultCredentialsId);
        }
        return m.includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2,
                        Jenkins.get(),
                        StandardCredentials.class,
                        URIRequirementBuilder.fromUri(AUTH_HOST_URI).build(),
                        CredentialsMatchers.anyOf(
                                CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                                CredentialsMatchers.instanceOf(StringCredentials.class),
                                CredentialsMatchers.instanceOf(BitbucketOAuthCredentials.class)))
                .includeCurrentValue(defaultCredentialsId);
    }
}
