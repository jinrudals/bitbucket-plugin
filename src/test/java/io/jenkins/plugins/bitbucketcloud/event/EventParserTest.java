package io.jenkins.plugins.bitbucketcloud.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventParserTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = EventParserTest.class.getResourceAsStream("/payloads/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static PullRequestEvent pr(String key, String name) throws IOException {
        List<BitbucketEvent> events = EventParser.parse(key, fixture(name));
        assertEquals(1, events.size());
        return (PullRequestEvent) events.get(0);
    }

    @Test
    void parsesPush() throws IOException {
        List<BitbucketEvent> events = EventParser.parse("repo:push", fixture("push"));
        assertEquals(List.of(new PushEvent("acme/app", "Jane Doe", "main", "abc123")), events);
    }

    @Test
    void pushWithMultipleChangesYieldsOneEventPerBranch() throws IOException {
        List<BitbucketEvent> events = EventParser.parse("repo:push", fixture("push-multi"));
        assertEquals(
                List.of(
                        new PushEvent("acme/app", "Jane Doe", "main", "abc123"),
                        new PushEvent("acme/app", "Jane Doe", "feature/x", "def456")),
                events);
    }

    @Test
    void ignoresBranchDeletion() throws IOException {
        assertTrue(EventParser.parse("repo:push", fixture("push-delete")).isEmpty());
    }

    @Test
    void ignoresTagPush() throws IOException {
        assertTrue(EventParser.parse("repo:push", fixture("push-tag")).isEmpty());
    }

    @Test
    void parsesPullRequestCreatedAndUpdated() throws IOException {
        PullRequestEvent created = pr("pullrequest:created", "pr-created");
        assertEquals(
                new PullRequestEvent("acme/app", "Jane Doe", "created", "7", "feature/x", "main", "fff999", false, false),
                created);
        assertEquals("updated", pr("pullrequest:updated", "pr-updated").type());
    }

    @Test
    void detectsForkPullRequest() throws IOException {
        assertTrue(pr("pullrequest:created", "pr-fork").fromFork());
        assertFalse(pr("pullrequest:created", "pr-created").fromFork());
    }

    @Test
    void detectsDraft() throws IOException {
        assertTrue(pr("pullrequest:created", "pr-draft").draft());
    }

    @Test
    void fallsBackToNicknameWhenNoDisplayName() {
        String body = "{\"push\":{\"changes\":[{\"new\":{\"type\":\"branch\",\"name\":\"m\",\"target\":{\"hash\":\"h\"}}}]},"
                + "\"repository\":{\"full_name\":\"a/b\"},\"actor\":{\"nickname\":\"jane\"}}";
        assertEquals("jane", EventParser.parse("repo:push", body).get(0).actor());
    }

    @Test
    void unsupportedEventKeyYieldsEmptyList() throws IOException {
        assertTrue(EventParser.parse("issue:created", fixture("push")).isEmpty());
    }

    @Test
    void malformedJsonThrowsInvalidPayload() {
        assertThrows(InvalidPayloadException.class, () -> EventParser.parse("repo:push", "{not json"));
    }

    @Test
    void missingRepositoryThrowsInvalidPayload() {
        assertThrows(InvalidPayloadException.class, () -> EventParser.parse("repo:push", "{\"push\":{\"changes\":[]}}"));
    }

    @Test
    void treatsMissingSourceRepositoryAsFork() throws IOException {
        String payload = fixture("pr-created")
                .replace(",\"repository\":{\"full_name\":\"acme/app\"}},\"destination\"", "},\"destination\"");
        assertFalse(payload.contains("\"source\":{\"branch\":{\"name\":\"feature/x\"},\"commit\":{\"hash\":\"fff999\"},\"repository\""));
        PullRequestEvent event = (PullRequestEvent) EventParser.parse("pullrequest:created", payload).get(0);
        assertTrue(event.fromFork());
    }
}
