package io.jenkins.plugins.bitbucketcloud.api;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import hudson.util.Secret;
import io.jenkins.plugins.bitbucketcloud.auth.BasicAuth;
import io.jenkins.plugins.bitbucketcloud.auth.BearerAuth;
import io.jenkins.plugins.bitbucketcloud.auth.OAuthClientCredentialsAuth;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@WireMockTest
class BitbucketWorkspaceClientTest {

    private BitbucketWorkspaceClient client(WireMockRuntimeInfo wm) {
        return new BitbucketWorkspaceClient(URI.create(wm.getHttpBaseUrl()), Duration.ofSeconds(2));
    }

    @Test
    void successfulWorkspaceReadReturnsSuccess(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/2.0/workspaces/team-one")).willReturn(ok()));

        BitbucketWorkspaceClient.WorkspaceTestResult result =
                client(wm).testWorkspace("team-one", new BasicAuth("username", Secret.fromString("password")));

        assertEquals(BitbucketWorkspaceClient.Outcome.SUCCESS, result.outcome());
        assertEquals(OptionalInt.of(200), result.httpStatus());
        verify(getRequestedFor(urlEqualTo("/2.0/workspaces/team-one"))
                .withHeader("Authorization", matching("Basic [A-Za-z0-9+/=]+")));
    }

    @Test
    void appliesUsernamePasswordAndSecretTextAuthorization(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/2.0/workspaces/team-one")).willReturn(ok()));
        stubFor(get(urlEqualTo("/2.0/workspaces/team-two")).willReturn(ok()));

        BitbucketWorkspaceClient api = client(wm);
        assertEquals(
                BitbucketWorkspaceClient.Outcome.SUCCESS,
                api.testWorkspace("team-one", new BasicAuth("username", Secret.fromString("password")))
                        .outcome());
        assertEquals(
                BitbucketWorkspaceClient.Outcome.SUCCESS,
                api.testWorkspace("team-two", new BearerAuth(Secret.fromString("secret-text")))
                        .outcome());

        verify(getRequestedFor(urlEqualTo("/2.0/workspaces/team-one"))
                .withHeader("Authorization", matching("Basic [A-Za-z0-9+/=]+")));
        verify(getRequestedFor(urlEqualTo("/2.0/workspaces/team-two"))
                .withHeader("Authorization", matching("Bearer .+")));
    }

    @Test
    void appliesOAuthAuthorization(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/oauth-token"))
                .willReturn(okJson("{\"access_token\":\"synthetic-oauth-token\",\"expires_in\":3600}")));
        stubFor(get(urlEqualTo("/2.0/workspaces/team-one")).willReturn(ok()));
        OAuthClientCredentialsAuth auth = new OAuthClientCredentialsAuth(
                "client-id",
                Secret.fromString("client-secret"),
                URI.create(wm.getHttpBaseUrl() + "/oauth-token"),
                Clock.systemUTC());

        BitbucketWorkspaceClient.WorkspaceTestResult result = client(wm).testWorkspace("team-one", auth);

