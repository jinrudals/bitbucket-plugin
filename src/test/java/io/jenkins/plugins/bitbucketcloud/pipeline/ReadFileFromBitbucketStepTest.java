package io.jenkins.plugins.bitbucketcloud.pipeline;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import hudson.util.Secret;
import io.jenkins.plugins.bitbucketcloud.api.BitbucketWorkspaceClient;
import io.jenkins.plugins.bitbucketcloud.auth.BasicAuth;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketAuthenticationCredentials;
import io.jenkins.plugins.bitbucketcloud.config.BitbucketGlobalConfig;
import io.jenkins.plugins.bitbucketcloud.config.BitbucketWorkspaceConnection;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@WireMockTest
class ReadFileFromBitbucketStepTest {

    private static final String SRC = "/2.0/repositories/acme/shop/src/";
    private static final String ARGS = "workspace: 'acme', repo: 'shop'";

    @AfterEach
    void restoreClient() {
        CommitStatusPublisher.clientFactory = BitbucketWorkspaceClient::new;
    }

    private void configure(WireMockRuntimeInfo wm) throws Exception {
        CommitStatusPublisher.clientFactory =
                () -> new BitbucketWorkspaceClient(URI.create(wm.getHttpBaseUrl()), Duration.ofSeconds(2));
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new BitbucketAuthenticationCredentials(
                        CredentialsScope.GLOBAL, "bb", "test", "user", Secret.fromString("token")));
        BitbucketGlobalConfig.get().setWorkspaceConnections(List.of(new BitbucketWorkspaceConnection("acme", "bb")));
    }

    private WorkflowJob job(JenkinsRule j, String script) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(script, false));
        return job;
    }

    @Test
    void returnsTheFileContentAtABranch(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(wm);
        stubFor(get(urlEqualTo(SRC + "main/docs/a%20b.txt")).willReturn(aResponse().withStatus(200).withBody("hello")));

        var build = j.buildAndAssertSuccess(job(
                j, "def c = readFileFromBitbucket path: 'docs/a b.txt', ref: 'main', " + ARGS + "\necho \"c=${c}\""));

        j.assertLogContains("c=hello", build);
    }

    @Test
    void encodesSlashesInTheBranchName(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(wm);
        stubFor(get(urlEqualTo(SRC + "feature%2Fx/README.md")).willReturn(aResponse().withStatus(200).withBody("ok")));

        j.buildAndAssertSuccess(job(j, "readFileFromBitbucket path: 'README.md', ref: 'feature/x', " + ARGS));

        verify(1, getRequestedFor(urlEqualTo(SRC + "feature%2Fx/README.md")));
    }

    @Test
    void returnsNullWhenTheFileIsMissing(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(wm);
        stubFor(get(urlEqualTo(SRC + "main/nope.txt")).willReturn(aResponse().withStatus(404)));

        var build = j.buildAndAssertSuccess(job(
                j, "def c = readFileFromBitbucket path: 'nope.txt', ref: 'main', " + ARGS + "\necho \"null=${c == null}\""));

        j.assertLogContains("null=true", build);
    }

    @Test
    void rejectsParentTraversal(WireMockRuntimeInfo wm) {
        var client = new BitbucketWorkspaceClient(URI.create(wm.getHttpBaseUrl()), Duration.ofSeconds(2));
        var auth = new BasicAuth("user", Secret.fromString("test-value"));
        assertThrows(IllegalArgumentException.class, () -> client.readFile("acme", "shop", "main", "a/../b", auth));
        assertEquals(
                BitbucketWorkspaceClient.Outcome.NOT_FOUND,
                client.readFile("acme", "shop", "main", "x", auth).outcome());
    }
}
