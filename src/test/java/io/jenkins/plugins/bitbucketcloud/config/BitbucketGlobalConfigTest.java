package io.jenkins.plugins.bitbucketcloud.config;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.HostnameSpecification;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketOAuthCredentials;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BitbucketGlobalConfigTest {
    private static class UnsupportedCredentials extends BaseStandardCredentials {
        private static final long serialVersionUID = 1L;

        UnsupportedCredentials() {
            super(CredentialsScope.GLOBAL, "unsupported", "d");
        }
    }

    @Test
    void workspaceConnectionsDefaultToEmpty(JenkinsRule j) {
        assertTrue(BitbucketGlobalConfig.get().getWorkspaceConnections().isEmpty());
    }

    @Test
    void workspaceConnectionsRoundTrip(JenkinsRule j) throws Exception {
        BitbucketGlobalConfig config = BitbucketGlobalConfig.get();
        config.setWorkspaceConnections(List.of(
                new BitbucketWorkspaceConnection("team-one", "credential-one"),
                new BitbucketWorkspaceConnection("team-two", "credential-two")));

        j.configRoundtrip();

        List<BitbucketWorkspaceConnection> connections = BitbucketGlobalConfig.get().getWorkspaceConnections();
        assertEquals(2, connections.size());
        assertEquals("team-one", connections.get(0).getWorkspace());
        assertEquals("credential-one", connections.get(0).getCredentialsId());
        assertEquals("team-two", connections.get(1).getWorkspace());
        assertEquals("credential-two", connections.get(1).getCredentialsId());
    }

    @Test
    void invalidWorkspaceSetterPreservesSavedConnections(JenkinsRule j) throws Exception {
        BitbucketGlobalConfig config = BitbucketGlobalConfig.get();
        config.setWorkspaceConnections(List.of(new BitbucketWorkspaceConnection("TeamA", "existing")));

        assertThrows(Descriptor.FormException.class, () -> config.setWorkspaceConnections(List.of(
                new BitbucketWorkspaceConnection("TeamA", "first"),
                new BitbucketWorkspaceConnection("teama", "second"))));

        assertEquals(1, config.getWorkspaceConnections().size());
        assertEquals("TeamA", config.getWorkspaceConnections().get(0).getWorkspace());
        assertEquals("existing", config.getWorkspaceConnections().get(0).getCredentialsId());
    }

    @Test
    void rejectsInvalidWorkspaceSlugs(JenkinsRule j) {
        for (String slug : new String[] {"", " ", "team/repo", "team?query", "team#fragment", ".", ".."}) {
            FormValidation result = BitbucketGlobalConfig.validateWorkspaceConnections(
                    List.of(new BitbucketWorkspaceConnection(slug, "credential")));
            assertEquals(FormValidation.Kind.ERROR, result.kind, slug);
        }
    }

    @Test
    void rejectsDuplicateWorkspaceSlugsIgnoringCase(JenkinsRule j) {
        FormValidation result = BitbucketGlobalConfig.validateWorkspaceConnections(List.of(
                new BitbucketWorkspaceConnection("TeamA", "first"),
                new BitbucketWorkspaceConnection("teama", "second")));

        assertEquals(FormValidation.Kind.ERROR, result.kind);
    }

    @Test
    void credentialPickerContainsSupportedGlobalCredentials(JenkinsRule j) throws Exception {
        var credentials = SystemCredentialsProvider.getInstance().getCredentials();
        credentials.add(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "up", "d", "u", "p"));
        credentials.add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "str", "d", Secret.fromString("t")));
        credentials.add(new BitbucketOAuthCredentials(CredentialsScope.GLOBAL, "oauth", "d", "id", Secret.fromString("s")));
        credentials.add(new UnsupportedCredentials());

        ListBoxModel options =
                j.jenkins.getDescriptorByType(BitbucketWorkspaceConnection.DescriptorImpl.class)
                        .doFillCredentialsIdItems(null);

        assertTrue(options.stream().anyMatch(option -> "up".equals(option.value)));
        assertTrue(options.stream().anyMatch(option -> "str".equals(option.value)));
        assertTrue(options.stream().anyMatch(option -> "oauth".equals(option.value)));
        assertFalse(options.stream().anyMatch(option -> "unsupported".equals(option.value)));
    }

    @Test
    void nonAdminCannotEnumerateCredentials(JenkinsRule j) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "secret", "d", Secret.fromString("s")));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ)
                .everywhere()
                .to("user"));

        try (var ctx = hudson.security.ACL.as2(
                hudson.model.User.getOrCreateByIdOrFullName("user").impersonate2())) {
            ListBoxModel options = j.jenkins
                    .getDescriptorByType(BitbucketWorkspaceConnection.DescriptorImpl.class)
                    .doFillCredentialsIdItems("current");
            assertEquals(1, options.size());
            assertEquals("current", options.get(0).value);
        }
    }

    @Test
    void defaults(JenkinsRule j) {
        BitbucketGlobalConfig c = BitbucketGlobalConfig.get();
        assertFalse(c.isAllowUnsignedWebhooks());
        assertEquals("https://api.bitbucket.org", c.getApiBaseUrl());
        assertTrue(c.webhookSecret().isEmpty());
        c.setApiBaseUrl("  ");
        assertEquals("https://api.bitbucket.org", c.getApiBaseUrl());
    }

    @Test
    void roundTrip(JenkinsRule j) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "wh", "d", Secret.fromString("s3cret")));
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "tok", "d", Secret.fromString("t")));
        BitbucketGlobalConfig c = BitbucketGlobalConfig.get();
        c.setWebhookSecretId("wh");
        c.setDefaultCredentialsId("tok");
        c.setAllowUnsignedWebhooks(true);
        c.setApiBaseUrl("http://localhost:1234");
        j.configRoundtrip();
        c = BitbucketGlobalConfig.get();
        assertEquals("wh", c.getWebhookSecretId());
        assertEquals("tok", c.getDefaultCredentialsId());
        assertTrue(c.isAllowUnsignedWebhooks());
        assertEquals("http://localhost:1234", c.getApiBaseUrl());
        assertEquals("s3cret", c.webhookSecret().orElseThrow());
    }

    @Test
    void invalidApiBaseUrlFallsBackToDefault(JenkinsRule j) {
        BitbucketGlobalConfig c = BitbucketGlobalConfig.get();
        for (String bad : new String[] {"http://evil.example.com", "not a url", "https://", "ftp://x.org"}) {
            c.setApiBaseUrl(bad);
            assertEquals("https://api.bitbucket.org", c.getApiBaseUrl(), bad);
        }
        for (String ok :
                new String[] {"http://localhost:1", "http://127.0.0.1:2", "http://[::1]:3", "https://example.org"}) {
            c.setApiBaseUrl(ok);
            assertEquals(ok, c.getApiBaseUrl());
        }
    }

    @Test
    void checkApiBaseUrl(JenkinsRule j) {
        BitbucketGlobalConfig c = BitbucketGlobalConfig.get();
        assertEquals(FormValidation.Kind.OK, c.doCheckApiBaseUrl("https://api.bitbucket.org").kind);
        assertEquals(FormValidation.Kind.OK, c.doCheckApiBaseUrl("http://localhost:8080").kind);
        assertEquals(FormValidation.Kind.WARNING, c.doCheckApiBaseUrl("http://example.com").kind);
        assertEquals(FormValidation.Kind.ERROR, c.doCheckApiBaseUrl("not a url").kind);
        assertEquals(FormValidation.Kind.ERROR, c.doCheckApiBaseUrl("https://").kind);
    }

    @Test
    void webhookSecretResolvesWhenScopedToBitbucketDomain(JenkinsRule j) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getDomainCredentialsMap()
                .put(
                        new Domain("bb", null, List.of(new HostnameSpecification("bitbucket.org", null))),
                        List.of(new StringCredentialsImpl(
                                CredentialsScope.GLOBAL, "wh", "d", Secret.fromString("s3cret"))));
        BitbucketGlobalConfig c = BitbucketGlobalConfig.get();
        c.setWebhookSecretId("wh");
        assertEquals("s3cret", c.webhookSecret().orElseThrow());
        ListBoxModel m = c.doFillWebhookSecretIdItems("wh");
        assertTrue(m.stream().anyMatch(o -> "wh".equals(o.value)));
    }

    @Test
    void fillMethodsAdminVsNonAdmin(JenkinsRule j) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "wh", "d", Secret.fromString("s")));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ)
                .everywhere()
                .to("user"));
        BitbucketGlobalConfig c = BitbucketGlobalConfig.get();
        try (var ctx = hudson.security.ACL.as2(
                hudson.model.User.getOrCreateByIdOrFullName("admin").impersonate2())) {
            ListBoxModel m = c.doFillWebhookSecretIdItems(null);
            assertTrue(m.stream().anyMatch(o -> "wh".equals(o.value)));
        }
        try (var ctx = hudson.security.ACL.as2(
                hudson.model.User.getOrCreateByIdOrFullName("user").impersonate2())) {
            ListBoxModel m = c.doFillWebhookSecretIdItems("cur");
            assertEquals(1, m.size());
            assertEquals("cur", m.get(0).value);
            assertEquals(1, c.doFillDefaultCredentialsIdItems("cur").size());
        }
    }
}
