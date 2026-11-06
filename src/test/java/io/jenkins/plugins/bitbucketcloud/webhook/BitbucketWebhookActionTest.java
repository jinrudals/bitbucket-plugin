package io.jenkins.plugins.bitbucketcloud.webhook;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.security.csrf.DefaultCrumbIssuer;
import hudson.util.Secret;
import io.jenkins.plugins.bitbucketcloud.config.BitbucketGlobalConfig;
import io.jenkins.plugins.bitbucketcloud.event.BitbucketEvent;
import io.jenkins.plugins.bitbucketcloud.event.PushEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BitbucketWebhookActionTest {

    private static final String SECRET = "s3cret";
    static final BlockingQueue<BitbucketEvent> RECEIVED = new LinkedBlockingQueue<>();

    static volatile String failOnBranch;

    private JenkinsRule j;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        RECEIVED.clear();
        failOnBranch = null;
        j.jenkins.setCrumbIssuer(new DefaultCrumbIssuer(false));
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl(
                        CredentialsScope.GLOBAL, "wh", "d", Secret.fromString(SECRET)));
        BitbucketGlobalConfig.get().setWebhookSecretId("wh");
        BitbucketGlobalConfig.get().setAllowUnsignedWebhooks(false);
    }

    @TestExtension
    public static class Collector implements EventDispatcher {
        @Override
        public void dispatch(BitbucketEvent e) {
            if (e instanceof PushEvent p && p.branch().equals(failOnBranch)) {
                throw new IllegalStateException("boom");
            }
            RECEIVED.add(e);
        }
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static byte[] fixture(String name) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/payloads", name));
    }

    private HttpResponse<String> post(byte[] body, String event, String signature) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(j.getURL() + "bitbucket-webhook/"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (event != null) {
            b.header("X-Event-Key", event);
        }
        if (signature != null) {
            b.header("X-Hub-Signature", signature);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void validSignedPushIsAccepted() throws Exception {
        byte[] body = fixture("push.json");
        assertEquals(202, post(body, "repo:push", sign(body)).statusCode());
        assertNotNull(RECEIVED.poll(10, TimeUnit.SECONDS));
        assertNull(RECEIVED.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void badSignatureRejected() throws Exception {
        byte[] body = fixture("push.json");
        assertEquals(401, post(body, "repo:push", "sha256=" + "00".repeat(32)).statusCode());
        assertEquals(401, post(body, "repo:push", null).statusCode());
        assertNull(RECEIVED.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void noSecretConfiguredRejected() throws Exception {
        BitbucketGlobalConfig.get().setWebhookSecretId(null);
        byte[] body = fixture("push.json");
        assertEquals(401, post(body, "repo:push", sign(body)).statusCode());
        assertNull(RECEIVED.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void allowUnsignedSkipsVerification() throws Exception {
        BitbucketGlobalConfig.get().setWebhookSecretId(null);
        BitbucketGlobalConfig.get().setAllowUnsignedWebhooks(true);
        assertEquals(202, post(fixture("push.json"), "repo:push", null).statusCode());
        assertNotNull(RECEIVED.poll(10, TimeUnit.SECONDS));
    }

    @Test
    void malformedJsonAndEmptyBodyAre400() throws Exception {
        byte[] bad = "{not json".getBytes(StandardCharsets.UTF_8);
        assertEquals(400, post(bad, "repo:push", sign(bad)).statusCode());
        byte[] empty = new byte[0];
        assertEquals(400, post(empty, "repo:push", sign(empty)).statusCode());
    }

    @Test
    void unsupportedOrMissingEventKeyIs200() throws Exception {
        byte[] body = fixture("push.json");
        assertEquals(200, post(body, "repo:fork", sign(body)).statusCode());
        assertEquals(200, post(body, null, sign(body)).statusCode());
        assertNull(RECEIVED.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void getIs405() throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(j.getURL() + "bitbucket-webhook/"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, r.statusCode());
        assertEquals("POST", r.headers().firstValue("Allow").orElse(null));
    }

    @Test
    void failingDispatchDoesNotAffectOtherEvents() throws Exception {
        failOnBranch = "main";
        byte[] body = fixture("push-multi.json");
        assertEquals(202, post(body, "repo:push", sign(body)).statusCode());
        BitbucketEvent e = RECEIVED.poll(10, TimeUnit.SECONDS);
        assertInstanceOf(PushEvent.class, e);
        assertEquals("feature/x", ((PushEvent) e).branch());
    }

    @Test
    void secretConfiguredAlwaysVerifiedEvenIfUnsignedAllowed() throws Exception {
        BitbucketGlobalConfig.get().setAllowUnsignedWebhooks(true);
        byte[] body = fixture("push.json");
        assertEquals(401, post(body, "repo:push", "sha256=" + "00".repeat(32)).statusCode());
        assertEquals(401, post(body, "repo:push", null).statusCode());
        assertNull(RECEIVED.poll(500, TimeUnit.MILLISECONDS));
        assertEquals(202, post(body, "repo:push", sign(body)).statusCode());
    }

    @Test
    void oversizedBodyIs413() throws Exception {
        byte[] big = new byte[BitbucketWebhookAction.MAX_BODY_BYTES + 1];
        assertEquals(413, post(big, "repo:push", sign(big)).statusCode());
        assertNull(RECEIVED.poll(500, TimeUnit.MILLISECONDS));
    }
}
