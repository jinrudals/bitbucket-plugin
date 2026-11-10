package io.jenkins.plugins.bitbucketcloud.pipeline;

import io.jenkins.plugins.bitbucketcloud.api.CommitTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the workspace, repository and commit a status is published to.
 *
 * <p>Explicit arguments win. Fields left open are taken from the first source that provides all of
 * them, so values from different sources are never mixed. Sources, in order: the
 * {@code BitbucketEnvAction} variables, the {@code BITBUCKET_*} environment variables and the Git SCM
 * ({@code GIT_URL} and {@code GIT_COMMIT}).
 */
public final class CommitTargetResolver {
    private static final Pattern BITBUCKET_URL =
            Pattern.compile("^(?:[a-z+]+://(?:[^@/]+@)?|[^@/]+@)bitbucket\\.org[:/]([^/]+)/([^/]+?)(?:\\.git)?/?$");

    private static final String WORKSPACE = "workspace";
    private static final String REPO = "repo";
    private static final String COMMIT = "commit";

    private CommitTargetResolver() {}

    public record Resolved(CommitTarget target, String source) {}

    public static Resolved resolve(
            String workspace,
            String repo,
            String commit,
            Map<String, String> actionVariables,
            Map<String, String> environment) {
        Map<String, String> explicit = new LinkedHashMap<>();
        put(explicit, WORKSPACE, workspace);
        put(explicit, REPO, repo);
        put(explicit, COMMIT, commit);
        List<String> open = new ArrayList<>(List.of(WORKSPACE, REPO, COMMIT));
        open.removeAll(explicit.keySet());

        if (open.isEmpty()) {
            return new Resolved(target(explicit), "arguments");
        }

        Map<String, Map<String, String>> sources = new LinkedHashMap<>();
        sources.put("BitbucketEnvAction", fromBitbucketVariables(actionVariables));
        sources.put("environment", fromBitbucketVariables(environment));
        sources.put("Git SCM", fromGit(environment));

        for (Map.Entry<String, Map<String, String>> source : sources.entrySet()) {
            if (source.getValue().keySet().containsAll(open)) {
                Map<String, String> merged = new LinkedHashMap<>(source.getValue());
                merged.putAll(explicit);
                return new Resolved(target(merged), source.getKey());
            }
        }
        throw new IllegalArgumentException("Cannot resolve " + String.join(", ", open)
                + ": no source (BitbucketEnvAction, BITBUCKET_* environment, Git SCM) provides them all."
                + " Pass them as step arguments.");
    }

    private static CommitTarget target(Map<String, String> fields) {
        return new CommitTarget(fields.get(WORKSPACE), fields.get(REPO), fields.get(COMMIT));
    }

    private static Map<String, String> fromBitbucketVariables(Map<String, String> vars) {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, WORKSPACE, vars.get("BITBUCKET_WORKSPACE"));
        put(out, REPO, vars.get("BITBUCKET_REPOSITORY_NAME"));
        String commit = vars.get("BITBUCKET_COMMIT_AFTER");
        put(out, COMMIT, isBlank(commit) ? vars.get("BITBUCKET_PR_SOURCE_COMMIT") : commit);
        return out;
    }

    private static Map<String, String> fromGit(Map<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        String url = env.get("GIT_URL");
        if (url != null) {
            Matcher m = BITBUCKET_URL.matcher(url.trim());
            if (m.matches()) {
                put(out, WORKSPACE, m.group(1));
                put(out, REPO, m.group(2));
            }
        }
        put(out, COMMIT, env.get("GIT_COMMIT"));
        return out;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (!isBlank(value)) {
            map.put(key, value.trim());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
