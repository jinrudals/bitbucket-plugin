package io.jenkins.plugins.bitbucketcloud.pipeline;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.bitbucketcloud.api.BitbucketWorkspaceClient;
import io.jenkins.plugins.bitbucketcloud.api.PullRequestItemType;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketAuth;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Creates comment threads and tasks on a pull request on behalf of the pipeline step. */
final class PullRequestCommentPublisher {
    private PullRequestCommentPublisher() {}

    static PullRequestItemType parseType(String type) throws AbortException {
        if (type == null || type.isBlank()) {
            return PullRequestItemType.THREAD;
        }
        try {
            return PullRequestItemType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AbortException("Unknown type '" + type + "'. Use one of: "
                    + String.join(", ", Arrays.stream(PullRequestItemType.values())
                            .map(Enum::name)
                            .toList()));
        }
    }

    /**
     * Resolves the target, authenticates and creates the item. Configuration problems abort the step; a
     * failing Bitbucket API call only logs a warning and returns {@code null} so it cannot fail the build.
     *
     * @return the id of the created thread or task, or {@code null} if it could not be created
     */
    static String publish(
            Run<?, ?> run,
            TaskListener listener,
            EnvVars env,
            PullRequestCommentOptions options,
            PullRequestItemType type)
            throws AbortException {
        Resolved target = resolve(options, CommitStatusPublisher.actionVariables(run), env);

        BitbucketAuth auth = CommitStatusPublisher.authFor(target.workspace());
        BitbucketWorkspaceClient.PullRequestItemResult result;
        try {
            result = CommitStatusPublisher.clientFactory
                    .get()
                    .postPullRequestItem(
                            target.workspace(), target.repo(), target.pullRequestId(), type, options.content(), options.parentId(), auth);
        } catch (IllegalArgumentException e) {
            throw new AbortException(e.getMessage());
        }

        String where = target.workspace() + "/" + target.repo() + " pull request #" + target.pullRequestId()
                + " (target from " + target.source() + ")";
        if (result.outcome() == BitbucketWorkspaceClient.Outcome.SUCCESS) {
            listener.getLogger()
                    .println("[bitbucket] " + type + " created on " + where
                            + result.id().map(id -> " (id " + id + ")").orElse(""));
            return result.id().orElse(null);
        }
        listener.getLogger()
                .println("[bitbucket] WARNING: could not create " + type + " on " + where + ": " + result.outcome()
                        + result.httpStatus().stream().mapToObj(s -> " (HTTP " + s + ")").findFirst().orElse(""));
        return null;
    }

    private record Resolved(String workspace, String repo, String pullRequestId, String source) {}

    // Explicit arguments win; the open fields come from the first source that provides all of them,
    // so values from different sources are never mixed.
    private static Resolved resolve(PullRequestCommentOptions options, Map<String, String> action, Map<String, String> env)
            throws AbortException {
        Map<String, String> explicit = new LinkedHashMap<>();
        put(explicit, "workspace", options.workspace());
        put(explicit, "repo", options.repo());
        put(explicit, "pullRequestId", options.pullRequestId());
        if (explicit.size() == 3) {
            return new Resolved(explicit.get("workspace"), explicit.get("repo"), explicit.get("pullRequestId"), "arguments");
        }

        Map<String, Map<String, String>> sources = new LinkedHashMap<>();
        sources.put("BitbucketEnvAction", fromVariables(action));
        sources.put("environment", fromVariables(env));
        for (Map.Entry<String, Map<String, String>> source : sources.entrySet()) {
            Map<String, String> merged = new LinkedHashMap<>(source.getValue());
            merged.putAll(explicit);
            if (merged.size() == 3 && source.getValue().keySet().containsAll(open(explicit))) {
                return new Resolved(
                        merged.get("workspace"), merged.get("repo"), merged.get("pullRequestId"), source.getKey());
            }
        }
        throw new AbortException("Cannot resolve " + String.join(", ", open(explicit))
                + ": no source (BitbucketEnvAction, BITBUCKET_* environment) provides them all."
                + " Pass them as step arguments or run from a pull request webhook build.");
    }

    private static java.util.List<String> open(Map<String, String> explicit) {
        return java.util.stream.Stream.of("workspace", "repo", "pullRequestId")
                .filter(f -> !explicit.containsKey(f))
                .toList();
    }

    private static Map<String, String> fromVariables(Map<String, String> vars) {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "workspace", vars.get("BITBUCKET_WORKSPACE"));
        put(out, "repo", vars.get("BITBUCKET_REPOSITORY_NAME"));
        put(out, "pullRequestId", vars.get("BITBUCKET_PR_ID"));
        return out;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }
}
