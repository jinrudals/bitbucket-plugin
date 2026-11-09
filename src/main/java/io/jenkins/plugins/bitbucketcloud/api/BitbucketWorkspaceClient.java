package io.jenkins.plugins.bitbucketcloud.api;

import io.jenkins.plugins.bitbucketcloud.auth.BitbucketAuth;
import io.jenkins.plugins.bitbucketcloud.auth.OAuthClientCredentialsAuth;
import io.jenkins.plugins.bitbucketcloud.config.BitbucketWorkspaceConnection;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

public final class BitbucketWorkspaceClient {
    private static final URI DEFAULT_API_BASE_URI = URI.create("https://api.bitbucket.org");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final URI apiBaseUri;
    private final Duration requestTimeout;
    private final HttpClient http;

    public BitbucketWorkspaceClient() {
        this(DEFAULT_API_BASE_URI, DEFAULT_TIMEOUT);
    }

    BitbucketWorkspaceClient(URI apiBaseUri, Duration requestTimeout) {
        this.apiBaseUri = Objects.requireNonNull(apiBaseUri);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        this.http = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
    }

    public WorkspaceTestResult testWorkspace(String workspace, BitbucketAuth auth) {
        if (!BitbucketWorkspaceConnection.isValidWorkspaceSlug(workspace)) {
            throw new IllegalArgumentException("Invalid Bitbucket Cloud workspace slug.");
        }
        Objects.requireNonNull(auth);

        URI uri = URI.create(apiBaseUri.toString().replaceAll("/+$", "") + "/2.0/workspaces/" + workspace.trim());
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .GET();
        try {
            auth.applyTo(request);
            int status = http.send(request.build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode();
            if (status >= 200 && status < 300) {
                return result(Outcome.SUCCESS, status);
            }
            if (status == 401) {
                return result(Outcome.UNAUTHORIZED, status);
            }
            if (status == 403) {
                return result(Outcome.FORBIDDEN, status);
            }
            if (status == 404) {
                return result(Outcome.NOT_FOUND, status);
            }
            return result(Outcome.HTTP_ERROR, status);
        } catch (HttpTimeoutException e) {
            return result(Outcome.TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(Outcome.INTERRUPTED);
        } catch (IOException e) {
            return result(Outcome.NETWORK_ERROR);
        } catch (OAuthClientCredentialsAuth.TokenRequestException e) {
            return switch (e.failure()) {
                case TIMEOUT -> result(Outcome.TIMEOUT);
                case NETWORK -> result(Outcome.NETWORK_ERROR);
                case INTERRUPTED -> result(Outcome.INTERRUPTED);
            };
        } catch (IllegalStateException e) {
            return result(Outcome.AUTHENTICATION_ERROR);
        }
    }

    private static WorkspaceTestResult result(Outcome outcome, int status) {
        return new WorkspaceTestResult(outcome, OptionalInt.of(status));
    }

    private static WorkspaceTestResult result(Outcome outcome) {
        return new WorkspaceTestResult(outcome, OptionalInt.empty());
    }

    public enum Outcome {
        SUCCESS,
        UNAUTHORIZED,
        FORBIDDEN,
        NOT_FOUND,
        HTTP_ERROR,
        AUTHENTICATION_ERROR,
        TIMEOUT,
        NETWORK_ERROR,
        INTERRUPTED
    }

    public record WorkspaceTestResult(Outcome outcome, OptionalInt httpStatus) {
        public WorkspaceTestResult {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(httpStatus);
        }
    }
}
