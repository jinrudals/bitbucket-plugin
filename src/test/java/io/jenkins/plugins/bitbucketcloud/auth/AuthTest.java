package io.jenkins.plugins.bitbucketcloud.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import hudson.util.Secret;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@WireMockTest
class AuthTest {

    private static String header(BitbucketAuth a) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost/x"));
        a.applyTo(b);
        return b.build().headers().firstValue("Authorization").orElse(null);
    }

    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now.get(); }
    }

    @Test
    void basicAndBearer() {
        assertEquals("Basic dTpw", header(new BasicAuth("u", Secret.fromString("p"))));
        assertEquals("Bearer tok", header(new BearerAuth(Secret.fromString("tok"))));
    }

    @Test
    void oauthCachesExpiresAndRefreshes(WireMockRuntimeInfo wm) {
        stubFor(post("/token").willReturn(okJson("{\"access_token\":\"T1\",\"expires_in\":3600}")));
        MutableClock clock = new MutableClock();
        OAuthClientCredentialsAuth a = new OAuthClientCredentialsAuth(
                "id", Secret.fromString("sec"), URI.create(wm.getHttpBaseUrl() + "/token"), clock);
        assertEquals("Bearer T1", header(a));
        assertEquals("Bearer T1", header(a));
        verify(1, postRequestedFor(urlEqualTo("/token"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withHeader("Authorization", equalTo("Basic aWQ6c2Vj")));
        clock.now.set(clock.now.get().plus(Duration.ofSeconds(3541)));
        header(a);
        verify(2, postRequestedFor(urlEqualTo("/token")));
        assertTrue(a.refresh());
        verify(3, postRequestedFor(urlEqualTo("/token")));
    }

    private void assertFailsAndNotCached(WireMockRuntimeInfo wm, String body, int status) {
        stubFor(post("/token").willReturn(aResponse().withStatus(status).withBody(body)));
        OAuthClientCredentialsAuth a = new OAuthClientCredentialsAuth(
                "id", Secret.fromString("topsecret"), URI.create(wm.getHttpBaseUrl() + "/token"), new MutableClock());
        for (int i = 1; i <= 2; i++) {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> header(a));
            for (Throwable t = ex; t != null; t = t.getCause()) {
                String m = String.valueOf(t.getMessage());
                assertFalse(m.contains("topsecret"), m);
                assertFalse(m.contains("LEAKMARKER"), m);
            }
            verify(i, postRequestedFor(urlEqualTo("/token")));
        }
    }

    @Test
    void oauthServerErrorFailsAndIsNotCached(WireMockRuntimeInfo wm) {
        assertFailsAndNotCached(wm, "LEAKMARKER", 500);
    }

    @Test
    void oauthMalformedJsonFailsWithoutLeakingBody(WireMockRuntimeInfo wm) {
        assertFailsAndNotCached(wm, "LEAKMARKER{bad", 200);
    }

    @Test
    void oauthMissingAccessTokenFails(WireMockRuntimeInfo wm) {
        assertFailsAndNotCached(wm, "{}", 200);
    }

    @Test
    void resolverMapsTypes(JenkinsRule j) throws Exception {
        var store = SystemCredentialsProvider.getInstance().getCredentials();
        store.add(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "up", "d", "u", "p"));
        store.add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "str", "d", Secret.fromString("t")));
        store.add(new BitbucketOAuthCredentials(CredentialsScope.GLOBAL, "oa", "d", "cid", Secret.fromString("cs")));
        assertInstanceOf(BasicAuth.class, AuthResolver.resolve("up").orElseThrow());
        assertInstanceOf(BearerAuth.class, AuthResolver.resolve("str").orElseThrow());
        assertInstanceOf(OAuthClientCredentialsAuth.class, AuthResolver.resolve("oa").orElseThrow());
        assertEquals(Optional.empty(), AuthResolver.resolve("nope"));
        assertEquals(Optional.empty(), AuthResolver.resolve(null));
    }
}
