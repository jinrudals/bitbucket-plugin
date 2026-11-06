package io.jenkins.plugins.bitbucketcloud.status;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.plugins.git.util.BuildData;
import hudson.triggers.Trigger;
import io.jenkins.plugins.bitbucketcloud.api.BitbucketClient;
import io.jenkins.plugins.bitbucketcloud.api.CommitStatus;
import io.jenkins.plugins.bitbucketcloud.auth.AuthResolver;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketAuth;
import io.jenkins.plugins.bitbucketcloud.config.BitbucketGlobalConfig;
import io.jenkins.plugins.bitbucketcloud.routing.RepoKey;
import io.jenkins.plugins.bitbucketcloud.trigger.BitbucketBuildAction;
import io.jenkins.plugins.bitbucketcloud.trigger.BitbucketTrigger;
import java.net.URI;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.ParameterizedJobMixIn;

@Extension
public class StatusReporter extends RunListener<Run<?, ?>> {
    private static final Logger LOGGER = Logger.getLogger(StatusReporter.class.getName());
    private static final int MAX_KEY = 40;

    private record Target(RepoKey repo, String commit) {}

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        report(run, "INPROGRESS", "Build started");
    }

    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        Result r = run.getResult();
        String state;
        if (r == null || r == Result.SUCCESS || r == Result.UNSTABLE) {
            state = "SUCCESSFUL";
        } else if (r == Result.ABORTED) {
            state = "STOPPED";
        } else {
            state = "FAILED";
        }
        report(run, state, "Build " + (r == null ? "SUCCESS" : r.toString()));
    }

    private void report(Run<?, ?> run, String state, String description) {
        try {
            Optional<BitbucketAuth> auth = resolveAuth(run);
            if (auth.isEmpty()) {
                return;
            }
            Optional<Target> target = target(run);
            if (target.isEmpty()) {
                return;
            }
            BitbucketGlobalConfig cfg = BitbucketGlobalConfig.get();
            String key = "jenkins/" + run.getParent().getFullName();
            if (key.length() > MAX_KEY) {
                key = key.substring(0, MAX_KEY);
            }
            String url = run.getAbsoluteUrl();
            new BitbucketClient(URI.create(cfg.getApiBaseUrl()), auth.get())
                    .postCommitStatus(
                            target.get().repo(),
                            target.get().commit(),
                            new CommitStatus(key, run.getFullDisplayName(), state, url, description));
        } catch (RuntimeException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Could not report build status to Bitbucket: {0}",
                    e.getClass().getSimpleName());
        }
    }

    private static Optional<BitbucketAuth> resolveAuth(Run<?, ?> run) {
        Job<?, ?> job = run.getParent();
        if (job instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> pj) {
            for (Trigger<?> t : pj.getTriggers().values()) {
                if (t instanceof BitbucketTrigger bt) {
                    Optional<BitbucketAuth> a = AuthResolver.resolve(bt.getCredentialsId());
                    if (a.isPresent()) {
                        return a;
                    }
                }
            }
        }
        return AuthResolver.resolve(BitbucketGlobalConfig.get().getDefaultCredentialsId());
    }

    private static Optional<Target> target(Run<?, ?> run) {
        try {
            BitbucketBuildAction action = run.getAction(BitbucketBuildAction.class);
            if (action != null && action.getRepoFullName() != null && action.getCommitHash() != null) {
                return Optional.of(new Target(RepoKey.fromFullName(action.getRepoFullName()), action.getCommitHash()));
            }
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        for (BuildData data : run.getActions(BuildData.class)) {
            if (data.getLastBuiltRevision() == null || data.getRemoteUrls() == null) {
                continue;
            }
            String sha = data.getLastBuiltRevision().getSha1String();
            for (String url : data.getRemoteUrls()) {
                Optional<RepoKey> key = RepoKey.fromUrl(url);
                if (key.isPresent()) {
                    return Optional.of(new Target(key.get(), sha));
                }
            }
        }
        return Optional.empty();
    }
}
