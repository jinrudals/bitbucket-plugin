package io.jenkins.plugins.bitbucketcloud.webhook;

import hudson.model.Cause;

public class BitbucketWebHookCause extends Cause {
    private final String eventKey;
    private final String repository;
    private final String ref;
    private final String actor;

    public BitbucketWebHookCause(String eventKey, String repository, String ref, String actor) {
        this.eventKey = eventKey;
        this.repository = repository;
        this.ref = ref;
        this.actor = actor;
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getRepository() {
        return repository;
    }

    public String getRef() {
        return ref;
    }

    public String getActor() {
        return actor;
    }

    @Override
    public String getShortDescription() {
        StringBuilder text = new StringBuilder("Started by Bitbucket webhook ").append(eventKey);
        if (actor != null && !actor.isEmpty()) {
            text.append(" from ").append(actor);
        }
        if (repository != null && !repository.isEmpty()) {
            text.append(" on ").append(repository);
        }
        if (ref != null && !ref.isEmpty()) {
            text.append(" (").append(ref).append(')');
        }
        return text.toString();
    }
}