        assertEquals(BitbucketWorkspaceClient.Outcome.SUCCESS, result.outcome());
        verify(postRequestedFor(urlEqualTo("/oauth-token")));
        verify(getRequestedFor(urlEqualTo("/2.0/workspaces/team-one"))
                .withHeader("Authorization", matching("Bearer .+")));
    }

    @Test
    void mapsUnauthorizedForbiddenNotFoundAndOtherHttpErrors(WireMockRuntimeInfo wm) {
        var client = client(wm);
        var cases = new int[] {401, 403, 404, 503};
        var outcomes = new BitbucketWorkspaceClient.Outcome[] {
            BitbucketWorkspaceClient.Outcome.UNAUTHORIZED,
            BitbucketWorkspaceClient.Outcome.FORBIDDEN,
            BitbucketWorkspaceClient.Outcome.NOT_FOUND,
            BitbucketWorkspaceClient.Outcome.HTTP_ERROR
        };
        for (int i = 0; i < cases.length; i++) {
            WireMock.reset();
            stubFor(get(urlEqualTo("/2.0/workspaces/team"))
                    .willReturn(aResponse().withStatus(cases[i]).withBody("RESPONSE_BODY_MARKER")));

            BitbucketWorkspaceClient.WorkspaceTestResult result =
                    client.testWorkspace("team", new BearerAuth(Secret.fromString("test-value")));

            assertEquals(outcomes[i], result.outcome());
            assertEquals(OptionalInt.of(cases[i]), result.httpStatus());
            assertFalse(result.toString().contains("RESPONSE_BODY_MARKER"));
        }
    }

    @Test
    void timeoutAndNetworkFailureReturnSafeOutcomes(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/2.0/workspaces/slow"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(500)));
        var slowClient = new BitbucketWorkspaceClient(URI.create(wm.getHttpBaseUrl()), Duration.ofMillis(100));

        BitbucketWorkspaceClient.WorkspaceTestResult timeout =
                slowClient.testWorkspace("slow", new BearerAuth(Secret.fromString("test-value")));
        BitbucketWorkspaceClient.WorkspaceTestResult networkFailure =
                new BitbucketWorkspaceClient(URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1))
                        .testWorkspace("offline", new BearerAuth(Secret.fromString("test-value")));

        assertEquals(BitbucketWorkspaceClient.Outcome.TIMEOUT, timeout.outcome());
        assertEquals(OptionalInt.empty(), timeout.httpStatus());
        assertEquals(BitbucketWorkspaceClient.Outcome.NETWORK_ERROR, networkFailure.outcome());
        assertEquals(OptionalInt.empty(), networkFailure.httpStatus());
    }

    @Test
    void interruptedRequestRestoresInterruptStatus(WireMockRuntimeInfo wm) throws Exception {
        AtomicReference<BitbucketWorkspaceClient.WorkspaceTestResult> result = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread request = new Thread(() -> {
            Thread.currentThread().interrupt();
            result.set(client(wm).testWorkspace("team", new BearerAuth(Secret.fromString("test-value"))));
            interruptRestored.set(Thread.currentThread().isInterrupted());
        });

        request.start();
        request.join(5000);

        assertFalse(request.isAlive());
        assertNotNull(result.get());
        assertEquals(BitbucketWorkspaceClient.Outcome.INTERRUPTED, result.get().outcome());
        assertTrue(interruptRestored.get());
    }

    @Test
    void oauthTokenFailureReturnsAuthenticationError(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/oauth-token")).willReturn(
                aResponse().withStatus(500).withBody("OAUTH_RESPONSE_MARKER")));
        OAuthClientCredentialsAuth auth = new OAuthClientCredentialsAuth(
                "client-id",
                Secret.fromString("client-secret"),
                URI.create(wm.getHttpBaseUrl() + "/oauth-token"),
                Clock.systemUTC());

        BitbucketWorkspaceClient.WorkspaceTestResult result = client(wm).testWorkspace("team", auth);

        assertEquals(BitbucketWorkspaceClient.Outcome.AUTHENTICATION_ERROR, result.outcome());
        assertFalse(result.toString().contains("client-secret"));
        assertFalse(result.toString().contains("OAUTH_RESPONSE_MARKER"));
    }

    @Test
    void oauthTokenTimeoutReturnsTimeoutOutcome(WireMockRuntimeInfo wm) {
        BitbucketWorkspaceClient.WorkspaceTestResult result = client(wm).testWorkspace(
                "team",
                request -> {
                    throw new OAuthClientCredentialsAuth.TokenRequestException(
                            OAuthClientCredentialsAuth.TokenRequestException.Failure.TIMEOUT,
                            "OAuth token request timed out.");
                });

        assertEquals(BitbucketWorkspaceClient.Outcome.TIMEOUT, result.outcome());
    }
}
