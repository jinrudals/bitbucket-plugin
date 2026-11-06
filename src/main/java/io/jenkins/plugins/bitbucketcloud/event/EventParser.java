package io.jenkins.plugins.bitbucketcloud.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

public final class EventParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EventParser() {}

    /** Returns the events for a webhook delivery; empty for unsupported keys, deletions and tags. */
    public static List<BitbucketEvent> parse(String eventKey, String body) {
        JsonNode root = readTree(body);
        String type =
                switch (eventKey == null ? "" : eventKey) {
                    case "repo:push" -> "push";
                    case "pullrequest:created" -> "created";
                    case "pullrequest:updated" -> "updated";
                    default -> null;
                };
        if (type == null) {
            return List.of();
        }
        String repo = text(root.path("repository").path("full_name"));
        if (repo == null) {
            throw new InvalidPayloadException("Payload has no repository.full_name");
        }
        String actor = actor(root.path("actor"));
        return type.equals("push") ? push(root, repo, actor) : List.of(pullRequest(root, type, repo, actor));
    }

    private static JsonNode readTree(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                throw new InvalidPayloadException("Payload is not a JSON object");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new InvalidPayloadException("Malformed JSON payload", e);
        }
    }

    private static List<BitbucketEvent> push(JsonNode root, String repo, String actor) {
        List<BitbucketEvent> events = new ArrayList<>();
        for (JsonNode change : root.path("push").path("changes")) {
            JsonNode target = change.path("new");
            if (!"branch".equals(text(target.path("type")))) {
                continue;
            }
            String branch = text(target.path("name"));
            String hash = text(target.path("target").path("hash"));
            if (branch != null && hash != null) {
                events.add(new PushEvent(repo, actor, branch, hash));
            }
        }
        return events;
    }

    private static BitbucketEvent pullRequest(JsonNode root, String type, String repo, String actor) {
        JsonNode pr = root.path("pullrequest");
        JsonNode source = pr.path("source");
        JsonNode destination = pr.path("destination");
        String id = text(pr.path("id"));
        String sourceBranch = text(source.path("branch").path("name"));
        String targetBranch = text(destination.path("branch").path("name"));
        String hash = text(source.path("commit").path("hash"));
        if (id == null || sourceBranch == null || targetBranch == null || hash == null) {
            throw new InvalidPayloadException("Pull request payload is missing required fields");
        }
        String sourceRepo = text(source.path("repository").path("full_name"));
        String destRepo = text(destination.path("repository").path("full_name"));
        // A missing repository (e.g. deleted source fork) must not be treated as trusted.
        boolean fromFork = sourceRepo == null || destRepo == null || !sourceRepo.equals(destRepo);
        return new PullRequestEvent(
                repo, actor, type, id, sourceBranch, targetBranch, hash, pr.path("draft").asBoolean(false), fromFork);
    }

    private static String actor(JsonNode actor) {
        String name = text(actor.path("display_name"));
        return name != null ? name : text(actor.path("nickname"));
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
