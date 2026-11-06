package io.jenkins.plugins.bitbucketcloud.config;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketOAuthCredentials;
import java.util.Optional;
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

    private String webhookSecretId;
    private String defaultCredentialsId;
    private boolean allowUnsignedWebhooks;
    private String apiBaseUrl = DEFAULT_API_BASE_URL;

    public BitbucketGlobalConfig() {
        load();
    }

    public static BitbucketGlobalConfig get() {
        return GlobalConfiguration.all().getInstance(BitbucketGlobalConfig.class);
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
        this.apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank() ? DEFAULT_API_BASE_URL : apiBaseUrl.trim();
        save();
    }

    public Optional<String> webhookSecret() {
        if (webhookSecretId == null) {
            return Optional.empty();
        }
        StringCredentials c = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM2,
                        URIRequirementBuilder.fromUri(getApiBaseUrl()).build()),
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
        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2,
                        Jenkins.get(),
                        StringCredentials.class,
                        URIRequirementBuilder.fromUri(getApiBaseUrl()).build(),
                        CredentialsMatchers.always())
                .includeCurrentValue(webhookSecretId);
    }

    @POST
    public ListBoxModel doFillDefaultCredentialsIdItems(@QueryParameter String defaultCredentialsId) {
        StandardListBoxModel m = new StandardListBoxModel();
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return m.includeCurrentValue(defaultCredentialsId);
        }
        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2,
                        Jenkins.get(),
                        StandardCredentials.class,
                        URIRequirementBuilder.fromUri(getApiBaseUrl()).build(),
                        CredentialsMatchers.anyOf(
                                CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                                CredentialsMatchers.instanceOf(StringCredentials.class),
                                CredentialsMatchers.instanceOf(BitbucketOAuthCredentials.class)))
                .includeCurrentValue(defaultCredentialsId);
    }
}
