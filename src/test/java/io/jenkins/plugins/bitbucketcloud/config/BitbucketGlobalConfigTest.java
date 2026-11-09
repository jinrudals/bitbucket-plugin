package io.jenkins.plugins.bitbucketcloud.config;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.bitbucketcloud.api.BitbucketWorkspaceClient;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketOAuthCredentials;
import java.util.List;
import java.util.OptionalInt;
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
    void testConnectionRequiresAdminister(JenkinsRule j) throws Exception {
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
            FormValidation result = j.jenkins
                    .getDescriptorByType(BitbucketWorkspaceConnection.DescriptorImpl.class)
                    .doTestConnection("team", "missing");
            assertEquals(FormValidation.Kind.ERROR, result.kind);
        }
    }

    @Test
    void testConnectionRejectsMissingOrUnsupportedCredentials(JenkinsRule j) {
        FormValidation result = j.jenkins
                .getDescriptorByType(BitbucketWorkspaceConnection.DescriptorImpl.class)
                .doTestConnection("team", "missing");

        assertEquals(FormValidation.Kind.ERROR, result.kind);
        assertTrue(result.getMessage().contains("credential"));
    }

    @Test
    void testConnectionMapsClientResultToFormValidation(JenkinsRule j) {
        var descriptor = j.jenkins.getDescriptorByType(BitbucketWorkspaceConnection.DescriptorImpl.class);
        var outcomes = new BitbucketWorkspaceClient.Outcome[] {
            BitbucketWorkspaceClient.Outcome.SUCCESS,
            BitbucketWorkspaceClient.Outcome.UNAUTHORIZED,
            BitbucketWorkspaceClient.Outcome.FORBIDDEN,
            BitbucketWorkspaceClient.Outcome.NOT_FOUND,
            BitbucketWorkspaceClient.Outcome.HTTP_ERROR,
            BitbucketWorkspaceClient.Outcome.AUTHENTICATION_ERROR,
            BitbucketWorkspaceClient.Outcome.TIMEOUT,
            BitbucketWorkspaceClient.Outcome.NETWORK_ERROR,
            BitbucketWorkspaceClient.Outcome.INTERRUPTED
        };
        for (BitbucketWorkspaceClient.Outcome outcome : outcomes) {
            FormValidation validation = descriptor.toFormValidation(
                    new BitbucketWorkspaceClient.WorkspaceTestResult(outcome, OptionalInt.empty()));
            FormValidation.Kind expected = outcome == BitbucketWorkspaceClient.Outcome.SUCCESS
                    ? FormValidation.Kind.OK
                    : FormValidation.Kind.ERROR;
            assertEquals(expected, validation.kind, outcome.name());
            assertFalse(validation.getMessage().contains("secret"));
            assertFalse(validation.getMessage().contains("RESPONSE_BODY_MARKER"));
        }
    }

}
