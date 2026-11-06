package io.jenkins.plugins.bitbucketcloud.routing;

import hudson.Extension;
import hudson.model.Job;
import hudson.security.ACL;
import io.jenkins.plugins.bitbucketcloud.event.BitbucketEvent;
import io.jenkins.plugins.bitbucketcloud.trigger.BitbucketTrigger;
import io.jenkins.plugins.bitbucketcloud.webhook.EventDispatcher;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class TriggerEventDispatcher implements EventDispatcher {

    private static final Logger LOGGER = Logger.getLogger(TriggerEventDispatcher.class.getName());

    @Override
    public void dispatch(BitbucketEvent e) {
        RepoKey repo;
        try {
            if (e.repoFullName() == null) {
                throw new IllegalArgumentException();
            }
            repo = RepoKey.fromFullName(e.repoFullName());
        } catch (IllegalArgumentException ex) {
            LOGGER.log(Level.FINE, "Ignoring event with malformed repository name");
            return;
        }
        try (var ignored = ACL.as2(ACL.SYSTEM2)) {
            for (BitbucketTrigger trigger : JobMatcher.findTriggers(repo)) {
                try {
                    if (trigger.accepts(e)) {
                        trigger.fire(e);
                    }
                } catch (Exception ex) {
                    Job<?, ?> job = trigger.getJob();
                    LOGGER.log(Level.WARNING, "Failed to trigger " + (job == null ? "?" : job.getFullName()), ex);
                }
            }
        }
    }
}
