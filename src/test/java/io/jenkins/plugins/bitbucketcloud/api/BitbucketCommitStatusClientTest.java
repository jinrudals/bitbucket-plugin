package io.jenkins.plugins.bitbucketcloud.api;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import hudson.util.Secret;
import io.jenkins.plugins.bitbucketcloud.auth.BasicAuth;
import java.net.URI;
import java.time.Duration;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

@WireMockTest
class BitbucketCommitStatusClientTest {

    private static final String PATH = "/2.0/repositories/acme/shop/commit/abc1234/statuses/build";
    private static final CommitTarget TARGET = new CommitTarget("acme", "shop", "abc1234");
    private static final BasicAuth AUTH = new BasicAuth("user", Secret.fromString("test-value"));

    private BitbucketWorkspaceClient client(WireMockRuntimeInfo wm) {
        return new BitbucketWorkspaceClient(URI.create(wm.getHttpBaseUrl()), Duration.ofSeconds(2));
    }

    private static CommitStatus status(CommitStatus.State state) {
        return new CommitStatus(state, "job-key", "job name", "built it", "https://jenkins/job/1/");
    }

    @Test
    void postsTheStatusAsJson(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201)));

        var result = client(wm).postCommitStatus(TARGET, status(CommitStatus.State.SUCCESSFUL), AUTH);

        assertEquals(BitbucketWorkspaceClient.Outcome.SUCCESS, result.outcome());
        assertEquals(OptionalInt.of(201), result.httpStatus());
        verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", matching("Basic [A-Za-z0-9+/=]+"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.state", equalTo("SUCCESSFUL")))
                .withRequestBody(matchingJsonPath("$.key", equalTo("job-key")))
                .withRequestBody(matchingJsonPath("$.name", equalTo("job name")))
                .withRequestBody(matchingJsonPath("$.description", equalTo("built it")))
                .withRequestBody(matchingJsonPath("$.url", equalTo("https://jenkins/job/1/"))));
    }

    @Test
    void escapesJsonSpecialCharacters(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201)));
        var tricky = new CommitStatus(CommitStatus.State.FAILED, "k", "say \"hi\"\n", null, "https://j/");

        client(wm).postCommitStatus(TARGET, tricky, AUTH);

        verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.name", equalTo("say \"hi\"\n"))));
    }

    @Test
    void omitsNullFields(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201)));

        client(wm).postCommitStatus(TARGET, new CommitStatus(CommitStatus.State.INPROGRESS, "k", null, null, null), AUTH);

        verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(notMatching(".*description.*"))
                .withRequestBody(notMatching(".*\"url\".*")));
    }

    @Test
    void mapsHttpFailures(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(403)));

        var result = client(wm).postCommitStatus(TARGET, status(CommitStatus.State.FAILED), AUTH);

        assertEquals(BitbucketWorkspaceClient.Outcome.FORBIDDEN, result.outcome());
        assertEquals(OptionalInt.of(403), result.httpStatus());
    }

    @Test
    void networkFailureReturnsNetworkError() {
        var offline = new BitbucketWorkspaceClient(URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1));

        var result = offline.postCommitStatus(TARGET, status(CommitStatus.State.FAILED), AUTH);

        assertEquals(BitbucketWorkspaceClient.Outcome.NETWORK_ERROR, result.outcome());
    }

    @Test
    void rejectsTargetsThatCouldChangeTheRequestPath(WireMockRuntimeInfo wm) {
        var client = client(wm);
        var s = status(CommitStatus.State.SUCCESSFUL);

        assertThrows(
                IllegalArgumentException.class,
                () -> client.postCommitStatus(new CommitTarget("acme", "../x", "abc1234"), s, AUTH));
        assertThrows(
                IllegalArgumentException.class,
                () -> client.postCommitStatus(new CommitTarget("a/b", "shop", "abc1234"), s, AUTH));
        assertThrows(
                IllegalArgumentException.class,
                () -> client.postCommitStatus(new CommitTarget("acme", "shop", "not-a-hash"), s, AUTH));
    }
}
