package io.jenkins.plugins.bitbucketcloud.pipeline;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import hudson.util.Secret;
import io.jenkins.plugins.bitbucketcloud.api.BitbucketWorkspaceClient;
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
class GetBitbucketProjectStepTest {

    private static final String REPO = "/2.0/repositories/acme/shop";
    private static final String CALL = "getBitbucketProject workspace: 'acme', repo: 'shop'";

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
    void returnsTheProjectAsAMap(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(wm);
        stubFor(get(urlEqualTo(REPO))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"project\": {\"key\": \"PROJ\", \"name\": \"My Project\","
                                + " \"uuid\": \"{u-1}\", \"is_private\": true}}")));

        var build = j.buildAndAssertSuccess(
                job(j, "def p = " + CALL + "\necho \"key=${p.key} name=${p.name} private=${p.isPrivate}\""));

        j.assertLogContains("key=PROJ name=My Project private=true", build);
    }

    @Test
    void returnsNullWhenTheRepositoryIsMissing(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(wm);
        stubFor(get(urlEqualTo(REPO)).willReturn(aResponse().withStatus(404)));

        var build = j.buildAndAssertSuccess(job(j, "def p = " + CALL + "\necho \"null=${p == null}\""));

        j.assertLogContains("null=true", build);
        j.assertLogContains("repository not found", build);
    }

    @Test
    void returnsNullWhenTheRepositoryHasNoProject(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(wm);
        stubFor(get(urlEqualTo(REPO)).willReturn(aResponse().withStatus(200).withBody("{}")));

        var build = j.buildAndAssertSuccess(job(j, "def p = " + CALL + "\necho \"null=${p == null}\""));

        j.assertLogContains("null=true", build);
    }
}
