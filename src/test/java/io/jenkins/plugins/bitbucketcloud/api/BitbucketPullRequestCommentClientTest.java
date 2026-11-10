package io.jenkins.plugins.bitbucketcloud.api;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import hudson.util.Secret;
import io.jenkins.plugins.bitbucketcloud.auth.BasicAuth;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

@WireMockTest
class BitbucketPullRequestCommentClientTest {

    private static final String COMMENTS = "/2.0/repositories/acme/shop/pullrequests/12/comments";
    private static final String TASKS = "/2.0/repositories/acme/shop/pullrequests/12/tasks";
    private static final BasicAuth AUTH = new BasicAuth("user", Secret.fromString("test-value"));

    private BitbucketWorkspaceClient client(WireMockRuntimeInfo wm) {
        return new BitbucketWorkspaceClient(URI.create(wm.getHttpBaseUrl()), Duration.ofSeconds(2));
    }

    @Test
    void postsAThreadAndReturnsItsId(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(COMMENTS))
                .willReturn(aResponse().withStatus(201).withBody("{\"id\": 345, \"type\": \"pullrequest_comment\"}")));

        var result = client(wm)
                .postPullRequestItem(
                        "acme", "shop", "12", PullRequestItemType.THREAD, "say \"hi\"", null, AUTH);

        assertEquals(BitbucketWorkspaceClient.Outcome.SUCCESS, result.outcome());
        assertEquals(OptionalInt.of(201), result.httpStatus());
        assertEquals(Optional.of("345"), result.id());
        verify(postRequestedFor(urlEqualTo(COMMENTS))
                .withHeader("Authorization", matching("Basic [A-Za-z0-9+/=]+"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.content.raw", equalTo("say \"hi\"")))
                .withRequestBody(notMatching(".*parent.*")));
    }

    @Test
    void postsAReplyWithTheParentId(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(COMMENTS)).willReturn(aResponse().withStatus(201).withBody("{\"id\": 9}")));

        client(wm).postPullRequestItem("acme", "shop", "12", PullRequestItemType.THREAD, "re", "7", AUTH);

        verify(postRequestedFor(urlEqualTo(COMMENTS))
                .withRequestBody(matchingJsonPath("$.parent.id", equalTo("7"))));
    }

    @Test
    void postsATaskToTheTasksEndpoint(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(TASKS)).willReturn(aResponse().withStatus(201).withBody("{\"id\": 21}")));

        var result = client(wm).postPullRequestItem("acme", "shop", "12", PullRequestItemType.TASK, "fix it", null, AUTH);

        assertEquals(Optional.of("21"), result.id());
        verify(postRequestedFor(urlEqualTo(TASKS))
                .withRequestBody(matchingJsonPath("$.content.raw", equalTo("fix it"))));
    }

    @Test
    void mapsHttpFailuresWithoutAnId(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo(COMMENTS)).willReturn(aResponse().withStatus(403)));

        var result = client(wm).postPullRequestItem("acme", "shop", "12", PullRequestItemType.THREAD, "x", null, AUTH);

        assertEquals(BitbucketWorkspaceClient.Outcome.FORBIDDEN, result.outcome());
        assertEquals(Optional.empty(), result.id());
    }

    @Test
    void rejectsInvalidArguments(WireMockRuntimeInfo wm) {
        var c = client(wm);
        assertThrows(
                IllegalArgumentException.class,
                () -> c.postPullRequestItem("../x", "shop", "12", PullRequestItemType.THREAD, "x", null, AUTH));
        assertThrows(
                IllegalArgumentException.class,
                () -> c.postPullRequestItem("acme", "shop", "1/2", PullRequestItemType.THREAD, "x", null, AUTH));
        assertThrows(
                IllegalArgumentException.class,
                () -> c.postPullRequestItem("acme", "shop", "12", PullRequestItemType.THREAD, "x", "abc", AUTH));
        assertThrows(
                IllegalArgumentException.class,
                () -> c.postPullRequestItem("acme", "shop", "12", PullRequestItemType.THREAD, " ", null, AUTH));
        assertThrows(
                IllegalArgumentException.class,
                () -> c.postPullRequestItem("acme", "shop", "12", PullRequestItemType.TASK, "x", "7", AUTH));
    }
}
