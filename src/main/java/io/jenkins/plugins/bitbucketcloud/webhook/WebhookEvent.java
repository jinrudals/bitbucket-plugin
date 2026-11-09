package io.jenkins.plugins.bitbucketcloud.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The parts of a Bitbucket Cloud webhook delivery that the trigger cares about. */
final class WebhookEvent {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String eventKey;
    private final String repository;
    private final String actor;
    private final List<String> refs;

    private WebhookEvent(String eventKey, String repository, String actor, List<String> refs) {
        this.eventKey = eventKey;
        this.repository = repository;
        this.actor = actor;
        this.refs = refs;
    }

    static WebhookEvent parse(String eventKey, String body) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        if (root == null || !root.isObject()) {
            throw new IOException("Payload is not a JSON object");
        }
        return new WebhookEvent(
                eventKey,
                root.path("repository").path("full_name").asText(""),
                root.path("actor").path("display_name").asText(""),
                extractRefs(eventKey, root));
    }

    // Push events list every changed ref; deletions have no "new" side and are ignored.
    // Pull request events are matched against the source branch.
    private static List<String> extractRefs(String eventKey, JsonNode root) {
        List<String> refs = new ArrayList<>();
        if (eventKey.startsWith("pullrequest:")) {
            String source = root.path("pullrequest").path("source").path("branch").path("name").asText("");
            if (!source.isEmpty()) {
                refs.add(source);
            }
        } else {
            for (JsonNode change : root.path("push").path("changes")) {
                String name = change.path("new").path("name").asText("");
                if (!name.isEmpty()) {
                    refs.add(name);
                }
            }
        }
        return refs;
    }

    String getEventKey() {
        return eventKey;
    }

    String getRepository() {
        return repository;
    }

    String getActor() {
        return actor;
    }

    List<String> getRefs() {
        return refs;
    }
}
