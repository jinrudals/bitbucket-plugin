package io.jenkins.plugins.bitbucketcloud.trigger;

import hudson.model.Cause;
import io.jenkins.plugins.bitbucketcloud.event.BitbucketEvent;
import io.jenkins.plugins.bitbucketcloud.event.PullRequestEvent;
import io.jenkins.plugins.bitbucketcloud.event.PushEvent;

public class BitbucketCause extends Cause {

    private final String description;

    public BitbucketCause(BitbucketEvent e) {
        String actor = e.actor() == null ? "unknown" : e.actor();
        if (e instanceof PushEvent p) {
            description = "Started by Bitbucket Cloud push to " + p.branch() + " by " + actor;
        } else if (e instanceof PullRequestEvent pr) {
            description = "Started by Bitbucket Cloud pull request #" + pr.prId() + " " + pr.type() + " ("
                    + pr.sourceBranch() + " -> " + pr.targetBranch() + ") by " + actor;
        } else {
            description = "Started by Bitbucket Cloud";
        }
    }

    @Override
    public String getShortDescription() {
        return description;
    }
}
