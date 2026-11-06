package io.jenkins.plugins.bitbucketcloud.status;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.plugins.git.Branch;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.Build;
import hudson.plugins.git.util.BuildData;
import hudson.util.Secret;
import io.jenkins.plugins.bitbucketcloud.config.BitbucketGlobalConfig;
import io.jenkins.plugins.bitbucketcloud.event.PushEvent;
import io.jenkins.plugins.bitbucketcloud.trigger.BitbucketBuildAction;
import io.jenkins.plugins.bitbucketcloud.trigger.BitbucketTrigger;
import org.eclipse.jgit.lib.ObjectId;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.UnstableBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@WireMockTest
class StatusReporterTest {
    private static final String HASH = "abcdef1234567890abcdef1234567890abcdef12";
    private static final String PATH = "/2.0/repositories/ws/repo/commit/" + HASH + "/statuses/build";

    private void setup(JenkinsRule j, WireMockRuntimeInfo wm, boolean globalCred) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "tok", "d", Secret.fromString("t")));
        BitbucketGlobalConfig cfg = BitbucketGlobalConfig.get();
        cfg.setApiBaseUrl(wm.getHttpBaseUrl());
        cfg.setDefaultCredentialsId(globalCred ? "tok" : null);
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201)));
    }

    private FreeStyleProject project(JenkinsRule j, boolean triggerCred) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("job");
        if (triggerCred) {
            BitbucketTrigger t = new BitbucketTrigger();
            t.setCredentialsId("tok");
            p.addTrigger(t);
        }
        return p;
    }

    private FreeStyleBuild run(FreeStyleProject p) throws Exception {
        return p.scheduleBuild2(
                        0,
                        new hudson.model.Cause.UserIdCause(),
                        new BitbucketBuildAction(new PushEvent("ws/repo", "a", "main", HASH)))
                .get();
    }

    @Test
    void successPostsInProgressThenSuccessful(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        setup(j, wm, false);
        FreeStyleBuild b = run(project(j, true));
        assertEquals(Result.SUCCESS, b.getResult());
        verify(
                1,
                postRequestedFor(urlEqualTo(PATH))
                        .withRequestBody(matchingJsonPath("$.state", equalTo("INPROGRESS")))
                        .withRequestBody(matchingJsonPath("$.key"))
                        .withRequestBody(matchingJsonPath("$.name", containing("job")))
                        .withRequestBody(matchingJsonPath("$.url", containing("job/1"))));
        verify(
                1,
                postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("SUCCESSFUL"))));
    }

    @Test
    void failureAndUnstableMapped(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        setup(j, wm, true);
        FreeStyleProject p = project(j, false);
        p.getBuildersList().add(new FailureBuilder());
        assertEquals(Result.FAILURE, run(p).getResult());
        verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("FAILED"))));
        p.getBuildersList().clear();
        p.getBuildersList().add(new UnstableBuilder());
        assertEquals(Result.UNSTABLE, run(p).getResult());
        verify(
                1,
                postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("SUCCESSFUL"))));
    }

    @Test
    void abortedMapsToStopped(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        setup(j, wm, true);
        FreeStyleProject p = project(j, false);
        p.getBuildersList().add(new org.jvnet.hudson.test.TestBuilder() {
            @Override
            public boolean perform(
                    hudson.model.AbstractBuild<?, ?> build, hudson.Launcher l, hudson.model.BuildListener bl) {
                build.setResult(Result.ABORTED);
                return true;
            }
        });
        assertEquals(Result.ABORTED, run(p).getResult());
        verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("STOPPED"))));
    }

    @Test
    void noCredentialsSkipsSilently(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        setup(j, wm, false);
        assertEquals(Result.SUCCESS, run(project(j, false)).getResult());
        verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void apiErrorDoesNotChangeResult(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        setup(j, wm, true);
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)));
        assertEquals(Result.SUCCESS, run(project(j, false)).getResult());
        verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void multibranchStyleBuildDerivesFromGitData(JenkinsRule j, WireMockRuntimeInfo wm) throws Exception {
        setup(j, wm, true);
        FreeStyleProject p = project(j, false);
        p.getBuildersList().add(new org.jvnet.hudson.test.TestBuilder() {
            @Override
            public boolean perform(
                    hudson.model.AbstractBuild<?, ?> build, hudson.Launcher l, hudson.model.BuildListener bl) {
                BuildData d = new BuildData();
                d.addRemoteUrl("https://bitbucket.org/ws/repo.git");
                Revision r = new Revision(
                        ObjectId.fromString(HASH),
                        java.util.List.of(new Branch("origin/main", ObjectId.fromString(HASH))));
                d.saveBuild(new Build(r, 1, Result.SUCCESS));
                build.addAction(d);
                return true;
            }
        });
        assertEquals(Result.SUCCESS, p.scheduleBuild2(0).get().getResult());
        verify(
                1,
                postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.state", equalTo("SUCCESSFUL"))));
    }
}
