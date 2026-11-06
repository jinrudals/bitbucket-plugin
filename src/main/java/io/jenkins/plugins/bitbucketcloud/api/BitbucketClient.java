package io.jenkins.plugins.bitbucketcloud.api;

import io.jenkins.plugins.bitbucketcloud.auth.BitbucketAuth;
import io.jenkins.plugins.bitbucketcloud.routing.RepoKey;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class BitbucketClient {
    private static final Logger LOGGER = Logger.getLogger(BitbucketClient.class.getName());
    private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]{4,64}$");
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9._~-]+$");
    private static final int MAX_RETRIES = 3;

    public interface Sleeper {
        Sleeper REAL = d -> Thread.sleep(d.toMillis());

        void sleep(Duration d) throws InterruptedException;
    }

    private final URI baseUrl;
    private final BitbucketAuth auth;
    private final Sleeper sleeper;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public BitbucketClient(URI baseUrl, BitbucketAuth auth) {
        this(baseUrl, auth, Sleeper.REAL);
    }

    public BitbucketClient(URI baseUrl, BitbucketAuth auth, Sleeper sleeper) {
        this.baseUrl = baseUrl;
        this.auth = auth;
        this.sleeper = sleeper;
    }

    /** Posts a build status; never throws. */
    public void postCommitStatus(RepoKey repo, String commitHash, CommitStatus status) {
        try {
            doPost(repo, commitHash, status);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to post Bitbucket commit status: {0}", e.getClass().getSimpleName());
        }
    }

    private void doPost(RepoKey repo, String commitHash, CommitStatus status) {
        if (commitHash == null || !HEX.matcher(commitHash).matches()
                || !SAFE_SEGMENT.matcher(repo.workspace()).matches()
                || !SAFE_SEGMENT.matcher(repo.slug()).matches()) {
            LOGGER.log(Level.WARNING, "Skipping commit status: invalid repository or commit hash");
            return;
        }
        String base = baseUrl.toString().replaceAll("/+$", "");
        URI uri = URI.create(base + "/2.0/repositories/" + repo.workspace() + "/" + repo.slug() + "/commit/"
                + commitHash + "/statuses/build");
        String body = toJson(status);
        int retries = 0;
        boolean refreshed = false;
        while (true) {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            auth.applyTo(b);
            int code;
            try {
                code = http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (IOException e) {
                if (!backoff(retries++, "I/O error " + e.getClass().getSimpleName())) {
                    return;
                }
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (code >= 200 && code < 300) {
                return;
            }
            if (code == 429 || code >= 500) {
                if (!backoff(retries++, "HTTP " + code)) {
                    return;
                }
            } else if (code == 401 && !refreshed) {
                refreshed = true;
                if (!auth.refresh()) {
                    LOGGER.log(Level.WARNING, "Bitbucket rejected credentials (HTTP 401)");
                    return;
                }
            } else {
                LOGGER.log(Level.WARNING, "Bitbucket commit status rejected: HTTP {0}", code);
                return;
            }
        }
    }

    private boolean backoff(int attempt, String reason) {
        if (attempt >= MAX_RETRIES) {
            LOGGER.log(Level.WARNING, "Giving up posting commit status after retries ({0})", reason);
            return false;
        }
        try {
            sleeper.sleep(Duration.ofSeconds(1L << attempt));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String toJson(CommitStatus s) {
        return "{\"key\":" + q(s.key()) + ",\"name\":" + q(s.name()) + ",\"state\":" + q(s.state())
                + ",\"url\":" + q(s.url()) + ",\"description\":" + q(s.description()) + "}";
    }

    private static String q(String v) {
        if (v == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
