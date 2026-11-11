package io.jenkins.plugins.bitbucketcloud.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketAuth;
import io.jenkins.plugins.bitbucketcloud.config.BitbucketWorkspaceConnection;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

public final class BitbucketWorkspaceClient {
    private static final URI DEFAULT_API_BASE_URI = URI.create("https://api.bitbucket.org");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern COMMIT_HASH = Pattern.compile("^[0-9a-fA-F]{7,64}$");
    private static final Pattern NUMERIC_ID = Pattern.compile("^[0-9]{1,18}$");
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

    /**
     * Creates a comment thread (optionally as a reply to {@code parentId}) or a task on a pull request.
     * The id of the created item is returned when Bitbucket reports it.
     */
    public PullRequestItemResult postPullRequestItem(
            String workspace,
            String repo,
            String pullRequestId,
            PullRequestItemType type,
            String content,
            String parentId,
            BitbucketAuth auth) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(auth);
        if (!BitbucketWorkspaceConnection.isValidWorkspaceSlug(workspace)
                || !BitbucketWorkspaceConnection.isValidWorkspaceSlug(repo)) {
            throw new IllegalArgumentException("Invalid Bitbucket Cloud workspace or repository slug.");
        }
        if (pullRequestId == null || !NUMERIC_ID.matcher(pullRequestId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid pull request id.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("The content must not be empty.");
        }
        boolean hasParent = parentId != null && !parentId.isBlank();
        if (hasParent && type == PullRequestItemType.TASK) {
            throw new IllegalArgumentException("A task cannot be a reply; parentId applies to threads only.");
        }
        if (hasParent && !NUMERIC_ID.matcher(parentId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid parent comment id.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", Map.of("raw", content));
        if (hasParent) {
            body.put("parent", Map.of("id", Long.parseLong(parentId.trim())));
        }
        String json;
        try {
            json = JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize pull request item.", e);
        }

        URI uri = apiUri("/2.0/repositories/" + workspace.trim() + "/" + repo.trim() + "/pullrequests/"
                + pullRequestId.trim() + (type == PullRequestItemType.TASK ? "/tasks" : "/comments"));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        Response response = exchange(request, auth);
        return new PullRequestItemResult(response.outcome(), response.httpStatus(), parseId(response.body()));
    }

    /**
     * Reads a file at a branch name or commit hash. {@code NOT_FOUND} means the file or the ref does not exist.
     */
    public FileReadResult readFile(String workspace, String repo, String ref, String path, BitbucketAuth auth) {
        Objects.requireNonNull(auth);
        if (!BitbucketWorkspaceConnection.isValidWorkspaceSlug(workspace)
                || !BitbucketWorkspaceConnection.isValidWorkspaceSlug(repo)) {
            throw new IllegalArgumentException("Invalid Bitbucket Cloud workspace or repository slug.");
        }
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("The ref must not be empty.");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("The path must not be empty.");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.trim().split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("The path must not contain '..'.");
            }
            if (!segment.isEmpty() && !segment.equals(".")) {
                segments.add(encode(segment));
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("The path must not be empty.");
        }

        String resolvedRef = ref.trim();
        if (resolvedRef.contains("/")) {
            // /src/{ref}/{path} cannot address a branch whose name contains '/', so resolve it to a commit hash.
            Response branch = exchange(
                    HttpRequest.newBuilder(apiUri("/2.0/repositories/" + workspace.trim() + "/" + repo.trim()
                                    + "/refs/branches/" + encode(resolvedRef)))
                            .timeout(requestTimeout)
                            .GET(),
                    auth);
            if (branch.outcome() != Outcome.SUCCESS) {
                return new FileReadResult(branch.outcome(), branch.httpStatus(), Optional.empty());
            }
            String hash = parseBranchHash(branch.body());
            if (hash == null) {
                return new FileReadResult(Outcome.HTTP_ERROR, branch.httpStatus(), Optional.empty());
            }
            resolvedRef = hash;
        }

        URI uri = apiUri("/2.0/repositories/" + workspace.trim() + "/" + repo.trim() + "/src/" + encode(resolvedRef)
                + "/" + String.join("/", segments));
        Response response = exchange(HttpRequest.newBuilder(uri).timeout(requestTimeout).GET(), auth);
        return new FileReadResult(response.outcome(), response.httpStatus(), Optional.ofNullable(response.body()));
    }

    /** Reads the project a repository belongs to. {@code NOT_FOUND} means the repository does not exist. */
    public ProjectReadResult getProject(String workspace, String repo, BitbucketAuth auth) {
        Objects.requireNonNull(auth);
        if (!BitbucketWorkspaceConnection.isValidWorkspaceSlug(workspace)
                || !BitbucketWorkspaceConnection.isValidWorkspaceSlug(repo)) {
            throw new IllegalArgumentException("Invalid Bitbucket Cloud workspace or repository slug.");
        }
        Response response = exchange(
                HttpRequest.newBuilder(apiUri("/2.0/repositories/" + workspace.trim() + "/" + repo.trim()))
                        .timeout(requestTimeout)
                        .GET(),
                auth);
        if (response.outcome() != Outcome.SUCCESS) {
            return new ProjectReadResult(response.outcome(), response.httpStatus(), Optional.empty());
        }
        Optional<Map<String, Object>> project = parseProject(response.body());
        // A repository without a project, or an unreadable body, is reported as an error rather than an empty project.
        return new ProjectReadResult(
                project.isPresent() ? Outcome.SUCCESS : Outcome.HTTP_ERROR, response.httpStatus(), project);
    }

    private static Optional<Map<String, Object>> parseProject(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode project = JSON.readTree(body).path("project");
            if (!project.isObject() || project.path("key").asText("").isBlank()) {
                return Optional.empty();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", project.path("key").asText());
            result.put("name", project.path("name").asText(""));
            result.put("uuid", project.path("uuid").asText(""));
            result.put("description", project.path("description").asText(""));
            result.put("isPrivate", project.path("is_private").asBoolean(false));
            return Optional.of(result);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String parseBranchHash(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            String hash = JSON.readTree(body).path("target").path("hash").asText("");
            return hash.isBlank() ? null : hash;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Optional<String> parseId(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode id = JSON.readTree(body).path("id");
            return id.isMissingNode() || id.isNull() ? Optional.empty() : Optional.of(id.asText());
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
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
        Response response = exchange(request, auth);
        return new WorkspaceTestResult(response.outcome(), response.httpStatus());
    }

    private Response exchange(HttpRequest.Builder request, BitbucketAuth auth) {
        try {
            auth.applyTo(request);
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return response(Outcome.SUCCESS, status, response.body());
            }
            if (status == 401) {
                return response(Outcome.UNAUTHORIZED, status, null);
            }
            if (status == 403) {
                return response(Outcome.FORBIDDEN, status, null);
            }
            if (status == 404) {
                return response(Outcome.NOT_FOUND, status, null);
            }
            return response(Outcome.HTTP_ERROR, status, null);
        } catch (HttpTimeoutException e) {
            return response(Outcome.TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return response(Outcome.INTERRUPTED);
        } catch (IOException e) {
            return response(Outcome.NETWORK_ERROR);
        } catch (IllegalStateException e) {
            return response(Outcome.AUTHENTICATION_ERROR);
        }
    }

    private static Response response(Outcome outcome, int status, String body) {
        return new Response(outcome, OptionalInt.of(status), body);
    }

    private static Response response(Outcome outcome) {
        return new Response(outcome, OptionalInt.empty(), null);
    }

    private record Response(Outcome outcome, OptionalInt httpStatus, String body) {}

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

    public record FileReadResult(Outcome outcome, OptionalInt httpStatus, Optional<String> content) {
        public FileReadResult {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(httpStatus);
            Objects.requireNonNull(content);
        }
    }

    public record ProjectReadResult(Outcome outcome, OptionalInt httpStatus, Optional<Map<String, Object>> project) {
        public ProjectReadResult {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(httpStatus);
            Objects.requireNonNull(project);
        }
    }

    public record PullRequestItemResult(Outcome outcome, OptionalInt httpStatus, Optional<String> id) {
        public PullRequestItemResult {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(httpStatus);
            Objects.requireNonNull(id);
        }
    }
}
