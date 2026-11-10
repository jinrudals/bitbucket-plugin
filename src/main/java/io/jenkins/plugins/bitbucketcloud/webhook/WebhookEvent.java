package io.jenkins.plugins.bitbucketcloud.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The parts of a Bitbucket Cloud webhook delivery that the trigger cares about. */
final class WebhookEvent {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String eventKey;
    private final String repository;
    private final String actor;
    private final List<String> refs;
    private final JsonNode root;

    private WebhookEvent(String eventKey, String repository, String actor, List<String> refs, JsonNode root) {
        this.root = root;
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
                extractRefs(eventKey, root),
                root);
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

    /** Environment variables exposed to the build; values missing from the payload are empty strings. */
    Map<String, String> environment(String ref) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("BITBUCKET_EVENT_KEY", eventKey);
        JsonNode repo = root.path("repository");
        String fullName = repo.path("full_name").asText("");
        env.put("BITBUCKET_REPOSITORY", fullName);
        env.put("BITBUCKET_REPOSITORY_NAME", repo.path("name").asText(""));
        String workspace = repo.path("workspace").path("slug").asText("");
        if (workspace.isEmpty() && fullName.contains("/")) {
            workspace = fullName.substring(0, fullName.indexOf('/'));
        }
        env.put("BITBUCKET_WORKSPACE", workspace);
        env.put("BITBUCKET_REPOSITORY_URL", repo.path("links").path("html").path("href").asText(""));
        env.put("BITBUCKET_ACTOR", actor);
        env.put("BITBUCKET_ACTOR_ACCOUNT_ID", root.path("actor").path("account_id").asText(""));
        env.put("BITBUCKET_REF", ref == null ? "" : ref);
        if (eventKey.startsWith("pullrequest:")) {
            addPullRequest(env, root.path("pullrequest"));
            env.put("BITBUCKET_PR_COMMENT", root.path("comment").path("content").path("raw").asText(""));
        } else if (eventKey.equals("repo:push")) {
            addPush(env, ref);
        }
        return env;
    }

    private void addPush(Map<String, String> env, String ref) {
        JsonNode match = null;
        for (JsonNode change : root.path("push").path("changes")) {
            if (ref != null && ref.equals(change.path("new").path("name").asText(""))) {
                match = change;
                break;
            }
        }
        JsonNode change = match == null ? root.path("push").path("changes").path(0) : match;
        env.put("BITBUCKET_REF_TYPE", change.path("new").path("type").asText(""));
        env.put("BITBUCKET_COMMIT_BEFORE", change.path("old").path("target").path("hash").asText(""));
        env.put("BITBUCKET_COMMIT_AFTER", change.path("new").path("target").path("hash").asText(""));
        env.put("BITBUCKET_COMMIT_MESSAGE", change.path("new").path("target").path("message").asText(""));
    }

    private static void addPullRequest(Map<String, String> env, JsonNode pr) {
        env.put("BITBUCKET_PR_ID", pr.path("id").asText(""));
        env.put("BITBUCKET_PR_TITLE", pr.path("title").asText(""));
        env.put("BITBUCKET_PR_STATE", pr.path("state").asText(""));
        env.put("BITBUCKET_PR_URL", pr.path("links").path("html").path("href").asText(""));
        env.put("BITBUCKET_PR_SOURCE_BRANCH", pr.path("source").path("branch").path("name").asText(""));
        env.put("BITBUCKET_PR_SOURCE_COMMIT", pr.path("source").path("commit").path("hash").asText(""));
        env.put("BITBUCKET_PR_TARGET_BRANCH", pr.path("destination").path("branch").path("name").asText(""));
        env.put("BITBUCKET_PR_AUTHOR", pr.path("author").path("display_name").asText(""));
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
