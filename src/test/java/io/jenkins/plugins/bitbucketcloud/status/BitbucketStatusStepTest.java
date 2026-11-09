package io.jenkins.plugins.bitbucketcloud.status;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import hudson.model.Cause;
import hudson.model.Result;
import hudson.util.Secret;
import io.jenkins.plugins.bitbucketcloud.config.BitbucketGlobalConfig;
import io.jenkins.plugins.bitbucketcloud.event.PushEvent;
import io.jenkins.plugins.bitbucketcloud.trigger.BitbucketBuildAction;
import java.util.List;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@WireMockTest
class BitbucketStatusStepTest {
    private static final String HASH = "abcdef1234567890abcdef1234567890abcdef12";
    private static final String PATH = "/2.0/repositories/ws/repo/commit/" + HASH + "/statuses/build";

    private void setup(JenkinsRule j, WireMockRuntimeInfo wm, int status) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "tok", "d", Secret.fromString("t")));
        BitbucketGlobalConfig cfg = BitbucketGlobalConfig.get();
        cfg.setApiBaseUrl(wm.getHttpBaseUrl());
        cfg.setDefaultCredentialsId("tok");
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)));
    }

    private WorkflowRun run(JenkinsRule j, String script, boolean withCommit) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "job");
        p.setDefinition(new CpsFlowDefinition(script, true));
        if (withCommit) {
            return p.scheduleBuild2(
                            0,
                            new hudson.model.CauseAction(new Cause.UserIdCause()),
                            new BitbucketBuildAction(new PushEvent("ws/repo", "a", "main", HASH)))
                    .get();
        }
        return p.scheduleBuild2(0).get();
    }

    private static String bodyOf(LoggedRequest r) {
        return r.getBodyAsString();
    }

    @Test
    void threeNamedStatusesPostThreeKeys(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        setup(j, wm, 201);
        WorkflowRun b = run(
                j,
                "bitbucketStatus(name: 'lint', state: 'SUCCESSFUL')\n"
                        + "bitbucketStatus(name: 'test', state: 'FAILED', description: 'boom')\n"
                        + "bitbucketStatus(name: 'deploy', state: 'STOPPED')",
                true);
        assertEquals(Result.SUCCESS, b.getResult());
        for (String n : List.of("lint", "test", "deploy")) {
            verify(
                    1,
                    postRequestedFor(urlEqualTo(PATH))
                            .withRequestBody(matchingJsonPath("$.key", equalTo("job/" + n)))
                            .withRequestBody(matchingJsonPath("$.name", equalTo("Jenkins: job / " + n))));
        }
        verify(
                1,
                postRequestedFor(urlEqualTo(PATH))
                        .withRequestBody(matchingJsonPath("$.key", equalTo("job/test")))
                        .withRequestBody(matchingJsonPath("$.state", equalTo("FAILED")))
                        .withRequestBody(matchingJsonPath("$.description", equalTo("boom"))));
        verify(
                2,
                postRequestedFor(urlEqualTo(PATH))
                        .withRequestBody(matchingJsonPath("$.key", equalTo("job")))
                        .withRequestBody(matchingJsonPath("$.name", equalTo("Jenkins: job"))));
    }

    @Test
    void sameNameTwiceReusesKey(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        setup(j, wm, 201);
        run(
                j,
                "bitbucketStatus(name: 'lint', state: 'INPROGRESS')\nbitbucketStatus(name: 'lint', state: 'SUCCESSFUL')",
                true);
        verify(2, postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.key", equalTo("job/lint"))));
    }

    @Test
    void invalidStateOrBlankNameFailsBuild(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        setup(j, wm, 201);
        WorkflowRun b = run(j, "bitbucketStatus(name: 'lint', state: 'BOGUS')", true);
        assertEquals(Result.FAILURE, b.getResult());
        j.assertLogContains("'state' must be one of", b);
        j.jenkins.getItemByFullName("job").delete();
        WorkflowRun b2 = run(j, "bitbucketStatus(name: ' ', state: 'SUCCESSFUL')", true);
        assertEquals(Result.FAILURE, b2.getResult());
        j.assertLogContains("'name' must not be blank", b2);
    }

    @Test
    void bitbucketErrorDoesNotFailBuild(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        setup(j, wm, 500);
        WorkflowRun b = run(j, "bitbucketStatus(name: 'lint', state: 'SUCCESSFUL')", true);
        assertEquals(Result.SUCCESS, b.getResult());
    }

    @Test
    void unresolvedTargetWarnsAndContinues(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        setup(j, wm, 201);
        WorkflowRun b = run(j, "bitbucketStatus(name: 'lint', state: 'SUCCESSFUL')", false);
        assertEquals(Result.SUCCESS, b.getResult());
        j.assertLogContains("could not post 'lint'", b);
        verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void longKeysAreHashedAndDistinct(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        setup(j, wm, 201);
        String a = "a".repeat(45);
        String c = "a".repeat(44) + "b";
        run(
                j,
                "bitbucketStatus(name: '" + a + "', state: 'SUCCESSFUL')\nbitbucketStatus(name: '" + c
                        + "', state: 'SUCCESSFUL')",
                true);
        List<String> keys = findAll(postRequestedFor(urlEqualTo(PATH))).stream()
                .map(BitbucketStatusStepTest::bodyOf)
                .filter(s -> s.contains("aaaa"))
                .map(s -> s.replaceAll(".*\"key\":\"([^\"]*)\".*", "$1"))
                .toList();
        assertEquals(2, keys.size());
        assertNotEquals(keys.get(0), keys.get(1));
        keys.forEach(k -> assertTrue(k.length() <= 40, k));
    }
}
