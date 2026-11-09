package io.jenkins.plugins.bitbucketcloud;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Result;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.util.Secret;
import io.jenkins.plugins.bitbucketcloud.config.BitbucketGlobalConfig;
import io.jenkins.plugins.bitbucketcloud.trigger.BitbucketTrigger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.CaptureEnvironmentBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@WireMockTest
class EndToEndTest {

    private static final String SECRET = "s3cret";
    private static final String REMOTE = "https://bitbucket.org/acme/app.git";
    private static final String STATUS_PATH = "/2.0/repositories/acme/app/commit/%s/statuses/build";

    private static final List<String> GIT_ENV_KEYS =
            List.of("GIT_CONFIG_COUNT", "GIT_CONFIG_KEY_0", "GIT_CONFIG_VALUE_0");

    private JenkinsRule j;
    private final HttpClient http = HttpClient.newHttpClient();
    private Path repo;
    private String sha;

    @BeforeEach
    void setUp(JenkinsRule rule, WireMockRuntimeInfo wm, @TempDir Path dir) throws Exception {
        j = rule;
        repo = dir;
        git("init", "-q", "-b", "main");
        Files.writeString(
                repo.resolve("Jenkinsfile"),
                "println \"branch=${env.bitbucketSourceBranch} commit=${env.bitbucketCommitHash}\"\n");
        git("add", ".");
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init");
        sha = git("rev-parse", "HEAD").trim();

        // Redirect the Bitbucket remote to the local repository so checkouts work offline.
        EnvironmentVariablesNodeProperty env = new EnvironmentVariablesNodeProperty(
                new EnvironmentVariablesNodeProperty.Entry("GIT_CONFIG_COUNT", "1"),
                new EnvironmentVariablesNodeProperty.Entry("GIT_CONFIG_KEY_0", "url." + repo + ".insteadOf"),
                new EnvironmentVariablesNodeProperty.Entry("GIT_CONFIG_VALUE_0", REMOTE));
        j.jenkins.getGlobalNodeProperties().add(env);
        // Multibranch indexing runs git with the controller environment rather than node properties.
        for (String k : GIT_ENV_KEYS) {
            hudson.EnvVars.masterEnvVars.put(k, env.getEnvVars().get(k));
        }

        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "wh", "d", Secret.fromString(SECRET)));
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "tok", "d", Secret.fromString("t")));
        BitbucketGlobalConfig cfg = BitbucketGlobalConfig.get();
        cfg.setWebhookSecretId("wh");
        cfg.setAllowUnsignedWebhooks(false);
        cfg.setDefaultCredentialsId("tok");
        cfg.setApiBaseUrl(wm.getHttpBaseUrl());
        stubFor(post(urlPathMatching("/2.0/repositories/.*/statuses/build"))
                .willReturn(aResponse().withStatus(201)));
    }

    @AfterEach
    void clearGitEnv() {
        GIT_ENV_KEYS.forEach(hudson.EnvVars.masterEnvVars::remove);
    }

    private String git(String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    private GitSCM scm() {
        GitSCM scm = new GitSCM(
                List.of(new UserRemoteConfig(REMOTE, null, null, null)),
                List.of(new BranchSpec("*/main")),
                null,
                null,
                List.of());
        return scm;
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private String payload(String name, String repoName) throws Exception {
        return Files.readString(Path.of("src/test/resources/payloads", name))
                .replace("acme/app", repoName)
                .replace("abc123", sha)
                .replace("fff999", sha);
    }

    private int webhook(String job, String event, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest r = HttpRequest.newBuilder(URI.create(j.getURL() + "bitbucket-webhook/job/" + job))
                .header("X-Event-Key", event)
                .header("X-Hub-Signature", sign(bytes))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        return http.send(r, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private void await(String what, BooleanSupplier cond) throws Exception {
        long end = System.currentTimeMillis() + 90_000;
        while (!cond.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < end, "Timed out waiting for " + what);
            Thread.sleep(100);
        }
    }

    private void awaitFinishedBuild(Job<?, ?> job) throws Exception {
        await(
                "build of " + job.getFullName(),
                () -> job.getLastBuild() != null && !job.getLastBuild().isBuilding());
        j.waitUntilNoActivity();
    }

    private void verifyStatuses(String hash) {
        verify(
                1,
                postRequestedFor(urlEqualTo(STATUS_PATH.formatted(hash)))
                        .withRequestBody(matchingJsonPath("$.state", equalTo("INPROGRESS"))));
        verify(
                1,
                postRequestedFor(urlEqualTo(STATUS_PATH.formatted(hash)))
                        .withRequestBody(matchingJsonPath("$.state", equalTo("SUCCESSFUL"))));
    }

    @Test
    void freestyleBuildsFromSignedPushAndReportsStatus() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("fs");
        p.setScm(scm());
        CaptureEnvironmentBuilder capture = new CaptureEnvironmentBuilder();
        p.getBuildersList().add(capture);
        BitbucketTrigger t = new BitbucketTrigger();
        p.addTrigger(t);
        t.start(p, true);

        assertEquals(202, webhook("fs", "repo:push", payload("push.json", "acme/app")));
        awaitFinishedBuild(p);

        FreeStyleBuild b = p.getLastBuild();
        assertEquals(Result.SUCCESS, b.getResult());
        assertEquals("main", capture.getEnvVars().get("bitbucketSourceBranch"));
        assertEquals(sha, capture.getEnvVars().get("bitbucketCommitHash"));
        assertEquals("acme/app", capture.getEnvVars().get("bitbucketRepoFullName"));
        verifyStatuses(sha);
    }

    @Test
    void pipelineBuildsFromSignedPullRequestAndReportsStatus() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "pipe");
        p.setDefinition(new CpsScmFlowDefinition(scm(), "Jenkinsfile"));
        BitbucketTrigger t = new BitbucketTrigger();
        p.addTrigger(t);
        t.start(p, true);

        assertEquals(202, webhook("pipe", "pullrequest:created", payload("pr-created.json", "acme/app")));
        awaitFinishedBuild(p);

        WorkflowRun b = p.getLastBuild();
        j.assertBuildStatusSuccess(b);
        j.assertLogContains("branch=feature/x commit=" + sha, b);
        verifyStatuses(sha);
    }

    @Test
    void pushThenPullRequestUpdateOnSameCommitBuildsOnce() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("dedupe");
        p.setScm(scm());
        BitbucketTrigger t = new BitbucketTrigger();
        p.addTrigger(t);
        t.start(p, true);

        assertEquals(202, webhook("dedupe", "repo:push", payload("push.json", "acme/app")));
        awaitFinishedBuild(p);
        assertEquals(2, p.getNextBuildNumber());

        assertEquals(202, webhook("dedupe", "pullrequest:updated", payload("pr-updated.json", "acme/app")));
        Thread.sleep(2000);
        j.waitUntilNoActivity();
        assertEquals(2, p.getNextBuildNumber());
        assertNull(p.getBuildByNumber(2));
    }

    @Test
    void unknownRepositoryTriggersNothing() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("fs");
        p.setScm(scm());
        BitbucketTrigger t = new BitbucketTrigger();
        p.addTrigger(t);
        t.start(p, true);

        assertEquals(202, webhook("fs", "repo:push", payload("push.json", "other/unknown")));
        Thread.sleep(2000);
        j.waitUntilNoActivity();
        assertNull(p.getLastBuild());
        verify(0, postRequestedFor(urlPathMatching("/2.0/repositories/.*")));
    }
}
