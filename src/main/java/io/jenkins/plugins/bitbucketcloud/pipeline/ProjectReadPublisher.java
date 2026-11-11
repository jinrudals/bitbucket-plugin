package io.jenkins.plugins.bitbucketcloud.pipeline;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.bitbucketcloud.api.BitbucketWorkspaceClient;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketAuth;
import java.util.Map;

/** Reads the project of a Bitbucket Cloud repository on behalf of the pipeline step. */
final class ProjectReadPublisher {
    private ProjectReadPublisher() {}

    /**
     * Configuration problems abort the step; a failing API call logs and returns {@code null}.
     *
     * @return the project fields, or {@code null} if the project could not be read
     */
    static Map<String, Object> read(Run<?, ?> run, TaskListener listener, EnvVars env, String workspace, String repo)
            throws AbortException {
        Map<String, String> action = CommitStatusPublisher.actionVariables(run);
        String ws = firstNonBlank(workspace, action.get("BITBUCKET_WORKSPACE"), env.get("BITBUCKET_WORKSPACE"));
        String rp = firstNonBlank(repo, action.get("BITBUCKET_REPOSITORY_NAME"), env.get("BITBUCKET_REPOSITORY_NAME"));
        if (ws == null || rp == null) {
            throw new AbortException("Cannot resolve workspace and repo: pass them as step arguments or run from a"
                    + " Bitbucket webhook build.");
        }

        BitbucketAuth auth = CommitStatusPublisher.authFor(ws);
        BitbucketWorkspaceClient.ProjectReadResult result;
        try {
            result = CommitStatusPublisher.clientFactory.get().getProject(ws, rp, auth);
        } catch (IllegalArgumentException e) {
            throw new AbortException(e.getMessage());
        }

        if (result.outcome() == BitbucketWorkspaceClient.Outcome.SUCCESS
                && result.project().isPresent()) {
            return result.project().get();
        }
        String reason = result.outcome() == BitbucketWorkspaceClient.Outcome.NOT_FOUND
                ? "repository not found"
                : "could not be read: " + result.outcome()
                        + result.httpStatus().stream()
                                .mapToObj(s -> " (HTTP " + s + ")")
                                .findFirst()
                                .orElse("");
        listener.getLogger().println("[bitbucket] WARNING: project of " + ws + "/" + rp + " " + reason);
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
