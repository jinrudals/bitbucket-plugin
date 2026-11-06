package io.jenkins.plugins.bitbucketcloud.webhook;

import hudson.ExtensionPoint;
import io.jenkins.plugins.bitbucketcloud.event.BitbucketEvent;

/** Receives verified webhook events; invoked asynchronously as {@code ACL.SYSTEM2}. */
public interface EventDispatcher extends ExtensionPoint {
    void dispatch(BitbucketEvent e);
}
