package io.jenkins.plugins.bitbucketcloud.pipeline;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.bitbucketcloud.api.BitbucketWorkspaceClient;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketAuth;
import java.util.Map;

/** Reads a file from a Bitbucket Cloud repository on behalf of the pipeline step. */
final class FileReadPublisher {
    private FileReadPublisher() {}

    /**
     * Configuration problems abort the step; a missing file or a failing API call logs and returns {@code null}.
     *
     * @return the file content, or {@code null} if it could not be read
     */
    static String read(
            Run<?, ?> run, TaskListener listener, EnvVars env, String workspace, String repo, String ref, String path)
            throws AbortException {
        Map<String, String> action = CommitStatusPublisher.actionVariables(run);
        String ws = firstNonBlank(workspace, action.get("BITBUCKET_WORKSPACE"), env.get("BITBUCKET_WORKSPACE"));
        String rp = firstNonBlank(repo, action.get("BITBUCKET_REPOSITORY_NAME"), env.get("BITBUCKET_REPOSITORY_NAME"));
        if (ws == null || rp == null) {
            throw new AbortException("Cannot resolve workspace and repo: pass them as step arguments or run from a"
                    + " Bitbucket webhook build.");
        }

        BitbucketAuth auth = CommitStatusPublisher.authFor(ws);
        BitbucketWorkspaceClient.FileReadResult result;
        try {
            result = CommitStatusPublisher.clientFactory.get().readFile(ws, rp, ref, path, auth);
        } catch (IllegalArgumentException e) {
            throw new AbortException(e.getMessage());
        }

        String where = path + " at " + ref + " in " + ws + "/" + rp;
        if (result.outcome() == BitbucketWorkspaceClient.Outcome.SUCCESS) {
            return result.content().orElse("");
        }
        String reason = result.outcome() == BitbucketWorkspaceClient.Outcome.NOT_FOUND
                ? "not found"
                : "could not be read: " + result.outcome()
                        + result.httpStatus().stream().mapToObj(s -> " (HTTP " + s + ")").findFirst().orElse("");
        listener.getLogger().println("[bitbucket] WARNING: " + where + " " + reason);
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
