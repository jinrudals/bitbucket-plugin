package io.jenkins.plugins.bitbucketcloud.pipeline;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import hudson.model.Result;
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
class CommitStatusStepsTest {

    private static final String PATH = "/2.0/repositories/acme/shop/commit/abc1234/statuses/build";
    private static final String ARGS = "workspace: 'acme', repo: 'shop', commit: 'abc1234'";

    @AfterEach
    void restoreClient() {
        CommitStatusPublisher.clientFactory = BitbucketWorkspaceClient::new;
    }

    private void configure(JenkinsRule j, WireMockRuntimeInfo wm, boolean registerWorkspace) throws Exception {
        CommitStatusPublisher.clientFactory =
                () -> new BitbucketWorkspaceClient(URI.create(wm.getHttpBaseUrl()), Duration.ofSeconds(2));
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new BitbucketAuthenticationCredentials(
                        CredentialsScope.GLOBAL, "bb", "test", "user", Secret.fromString("token")));
        if (registerWorkspace) {
            BitbucketGlobalConfig.get()
                    .setWorkspaceConnections(List.of(new BitbucketWorkspaceConnection("acme", "bb")));
        }
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201)));
    }

    private WorkflowJob job(JenkinsRule j, String script) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(script, false));
        return job;
    }

    @Test
    void updateStepPublishesTheGivenState(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(j, wm, true);

        j.buildAndAssertSuccess(job(j, "updateBitbucketCommitStatus state: 'SUCCESSFUL', key: 'ci', " + ARGS));

        verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", matching("Basic .+"))
                .withRequestBody(matchingJsonPath("$.state", equalTo("SUCCESSFUL")))
                .withRequestBody(matchingJsonPath("$.key", equalTo("ci")))
                .withRequestBody(matchingJsonPath("$.url", matching("http.*/job/.*"))));
    }

    @Test
    void updateStepResolvesTargetFromEnvironment(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(j, wm, true);
        String script = "withEnv(['BITBUCKET_WORKSPACE=acme','BITBUCKET_REPOSITORY_NAME=shop',"
                + "'BITBUCKET_COMMIT_AFTER=abc1234']) { updateBitbucketCommitStatus state: 'INPROGRESS' }";

        var build = j.buildAndAssertSuccess(job(j, script));

        j.assertLogContains("environment", build);
        verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("INPROGRESS"))));
    }

    @Test
    void longJobNamesGetAKeyWithinBitbucketsLimit(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(j, wm, true);
        WorkflowJob job = j.createProject(WorkflowJob.class, "a-very-long-job-name-that-is-well-over-forty-chars");
        job.setDefinition(new CpsFlowDefinition("updateBitbucketCommitStatus state: 'FAILED', " + ARGS, false));

        j.buildAndAssertSuccess(job);

        verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.key", matching("a-very-long-job-name-that-is-.{1,5}-[0-9a-f]{8}"))));
    }

    @Test
    void unknownStateFailsTheBuild(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(j, wm, true);

        var build = j.assertBuildStatus(
                Result.FAILURE, job(j, "updateBitbucketCommitStatus state: 'running', " + ARGS).scheduleBuild2(0));

        j.assertLogContains("INPROGRESS", build);
        verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void unregisteredWorkspaceFailsTheBuild(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(j, wm, false);

        var build = j.assertBuildStatus(
                Result.FAILURE, job(j, "updateBitbucketCommitStatus state: 'SUCCESSFUL', " + ARGS).scheduleBuild2(0));

        j.assertLogContains("acme", build);
        verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void unresolvableTargetFailsTheBuild(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(j, wm, true);

        var build = j.assertBuildStatus(
                Result.FAILURE, job(j, "updateBitbucketCommitStatus state: 'SUCCESSFUL'").scheduleBuild2(0));

        j.assertLogContains("Cannot resolve", build);
    }

    @Test
    void apiFailureOnlyWarns(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(j, wm, true);
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(403)));

        var build = j.buildAndAssertSuccess(job(j, "updateBitbucketCommitStatus state: 'SUCCESSFUL', " + ARGS));

        j.assertLogContains("403", build);
    }

    @Test
    void blockPublishesInProgressThenSuccessful(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(j, wm, true);

        j.buildAndAssertSuccess(job(j, "bitbucketCommitStatus(" + ARGS + ") { echo 'work' }"));

        verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("INPROGRESS"))));
        verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("SUCCESSFUL"))));
    }

    @Test
    void blockPublishesFailedAndKeepsTheFailure(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(j, wm, true);

        j.assertBuildStatus(
                Result.FAILURE, job(j, "bitbucketCommitStatus(" + ARGS + ") { error 'boom' }").scheduleBuild2(0));

        verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("INPROGRESS"))));
        verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("FAILED"))));
        verify(0, postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("SUCCESSFUL"))));
    }

    @Test
    void blockPublishesStoppedWhenAborted(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(j, wm, true);
        String body = "throw new org.jenkinsci.plugins.workflow.steps.FlowInterruptedException("
                + "hudson.model.Result.ABORTED, new jenkins.model.CauseOfInterruption.UserInterruption('tester'))";

        j.assertBuildStatus(
                Result.ABORTED, job(j, "bitbucketCommitStatus(" + ARGS + ") { " + body + " }").scheduleBuild2(0));

        verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("STOPPED"))));
    }
}
