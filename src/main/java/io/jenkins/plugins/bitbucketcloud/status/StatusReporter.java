package io.jenkins.plugins.bitbucketcloud.status;

import hudson.Extension;
import hudson.ExtensionList;
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
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.plugins.git.GitSCMSource;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMRevisionAction;
import jenkins.scm.api.SCMSource;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener;

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
        } else if (r == Result.ABORTED || r == Result.NOT_BUILT) {
            state = "STOPPED";
        } else {
            state = "FAILED";
        }
        report(run, state, "Build " + (r == null ? "SUCCESS" : r.toString()));
    }

    /**
     * Multibranch builds learn their revision only once the pipeline definition is loaded, which is after
     * {@link #onStarted}; report INPROGRESS from there when it could not be reported earlier.
     */
    @Extension
    public static class MultibranchStart extends FlowExecutionListener {
        @Override
        public void onRunning(FlowExecution execution) {
            try {
                if (execution.getOwner().getExecutable() instanceof Run<?, ?> run
                        && directTarget(run).isEmpty()
                        && multibranchTarget(run).isPresent()) {
                    ExtensionList.lookupSingleton(StatusReporter.class).report(run, "INPROGRESS", "Build started");
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not report multibranch build start", e);
            }
        }
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
            String fullName = run.getParent().getFullName();
            String key = statusKey(fullName);
            String url = run.getAbsoluteUrl();
            new BitbucketClient(URI.create(cfg.getApiBaseUrl()), auth.get())
                    .postCommitStatus(
                            target.get().repo(),
                            target.get().commit(),
                            new CommitStatus(key, "Jenkins: " + fullName, state, url, description));
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(
                    Level.WARNING,
                    "Could not report build status to Bitbucket: {0}",
                    e.getClass().getSimpleName());
        }
    }

    // Bitbucket limits keys to 40 chars; long names keep a prefix plus a SHA-1 suffix to stay unique.
    static String statusKey(String fullName) {
        if (fullName.length() <= MAX_KEY) {
            return fullName;
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(fullName.getBytes(StandardCharsets.UTF_8));
            return fullName.substring(0, MAX_KEY - 9) + "-" + HexFormat.of().formatHex(d, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
        Optional<Target> direct = directTarget(run);
        return direct.isPresent() ? direct : multibranchTarget(run);
    }

    private static Optional<Target> directTarget(Run<?, ?> run) {
        try {
            BitbucketBuildAction action = run.getAction(BitbucketBuildAction.class);
            if (action != null && action.getRepoFullName() != null && action.getCommitHash() != null) {
                return Optional.of(new Target(RepoKey.fromFullName(action.getRepoFullName()), action.getCommitHash()));
            }
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        for (BuildData data : run.getActions(BuildData.class)) {
            var revision = data.getLastBuiltRevision();
            if (revision == null || data.getRemoteUrls() == null) {
                continue;
            }
            String sha = revision.getSha1String();
            for (String url : data.getRemoteUrls()) {
                Optional<RepoKey> key = RepoKey.fromUrl(url);
                if (key.isPresent()) {
                    return Optional.of(new Target(key.get(), sha));
                }
            }
        }
        return Optional.empty();
    }

    // Multibranch builds read the Jenkinsfile lightweight, so there may be no BuildData (at least at start).
    private static Optional<Target> multibranchTarget(Run<?, ?> run) {
        SCMSource source = SCMSource.SourceByItem.findSource(run.getParent());
        if (!(source instanceof GitSCMSource git) || git.getRemote() == null) {
            return Optional.empty();
        }
        SCMRevision revision = SCMRevisionAction.getRevision(source, run);
        if (!(revision instanceof AbstractGitSCMSource.SCMRevisionImpl rev)) {
            return Optional.empty();
        }
        return RepoKey.fromUrl(git.getRemote()).map(key -> new Target(key, rev.getHash()));
    }
}
