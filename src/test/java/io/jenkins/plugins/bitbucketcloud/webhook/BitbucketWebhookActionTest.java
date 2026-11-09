package io.jenkins.plugins.bitbucketcloud.webhook;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.csrf.DefaultCrumbIssuer;
import hudson.triggers.TriggerDescriptor;
import hudson.util.Secret;
import io.jenkins.plugins.bitbucketcloud.config.BitbucketGlobalConfig;
import io.jenkins.plugins.bitbucketcloud.event.BitbucketEvent;
import io.jenkins.plugins.bitbucketcloud.trigger.BitbucketTrigger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@WithJenkins
class BitbucketWebhookActionTest {

    private static final String SECRET = "s3cret";
    private static final String REPO = "acme/app";

    private JenkinsRule j;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        j.jenkins.setCrumbIssuer(new DefaultCrumbIssuer(false));
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl(
                        CredentialsScope.GLOBAL, "wh", "d", Secret.fromString(SECRET)));
        BitbucketGlobalConfig.get().setWebhookSecretId("wh");
        BitbucketGlobalConfig.get().setAllowUnsignedWebhooks(false);
    }

    private FreeStyleProject job(String name, String repo, BitbucketTrigger t) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(name);
        t.setRepository(repo);
        p.addTrigger(t);
        t.start(p, true);
        return p;
    }

    private FreeStyleProject job(String name, String repo) throws Exception {
        return job(name, repo, new BitbucketTrigger());
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static byte[] fixture(String name) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/payloads", name));
    }

    private HttpResponse<String> post(String path, byte[] body, String event, String signature) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(j.getURL() + "bitbucket-webhook/" + path))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (event != null) {
            b.header("X-Event-Key", event);
        }
        if (signature != null) {
            b.header("X-Hub-Signature", signature);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> signedPush(String path) throws Exception {
        byte[] body = fixture("push.json");
        return post(path, body, "repo:push", sign(body));
    }

    private void awaitBuilds(Job<?, ?> job, int count) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (job.getNextBuildNumber() <= count && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        j.waitUntilNoActivity();
    }

    private void assertNoBuild(Job<?, ?> job) throws Exception {
        Thread.sleep(700);
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), job.getFullName() + " must not build");
    }

    @Test
    void jobUrlFiresOnlyThatJob() throws Exception {
        FreeStyleProject a = job("a", REPO);
        FreeStyleProject b = job("b", REPO);
        assertEquals(202, signedPush("job/a").statusCode());
        awaitBuilds(a, 1);
        assertEquals(1, a.getBuilds().size());
        assertNoBuild(b);
    }

    @Test
    void rootAndNonJobPathsAre404() throws Exception {
        job("a", REPO);
        assertEquals(404, signedPush("").statusCode());
        assertEquals(404, signedPush("job/").statusCode());
        assertEquals(404, signedPush("other/a").statusCode());
    }

    @Test
    void unknownJobAndTriggerlessJobAre404() throws Exception {
        j.createFreeStyleProject("plain");
        assertEquals(404, signedPush("job/missing").statusCode());
        assertEquals(404, signedPush("job/plain").statusCode());
    }

    @Test
    void badSignatureIs401BeforeJobLookup() throws Exception {
        byte[] body = fixture("push.json");
        assertEquals(
                401,
                post("job/missing", body, "repo:push", "sha256=" + "00".repeat(32))
                        .statusCode());
    }

    @Test
    void encodedJobNameResolves() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("my job");
        BitbucketTrigger t = new BitbucketTrigger();
        t.setRepository(REPO);
        p.addTrigger(t);
        t.start(p, true);
        assertEquals(202, signedPush("job/my%20job").statusCode());
        awaitBuilds(p, 1);
        assertEquals(1, p.getBuilds().size());
    }

    @Test
    void repoMismatchOrBranchFilterFiresNothing() throws Exception {
        FreeStyleProject other = job("other", "acme/elsewhere");
        BitbucketTrigger filtered = new BitbucketTrigger();
        filtered.setSourceBranchIncludes("release/*");
        FreeStyleProject f = job("filtered", REPO, filtered);
        assertEquals(202, signedPush("job/other").statusCode());
        assertEquals(202, signedPush("job/filtered").statusCode());
        assertNoBuild(other);
        assertNoBuild(f);
    }

    @Test
    void multipleEventsOnlyAcceptedOnesBuild() throws Exception {
        BitbucketTrigger t = new BitbucketTrigger();
        t.setSourceBranchIncludes("feature/*");
        t.setSkipDuplicateCommit(false);
        FreeStyleProject p = job("p", REPO, t);
        byte[] body = fixture("push-multi.json");
        assertEquals(202, post("job/p", body, "repo:push", sign(body)).statusCode());
        awaitBuilds(p, 1);
        assertEquals(1, p.getBuilds().size());
    }

    @Test
    void hiddenJobStillTriggeredWhenCallerIsAnonymous() throws Exception {
        AuthRecordingTrigger trigger = new AuthRecordingTrigger();
        FreeStyleProject p = job("hidden", REPO, trigger);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy());
        assertEquals(202, signedPush("job/hidden").statusCode());
        awaitBuilds(p, 1);
        assertEquals(1, p.getBuilds().size());
        assertEquals(ACL.SYSTEM2, trigger.acceptsAuth);
    }

    @Test
    void noSecretConfiguredRejected() throws Exception {
        job("a", REPO);
        BitbucketGlobalConfig.get().setWebhookSecretId(null);
        assertEquals(401, signedPush("job/a").statusCode());
    }

    @Test
    void allowUnsignedSkipsVerification() throws Exception {
        FreeStyleProject a = job("a", REPO);
        BitbucketGlobalConfig.get().setWebhookSecretId(null);
        BitbucketGlobalConfig.get().setAllowUnsignedWebhooks(true);
        assertEquals(202, post("job/a", fixture("push.json"), "repo:push", null).statusCode());
        awaitBuilds(a, 1);
        assertEquals(1, a.getBuilds().size());
    }

    @Test
    void secretConfiguredAlwaysVerifiedEvenIfUnsignedAllowed() throws Exception {
        FreeStyleProject a = job("a", REPO);
        BitbucketGlobalConfig.get().setAllowUnsignedWebhooks(true);
        byte[] body = fixture("push.json");
        assertEquals(
                401,
                post("job/a", body, "repo:push", "sha256=" + "00".repeat(32)).statusCode());
        assertEquals(401, post("job/a", body, "repo:push", null).statusCode());
        assertNoBuild(a);
    }

    @Test
    void malformedJsonAndEmptyBodyAre400() throws Exception {
        job("a", REPO);
        byte[] bad = "{not json".getBytes(StandardCharsets.UTF_8);
        assertEquals(400, post("job/a", bad, "repo:push", sign(bad)).statusCode());
        byte[] empty = new byte[0];
        assertEquals(400, post("job/a", empty, "repo:push", sign(empty)).statusCode());
    }

    @Test
    void unsupportedOrMissingEventKeyIs200() throws Exception {
        FreeStyleProject a = job("a", REPO);
        byte[] body = fixture("push.json");
        assertEquals(200, post("job/a", body, "repo:fork", sign(body)).statusCode());
        assertEquals(200, post("job/a", body, null, sign(body)).statusCode());
        assertNoBuild(a);
    }

    @Test
    void getIs405() throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(j.getURL() + "bitbucket-webhook/job/a"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, r.statusCode());
        assertEquals("POST", r.headers().firstValue("Allow").orElse(null));
    }

    @Test
    void oversizedBodyIs413() throws Exception {
        job("a", REPO);
        byte[] big = new byte[BitbucketWebhookAction.MAX_BODY_BYTES + 1];
        assertEquals(413, post("job/a", big, "repo:push", sign(big)).statusCode());
    }

    public static class AuthRecordingTrigger extends BitbucketTrigger {
        volatile Authentication acceptsAuth;

        @Override
        public boolean accepts(BitbucketEvent e) {
            acceptsAuth = SecurityContextHolder.getContext().getAuthentication();
            return super.accepts(e);
        }

        @Override
        public TriggerDescriptor getDescriptor() {
            return Jenkins.get().getDescriptorByType(BitbucketTrigger.DescriptorImpl.class);
        }
    }
}
