package io.jenkins.plugins.bitbucketcloud.webhook;

import hudson.EnvVars;
import hudson.model.EnvironmentContributingAction;
import hudson.model.InvisibleAction;
import hudson.model.Run;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exposes webhook payload values to the build as {@code BITBUCKET_*} environment variables. */
public class BitbucketEnvAction extends InvisibleAction implements EnvironmentContributingAction {
    private final Map<String, String> variables;

    public BitbucketEnvAction(Map<String, String> variables) {
        this.variables = new LinkedHashMap<>(variables);
    }

    @Override
    public void buildEnvironment(Run<?, ?> run, EnvVars env) {
        env.putAll(variables);
    }
}
