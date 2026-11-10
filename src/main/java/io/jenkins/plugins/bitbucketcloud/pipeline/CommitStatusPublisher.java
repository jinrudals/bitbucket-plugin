package io.jenkins.plugins.bitbucketcloud.pipeline;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.bitbucketcloud.api.BitbucketWorkspaceClient;
import io.jenkins.plugins.bitbucketcloud.api.CommitStatus;
import io.jenkins.plugins.bitbucketcloud.api.CommitTarget;
import io.jenkins.plugins.bitbucketcloud.auth.AuthResolver;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketAuth;
import io.jenkins.plugins.bitbucketcloud.config.BitbucketGlobalConfig;
import io.jenkins.plugins.bitbucketcloud.config.BitbucketWorkspaceConnection;
import io.jenkins.plugins.bitbucketcloud.webhook.BitbucketEnvAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** Publishes a build status to Bitbucket Cloud on behalf of the pipeline steps. */
final class CommitStatusPublisher {
    private static final int MAX_KEY_LENGTH = 40;

    // Replaced by tests to point the client at a local server.
    static volatile Supplier<BitbucketWorkspaceClient> clientFactory = BitbucketWorkspaceClient::new;

    private CommitStatusPublisher() {}

    static CommitStatus.State parseState(String state) throws AbortException {
        try {
            return CommitStatus.State.valueOf(state == null ? "" : state.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AbortException("Unknown state '" + state + "'. Use one of: "
                    + String.join(", ", Arrays.stream(CommitStatus.State.values())
                            .map(Enum::name)
                            .toList()));
        }
    }

    /**
     * Resolves the target, authenticates and posts the status. Configuration problems abort the step;
     * a failing Bitbucket API call only logs a warning so that it cannot fail the build.
     */
    static void publish(Run<?, ?> run, TaskListener listener, EnvVars env, CommitStatusOptions options, CommitStatus.State state)
            throws AbortException {
        CommitTargetResolver.Resolved resolved;
        try {
            resolved = CommitTargetResolver.resolve(
                    options.workspace(), options.repo(), options.commit(), actionVariables(run), env);
        } catch (IllegalArgumentException e) {
            throw new AbortException(e.getMessage());
        }
        CommitTarget target = resolved.target();

        BitbucketAuth auth = authFor(target.workspace());
        CommitStatus status = new CommitStatus(
                state,
                firstNonBlank(options.key(), defaultKey(env.get("JOB_NAME"))),
                firstNonBlank(options.name(), env.get("JOB_NAME")),
                options.description(),
                firstNonBlank(options.url(), env.get("BUILD_URL")));

        BitbucketWorkspaceClient.CommitStatusResult result;
        try {
            result = clientFactory.get().postCommitStatus(target, status, auth);
        } catch (IllegalArgumentException e) {
            throw new AbortException(e.getMessage());
        }
        if (result.outcome() == BitbucketWorkspaceClient.Outcome.SUCCESS) {
            listener.getLogger()
                    .println("[bitbucket] " + state + " published for " + target.workspace() + "/" + target.repo()
                            + "@" + target.commit() + " (target from " + resolved.source() + ")");
        } else {
            listener.getLogger()
                    .println("[bitbucket] WARNING: could not publish " + state + " for " + target.workspace() + "/"
                            + target.repo() + "@" + target.commit() + ": " + result.outcome()
                            + result.httpStatus().stream().mapToObj(s -> " (HTTP " + s + ")").findFirst().orElse(""));
        }
    }

    static Map<String, String> actionVariables(Run<?, ?> run) {
        BitbucketEnvAction action = run.getAction(BitbucketEnvAction.class);
        EnvVars vars = new EnvVars();
        if (action != null) {
            action.buildEnvironment(run, vars);
        }
        return vars;
    }

    static BitbucketAuth authFor(String workspace) throws AbortException {
        BitbucketWorkspaceConnection connection = BitbucketGlobalConfig.get().getWorkspaceConnections().stream()
                .filter(c -> c.getWorkspace().equalsIgnoreCase(workspace))
                .findFirst()
                .orElseThrow(() -> new AbortException("Workspace '" + workspace
                        + "' is not configured under Manage Jenkins > System > Bitbucket Cloud."));
        return AuthResolver.resolveWorkspaceCredential(connection.getCredentialsId())
                .orElseThrow(() -> new AbortException("The credential for workspace '" + workspace
                        + "' was not found or is not a Bitbucket Cloud API token credential."));
    }

    // Bitbucket limits the key to 40 characters; keep long job names unique by appending a hash.
    static String defaultKey(String jobName) {
        if (jobName == null || jobName.length() <= MAX_KEY_LENGTH) {
            return jobName;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(jobName.getBytes(StandardCharsets.UTF_8));
            return jobName.substring(0, MAX_KEY_LENGTH - 9) + "-" + HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
