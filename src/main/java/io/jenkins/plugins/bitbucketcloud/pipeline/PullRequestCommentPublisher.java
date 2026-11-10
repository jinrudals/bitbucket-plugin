package io.jenkins.plugins.bitbucketcloud.pipeline;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.bitbucketcloud.api.BitbucketWorkspaceClient;
import io.jenkins.plugins.bitbucketcloud.api.PullRequestItemType;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketAuth;
import java.util.Arrays;
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
        Map<String, String> action = CommitStatusPublisher.actionVariables(run);
        String workspace = pick(options.workspace(), action.get("BITBUCKET_WORKSPACE"), env.get("BITBUCKET_WORKSPACE"));
        String repo = pick(
                options.repo(), action.get("BITBUCKET_REPOSITORY_NAME"), env.get("BITBUCKET_REPOSITORY_NAME"));
        String pullRequestId = pick(options.pullRequestId(), action.get("BITBUCKET_PR_ID"), env.get("BITBUCKET_PR_ID"));
        if (workspace == null || repo == null) {
            throw new AbortException("Cannot resolve the workspace and repository: pass them as step arguments"
                    + " or run from a Bitbucket webhook build.");
        }
        if (pullRequestId == null) {
            throw new AbortException(
                    "Cannot resolve the pull request id: pass pullRequestId or run from a pull request webhook build.");
        }

        BitbucketAuth auth = CommitStatusPublisher.authFor(workspace);
        BitbucketWorkspaceClient.PullRequestItemResult result;
        try {
            result = CommitStatusPublisher.clientFactory
                    .get()
                    .postPullRequestItem(
                            workspace, repo, pullRequestId, type, options.content(), options.parentId(), auth);
        } catch (IllegalArgumentException e) {
            throw new AbortException(e.getMessage());
        }

        String where = workspace + "/" + repo + " pull request #" + pullRequestId;
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

    private static String pick(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }
}
