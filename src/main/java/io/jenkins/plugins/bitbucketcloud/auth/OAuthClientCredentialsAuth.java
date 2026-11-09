package io.jenkins.plugins.bitbucketcloud.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.util.Secret;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

public final class OAuthClientCredentialsAuth implements BitbucketAuth {
    public static final URI DEFAULT_TOKEN_ENDPOINT = URI.create("https://bitbucket.org/site/oauth2/access_token");
    private static final Duration SKEW = Duration.ofSeconds(60);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String clientId;
    private final Secret clientSecret;
    private final URI tokenEndpoint;
    private final Clock clock;
    private final Duration requestTimeout;
    private final HttpClient http;

    private String token;
    private Instant validUntil = Instant.MIN;

    public OAuthClientCredentialsAuth(String clientId, Secret clientSecret) {
        this(clientId, clientSecret, DEFAULT_TOKEN_ENDPOINT, Clock.systemUTC());
    }

    public OAuthClientCredentialsAuth(String clientId, Secret clientSecret, URI tokenEndpoint, Clock clock) {
        this(clientId, clientSecret, tokenEndpoint, clock, REQUEST_TIMEOUT);
    }

    OAuthClientCredentialsAuth(
            String clientId, Secret clientSecret, URI tokenEndpoint, Clock clock, Duration requestTimeout) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tokenEndpoint = tokenEndpoint;
        this.clock = clock;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public synchronized void applyTo(HttpRequest.Builder b) {
        if (token == null || !clock.instant().isBefore(validUntil)) {
            fetch();
        }
        b.setHeader("Authorization", "Bearer " + token);
    }

    @Override
    public synchronized boolean refresh() {
        fetch();
        return true;
    }

    private void fetch() {
        String basic = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret.getPlainText()).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(tokenEndpoint)
                .timeout(requestTimeout)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("OAuth token request failed with HTTP " + res.statusCode());
            }
            JsonNode json;
            try {
                json = MAPPER.readTree(res.body());
            } catch (JsonProcessingException e) {
                // Jackson's message and cause can quote fragments of the response body
                throw new IllegalStateException("OAuth token response was not valid JSON");
            }
            String t = json.path("access_token").asText(null);
            if (t == null || t.isEmpty()) {
                throw new IllegalStateException("OAuth token response had no access_token");
            }
            long expiresIn = json.path("expires_in").asLong(3600);
            token = t;
            validUntil = clock.instant().plusSeconds(expiresIn).minus(SKEW);
        } catch (HttpTimeoutException e) {
            throw new TokenRequestException(TokenRequestException.Failure.TIMEOUT, "OAuth token request timed out.");
        } catch (IOException e) {
            throw new TokenRequestException(TokenRequestException.Failure.NETWORK, "OAuth token request failed.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenRequestException(
                    TokenRequestException.Failure.INTERRUPTED, "OAuth token request was interrupted.");
        }
    }

    public static final class TokenRequestException extends IllegalStateException {
        private final Failure failure;

        public TokenRequestException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }

        public enum Failure {
            TIMEOUT,
            NETWORK,
            INTERRUPTED
        }
    }
}
