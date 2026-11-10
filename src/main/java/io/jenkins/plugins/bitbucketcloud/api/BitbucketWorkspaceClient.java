package io.jenkins.plugins.bitbucketcloud.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketAuth;
import io.jenkins.plugins.bitbucketcloud.config.BitbucketWorkspaceConnection;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Pattern;

public final class BitbucketWorkspaceClient {
    private static final URI DEFAULT_API_BASE_URI = URI.create("https://api.bitbucket.org");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern COMMIT_HASH = Pattern.compile("^[0-9a-fA-F]{7,64}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI apiBaseUri;
    private final Duration requestTimeout;
    private final HttpClient http;

    public BitbucketWorkspaceClient() {
        this(DEFAULT_API_BASE_URI, DEFAULT_TIMEOUT);
    }

    public BitbucketWorkspaceClient(URI apiBaseUri, Duration requestTimeout) {
        this.apiBaseUri = Objects.requireNonNull(apiBaseUri);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        this.http = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
    }

    public WorkspaceTestResult testWorkspace(String workspace, BitbucketAuth auth) {
        if (!BitbucketWorkspaceConnection.isValidWorkspaceSlug(workspace)) {
            throw new IllegalArgumentException("Invalid Bitbucket Cloud workspace slug.");
        }
        Objects.requireNonNull(auth);

        URI uri = apiUri("/2.0/workspaces/" + workspace.trim());
        return send(HttpRequest.newBuilder(uri).timeout(requestTimeout).GET(), auth);
    }

    public CommitStatusResult postCommitStatus(CommitTarget target, CommitStatus status, BitbucketAuth auth) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(status);
        Objects.requireNonNull(auth);
        if (!BitbucketWorkspaceConnection.isValidWorkspaceSlug(target.workspace())
                || !BitbucketWorkspaceConnection.isValidWorkspaceSlug(target.repo())) {
            throw new IllegalArgumentException("Invalid Bitbucket Cloud workspace or repository slug.");
        }
        if (target.commit() == null || !COMMIT_HASH.matcher(target.commit()).matches()) {
            throw new IllegalArgumentException("Invalid commit hash.");
        }

        Map<String, String> body = new LinkedHashMap<>();
        body.put("state", status.state().name());
        body.put("key", status.key());
        putIfPresent(body, "name", status.name());
        putIfPresent(body, "description", status.description());
        putIfPresent(body, "url", status.url());
        String json;
        try {
            json = JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize commit status.", e);
        }

        URI uri = apiUri("/2.0/repositories/" + target.workspace().trim() + "/" + target.repo().trim()
                + "/commit/" + target.commit().trim() + "/statuses/build");
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        WorkspaceTestResult result = send(request, auth);
        return new CommitStatusResult(result.outcome(), result.httpStatus());
    }

    private static void putIfPresent(Map<String, String> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    private URI apiUri(String path) {
        return URI.create(apiBaseUri.toString().replaceAll("/+$", "") + path);
    }

    private WorkspaceTestResult send(HttpRequest.Builder request, BitbucketAuth auth) {
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

    public record CommitStatusResult(Outcome outcome, OptionalInt httpStatus) {
        public CommitStatusResult {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(httpStatus);
        }
    }
}
