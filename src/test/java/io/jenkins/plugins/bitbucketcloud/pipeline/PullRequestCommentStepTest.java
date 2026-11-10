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
import io.jenkins.plugins.bitbucketcloud.webhook.BitbucketEnvAction;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@WireMockTest
class PullRequestCommentStepTest {

    private static final String COMMENTS = "/2.0/repositories/acme/shop/pullrequests/12/comments";
    private static final String TASKS = "/2.0/repositories/acme/shop/pullrequests/12/tasks";
    private static final String ARGS = "workspace: 'acme', repo: 'shop', pullRequestId: '12'";

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
        stubFor(post(urlEqualTo(COMMENTS)).willReturn(aResponse().withStatus(201).withBody("{\"id\": 345}")));
        stubFor(post(urlEqualTo(TASKS)).willReturn(aResponse().withStatus(201).withBody("{\"id\": 21}")));
    }

    private WorkflowJob job(JenkinsRule j, String script) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(script, false));
        return job;
    }

    @Test
    void createsAThreadAndReturnsItsId(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(wm);

        var build = j.buildAndAssertSuccess(
                job(j, "def id = commentOnBitbucketPullRequest content: 'hello', " + ARGS + "\necho \"id=${id}\""));

        j.assertLogContains("id=345", build);
        verify(postRequestedFor(urlEqualTo(COMMENTS))
                .withRequestBody(matchingJsonPath("$.content.raw", equalTo("hello"))));
    }

    @Test
    void createsATask(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(wm);

        j.buildAndAssertSuccess(job(j, "commentOnBitbucketPullRequest content: 'todo', type: 'task', " + ARGS));

        verify(1, postRequestedFor(urlEqualTo(TASKS)).withRequestBody(matchingJsonPath("$.content.raw", equalTo("todo"))));
        verify(0, postRequestedFor(urlEqualTo(COMMENTS)));
    }

    @Test
    void repliesToAnExistingThread(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(wm);

        j.buildAndAssertSuccess(job(j, "commentOnBitbucketPullRequest content: 'ok', parentId: '7', " + ARGS));

        verify(postRequestedFor(urlEqualTo(COMMENTS)).withRequestBody(matchingJsonPath("$.parent.id", equalTo("7"))));
    }

    @Test
    void resolvesTheTargetFromTheEnvironment(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(wm);
        String script = "withEnv(['BITBUCKET_WORKSPACE=acme','BITBUCKET_REPOSITORY_NAME=shop','BITBUCKET_PR_ID=12']) {"
                + " commentOnBitbucketPullRequest content: 'env' }";

        j.buildAndAssertSuccess(job(j, script));

        verify(postRequestedFor(urlEqualTo(COMMENTS)).withRequestBody(matchingJsonPath("$.content.raw", equalTo("env"))));
    }

    @Test
    void takesAllOpenFieldsFromTheFirstCompleteSource(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(wm);
        var job = job(
                j,
                "withEnv(['BITBUCKET_WORKSPACE=other','BITBUCKET_PR_ID=99']) {"
                        + " commentOnBitbucketPullRequest content: 'x' }");

        var build = j.assertBuildStatusSuccess(job.scheduleBuild2(
                        0,
                        new BitbucketEnvAction(Map.of(
                                "BITBUCKET_WORKSPACE", "acme",
                                "BITBUCKET_REPOSITORY_NAME", "shop",
                                "BITBUCKET_PR_ID", "12"))));

        j.assertLogContains("BitbucketEnvAction", build);
        verify(1, postRequestedFor(urlEqualTo(COMMENTS)));
    }

    @Test
    void missingPullRequestIdFailsTheBuild(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(wm);

        var build = j.assertBuildStatus(
                Result.FAILURE,
                job(j, "commentOnBitbucketPullRequest content: 'x', workspace: 'acme', repo: 'shop'")
                        .scheduleBuild2(0));

        j.assertLogContains("pullRequestId", build);
        verify(0, postRequestedFor(urlEqualTo(COMMENTS)));
    }

    @Test
    void unknownTypeFailsTheBuild(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(wm);

        var build = j.assertBuildStatus(
                Result.FAILURE,
                job(j, "commentOnBitbucketPullRequest content: 'x', type: 'nope', " + ARGS).scheduleBuild2(0));

        j.assertLogContains("THREAD", build);
    }

    @Test
    void apiFailureOnlyWarns(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        configure(wm);
        stubFor(post(urlEqualTo(COMMENTS)).willReturn(aResponse().withStatus(403)));

        var build = j.buildAndAssertSuccess(job(j, "commentOnBitbucketPullRequest content: 'x', " + ARGS));

        j.assertLogContains("403", build);
    }
}
