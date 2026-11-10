package io.jenkins.plugins.bitbucketcloud.webhook;

import hudson.EnvVars;
import hudson.Util;
import hudson.model.Action;
import hudson.model.EnvironmentContributingAction;
import hudson.model.InvisibleAction;
import hudson.model.Run;
import hudson.model.Queue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exposes webhook payload values to the build as {@code BITBUCKET_*} environment variables. */
public class BitbucketEnvAction extends InvisibleAction implements EnvironmentContributingAction, Queue.QueueAction {
    private final Map<String, String> variables;

    public BitbucketEnvAction(Map<String, String> variables) {
        this.variables = new LinkedHashMap<>(variables);
    }

    @Override
    public void buildEnvironment(Run<?, ?> run, EnvVars env) {
        env.putAll(variables);
    }

    // Jenkins folds a queued duplicate into the existing item and drops its actions (only causes are
    // merged). Deliveries with different values must therefore stay separate builds, e.g. the
    // repo:push and pullrequest:updated events Bitbucket sends for a single pull request update.
    @Override
    public boolean shouldSchedule(List<Action> actions) {
        for (BitbucketEnvAction other : Util.filter(actions, BitbucketEnvAction.class)) {
            if (variables.equals(other.variables)) {
                return false;
            }
        }
        return true;
    }
}
