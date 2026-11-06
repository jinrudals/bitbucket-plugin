package io.jenkins.plugins.bitbucketcloud.routing;

import hudson.Extension;
import hudson.security.ACL;
import io.jenkins.plugins.bitbucketcloud.event.BitbucketEvent;
import io.jenkins.plugins.bitbucketcloud.webhook.EventDispatcher;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.scm.api.SCMHeadEvent;

@Extension
public class MultibranchEventDispatcher implements EventDispatcher {

    private static final Logger LOGGER = Logger.getLogger(MultibranchEventDispatcher.class.getName());

    @Override
    public void dispatch(BitbucketEvent e) {
        RepoKey repo;
        try {
            repo = RepoKey.fromFullName(e.repoFullName());
        } catch (IllegalArgumentException | NullPointerException ex) {
            LOGGER.log(Level.FINE, "Ignoring event with malformed repository name");
            return;
        }
        try (var ignored = ACL.as2(ACL.SYSTEM2)) {
            SCMHeadEvent.fireNow(new BitbucketHeadEvent(e, repo));
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "Failed to fire multibranch head event", ex);
        }
    }
}
