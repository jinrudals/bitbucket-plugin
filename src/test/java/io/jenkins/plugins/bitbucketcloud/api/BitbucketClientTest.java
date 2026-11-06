package io.jenkins.plugins.bitbucketcloud.api;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketAuth;
import io.jenkins.plugins.bitbucketcloud.routing.RepoKey;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@WireMockTest
class BitbucketClientTest {
    private static final String PATH = "/2.0/repositories/ws/repo/commit/abc123/statuses/build";
    private static final RepoKey REPO = new RepoKey("ws", "repo");
    private static final CommitStatus STATUS = new CommitStatus("job-1", "job #1", "SUCCESSFUL", "http://j/1", "done");

    private final List<Duration> sleeps = new ArrayList<>();

    private static class TestAuth implements BitbucketAuth {
        String token = "t1";
        boolean refreshable;
        int refreshes;

        @Override
        public void applyTo(HttpRequest.Builder b) {
            b.setHeader("Authorization", "Bearer " + token);
        }

        @Override
        public boolean refresh() {
            refreshes++;
            if (refreshable) {
                token = "t2";
            }
            return refreshable;
        }
    }

    private BitbucketClient client(WireMockRuntimeInfo wm, BitbucketAuth auth) {
        return new BitbucketClient(URI.create(wm.getHttpBaseUrl()), auth, sleeps::add);
    }

    @Test
    void sendsExpectedRequest(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201)));
        client(wm, new TestAuth()).postCommitStatus(REPO, "abc123", STATUS);
        verify(
                1,
                postRequestedFor(urlEqualTo(PATH))
                        .withHeader("Authorization", equalTo("Bearer t1"))
                        .withHeader("Content-Type", containing("application/json"))
                        .withRequestBody(
                                equalToJson(
                                        "{\"key\":\"job-1\",\"name\":\"job #1\",\"state\":\"SUCCESSFUL\",\"url\":\"http://j/1\",\"description\":\"done\"}")));
    }

    @Test
    void retriesServerErrorsThenSucceeds(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(PATH))
                .inScenario("s")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("2"));
        stubFor(post(urlEqualTo(PATH))
                .inScenario("s")
                .whenScenarioStateIs("2")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("3"));
        stubFor(post(urlEqualTo(PATH))
                .inScenario("s")
                .whenScenarioStateIs("3")
                .willReturn(aResponse().withStatus(200)));
        client(wm, new TestAuth()).postCommitStatus(REPO, "abc123", STATUS);
        verify(3, postRequestedFor(urlEqualTo(PATH)));
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), sleeps);
    }

    @Test
    void persistent429GivesUpAfterFourRequests(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429)));
        client(wm, new TestAuth()).postCommitStatus(REPO, "abc123", STATUS);
        verify(4, postRequestedFor(urlEqualTo(PATH)));
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)), sleeps);
    }

    @Test
    void forbiddenIsNotRetried(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(403)));
        client(wm, new TestAuth()).postCommitStatus(REPO, "abc123", STATUS);
        verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void otherClientErrorIsNotRetried(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(404)));
        client(wm, new TestAuth()).postCommitStatus(REPO, "abc123", STATUS);
        verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void unauthorizedWithRefreshRetriesOnceWithNewToken(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer t1"))
                .willReturn(aResponse().withStatus(401)));
        stubFor(post(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer t2"))
                .willReturn(aResponse().withStatus(200)));
        TestAuth auth = new TestAuth();
        auth.refreshable = true;
        client(wm, auth).postCommitStatus(REPO, "abc123", STATUS);
        verify(2, postRequestedFor(urlEqualTo(PATH)));
        assertEquals(1, auth.refreshes);
    }

    @Test
    void unauthorizedWithoutRefreshIsNotRetried(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)));
        client(wm, new TestAuth()).postCommitStatus(REPO, "abc123", STATUS);
        verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void repeated401AfterRefreshStopsAfterTwoRequests(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)));
        TestAuth auth = new TestAuth();
        auth.refreshable = true;
        client(wm, auth).postCommitStatus(REPO, "abc123", STATUS);
        verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void connectionResetDoesNotThrowAndRetries(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertDoesNotThrow(() -> client(wm, new TestAuth()).postCommitStatus(REPO, "abc123", STATUS));
        verify(4, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void authRuntimeFailureDoesNotThrowOrRetry(WireMockRuntimeInfo wm) {
        BitbucketAuth bad = b -> {
            throw new IllegalStateException("token endpoint down");
        };
        assertDoesNotThrow(() -> client(wm, bad).postCommitStatus(REPO, "abc123", STATUS));
        verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void nonHexCommitIsSkipped(WireMockRuntimeInfo wm) {
        client(wm, new TestAuth()).postCommitStatus(REPO, "../evil", STATUS);
        verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void unsafePathSegmentsAreSkipped(WireMockRuntimeInfo wm) {
        BitbucketClient c = client(wm, new TestAuth());
        c.postCommitStatus(new RepoKey("..", ".."), "abc123", STATUS);
        c.postCommitStatus(new RepoKey(".", "repo"), "abc123", STATUS);
        c.postCommitStatus(new RepoKey("ws", ".."), "abc123", STATUS);
        c.postCommitStatus(new RepoKey("ws", "a/b"), "abc123", STATUS);
        c.postCommitStatus(new RepoKey("ws", "a%2fb"), "abc123", STATUS);
        verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void specialCharactersAreEscapedAndRoundTrip(WireMockRuntimeInfo wm) throws Exception {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201)));
        String tricky = "a\"b\\c\n\u0001\u00e9\ud55c";
        client(wm, new TestAuth())
                .postCommitStatus(REPO, "abc123", new CommitStatus("k", tricky, "SUCCESSFUL", "http://j/1", tricky));
        String body = findAll(postRequestedFor(urlEqualTo(PATH))).get(0).getBodyAsString();
        com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(tricky, n.get("name").asText());
        assertEquals(tricky, n.get("description").asText());
    }

    @Test
    void interruptStopsRetryAndRestoresFlag(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
        BitbucketClient c = new BitbucketClient(URI.create(wm.getHttpBaseUrl()), new TestAuth(), d -> {
            throw new InterruptedException();
        });
        try {
            c.postCommitStatus(REPO, "abc123", STATUS);
            assertTrue(Thread.currentThread().isInterrupted());
            verify(1, postRequestedFor(urlEqualTo(PATH)));
        } finally {
            Thread.interrupted();
        }
    }
}
