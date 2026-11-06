package io.jenkins.plugins.bitbucketcloud.trigger;

import hudson.EnvVars;
import hudson.Util;
import hudson.model.Action;
import hudson.model.EnvironmentContributingAction;
import hudson.model.InvisibleAction;
import hudson.model.Queue;
import hudson.model.Run;
import io.jenkins.plugins.bitbucketcloud.event.BitbucketEvent;
import io.jenkins.plugins.bitbucketcloud.event.PullRequestEvent;
import io.jenkins.plugins.bitbucketcloud.event.PushEvent;
import java.util.List;
import java.util.Objects;

public class BitbucketBuildAction extends InvisibleAction implements EnvironmentContributingAction, Queue.QueueAction {

    private final String repoFullName;
    private final String commitHash;
    private final String sourceBranch;
    private final String targetBranch;
    private final String prId;
    private final String actor;

    public BitbucketBuildAction(BitbucketEvent e) {
        this.repoFullName = e.repoFullName();
        this.commitHash = e.commitHash();
        this.actor = e.actor();
        if (e instanceof PushEvent p) {
            this.sourceBranch = p.branch();
            this.targetBranch = p.branch();
            this.prId = null;
        } else if (e instanceof PullRequestEvent pr) {
            this.sourceBranch = pr.sourceBranch();
            this.targetBranch = pr.targetBranch();
            this.prId = pr.prId();
        } else {
            this.sourceBranch = null;
            this.targetBranch = null;
            this.prId = null;
        }
    }

    public String getRepoFullName() {
        return repoFullName;
    }

    public String getCommitHash() {
        return commitHash;
    }

    public String getSourceBranch() {
        return sourceBranch;
    }

    public String getTargetBranch() {
        return targetBranch;
    }

    public String getPrId() {
        return prId;
    }

    public String getActor() {
        return actor;
    }

    /** Folds into an already-queued item only when it is for the same commit and pull request. */
    @Override
    public boolean shouldSchedule(List<Action> actions) {
        for (BitbucketBuildAction existing : Util.filter(actions, BitbucketBuildAction.class)) {
            if (Objects.equals(existing.commitHash, commitHash) && Objects.equals(existing.prId, prId)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void buildEnvironment(Run<?, ?> run, EnvVars env) {
        put(env, "bitbucketSourceBranch", sourceBranch);
        put(env, "bitbucketTargetBranch", targetBranch);
        put(env, "bitbucketPullRequestId", prId);
        put(env, "bitbucketRepoFullName", repoFullName);
        put(env, "bitbucketCommitHash", commitHash);
        put(env, "bitbucketActor", actor);
    }

    private static void put(EnvVars env, String key, String value) {
        if (value != null) {
            env.put(key, value);
        }
    }
}
