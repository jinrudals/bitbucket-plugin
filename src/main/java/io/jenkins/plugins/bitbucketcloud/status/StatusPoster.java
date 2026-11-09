package io.jenkins.plugins.bitbucketcloud.status;

import hudson.model.Job;
import hudson.model.Run;
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
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.plugins.git.GitSCMSource;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMRevisionAction;
import jenkins.scm.api.SCMSource;

/** Resolves repository, commit and credentials for a run and posts a commit status to Bitbucket. */
public final class StatusPoster {
    private static final Logger LOGGER = Logger.getLogger(StatusPoster.class.getName());

    private record Target(RepoKey repo, String commit) {}

    private StatusPoster() {}

    /** Returns false (never throws) when credentials or the target commit are unknown, or posting fails. */
    public static boolean post(Run<?, ?> run, String key, String name, String state, String description, String url) {
        try {
            Optional<BitbucketAuth> auth = resolveAuth(run);
            if (auth.isEmpty()) {
                return false;
            }
            Optional<Target> target = target(run);
            if (target.isEmpty()) {
                return false;
            }
            BitbucketGlobalConfig cfg = BitbucketGlobalConfig.get();
            new BitbucketClient(URI.create(cfg.getApiBaseUrl()), auth.get())
                    .postCommitStatus(
                            target.get().repo(),
                            target.get().commit(),
                            new CommitStatus(key, name, state, url, description));
            return true;
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(
                    Level.WARNING,
                    "Could not report build status to Bitbucket: {0}",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /** True when only the multibranch revision (not build actions or BuildData) identifies the commit. */
    static boolean hasMultibranchOnlyTarget(Run<?, ?> run) {
        return directTarget(run).isEmpty() && multibranchTarget(run).isPresent();
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
