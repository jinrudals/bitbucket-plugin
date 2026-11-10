package io.jenkins.plugins.bitbucketcloud.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import java.net.URI;
import jenkins.model.Jenkins;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BitbucketWebHookTest {
    private static final String PUSH_MAIN =
            "{\"actor\":{\"display_name\":\"Dev\"},\"repository\":{\"full_name\":\"ws/repo\"},"
                    + "\"push\":{\"changes\":[{\"new\":{\"type\":\"branch\",\"name\":\"main\"}}]}}";

    private static FreeStyleProject withTrigger(FreeStyleProject project, BitbucketPushTrigger trigger)
            throws Exception {
        project.addTrigger(trigger);
        trigger.start(project, true);
        return project;
    }

    private static HttpResponse<String> post(
            JenkinsRule j, String path, String eventKey, String body, String secret) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(j.getURL() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("X-Event-Key", eventKey);
        if (secret != null) {
            builder.header("X-Hub-Signature", SignatureVerifier.sign(secret, body.getBytes(StandardCharsets.UTF_8)));
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void validSignatureTriggersOnlyTheAddressedJob(JenkinsRule j) throws Exception {
        BitbucketPushTrigger trigger = new BitbucketPushTrigger();
        FreeStyleProject target = withTrigger(j.createFreeStyleProject("target"), trigger);
        FreeStyleProject other = withTrigger(j.createFreeStyleProject("other"), new BitbucketPushTrigger());

        HttpResponse<String> response = post(j, "bitbucket/target", "repo:push", PUSH_MAIN, trigger.getSecretText());

        assertEquals(200, response.statusCode());
        assertEquals("triggered", response.body());
        j.waitUntilNoActivity();
        assertNotNull(target.getLastBuild());
        assertTrue(target.getLastBuild().getCauses().get(0) instanceof BitbucketWebHookCause);
        assertEquals(null, other.getLastBuild());
    }

    @Test
    void pushPayloadValuesAreExposedAsEnvironmentVariables(JenkinsRule j) throws Exception {
        BitbucketPushTrigger trigger = new BitbucketPushTrigger();
        FreeStyleProject job = withTrigger(j.createFreeStyleProject("env"), trigger);
        String body = "{\"actor\":{\"display_name\":\"Dev\",\"account_id\":\"acc-1\"},"
                + "\"repository\":{\"full_name\":\"ws/repo\",\"name\":\"repo\"},"
                + "\"push\":{\"changes\":[{\"old\":{\"target\":{\"hash\":\"aaa\"}},"
                + "\"new\":{\"type\":\"branch\",\"name\":\"main\",\"target\":{\"hash\":\"bbb\",\"message\":\"msg\"}}}]}}";

        post(j, "bitbucket/env", "repo:push", body, trigger.getSecretText());
        j.waitUntilNoActivity();

        hudson.EnvVars env = job.getLastBuild().getEnvironment(hudson.model.TaskListener.NULL);
        assertEquals("repo:push", env.get("BITBUCKET_EVENT_KEY"));
        assertEquals("ws", env.get("BITBUCKET_WORKSPACE"));
        assertEquals("git@ssh.bitbucket.org:ws/repo.git", env.get("BITBUCKET_REPO_SSH_URL"));
        assertEquals("repo", env.get("BITBUCKET_REPOSITORY_NAME"));
        assertEquals("acc-1", env.get("BITBUCKET_ACTOR_ACCOUNT_ID"));
        assertEquals("main", env.get("BITBUCKET_REF"));
        assertEquals("branch", env.get("BITBUCKET_REF_TYPE"));
        assertEquals("aaa", env.get("BITBUCKET_COMMIT_BEFORE"));
        assertEquals("bbb", env.get("BITBUCKET_COMMIT_AFTER"));
        assertEquals("msg", env.get("BITBUCKET_COMMIT_MESSAGE"));
    }

    @Test
    void pullRequestPayloadValuesAreExposedAsEnvironmentVariables(JenkinsRule j) throws Exception {
        BitbucketRule rule = new BitbucketRule();
        rule.setEvents("pullrequest:created");
        BitbucketPushTrigger trigger = new BitbucketPushTrigger();
        trigger.setRules(List.of(rule));
        FreeStyleProject job = withTrigger(j.createFreeStyleProject("pr"), trigger);
        String body = "{\"repository\":{\"full_name\":\"ws/repo\"},\"comment\":{\"content\":{\"raw\":\"hi\"}},"
                + "\"pullrequest\":{\"id\":7,\"title\":\"T\",\"state\":\"OPEN\",\"author\":{\"display_name\":\"A\"},"
                + "\"source\":{\"branch\":{\"name\":\"feat\"},\"commit\":{\"hash\":\"abc\"}},"
                + "\"destination\":{\"branch\":{\"name\":\"main\"}}}}";

        post(j, "bitbucket/pr", "pullrequest:created", body, trigger.getSecretText());
        j.waitUntilNoActivity();

        hudson.EnvVars env = job.getLastBuild().getEnvironment(hudson.model.TaskListener.NULL);
        assertEquals("7", env.get("BITBUCKET_PR_ID"));
        assertEquals("feat", env.get("BITBUCKET_PR_SOURCE_BRANCH"));
        assertEquals("main", env.get("BITBUCKET_PR_TARGET_BRANCH"));
        assertEquals("abc", env.get("BITBUCKET_PR_SOURCE_COMMIT"));
        assertEquals("hi", env.get("BITBUCKET_PR_COMMENT"));
    }

    @Test
    void resolvesJobInsideNestedFolders(JenkinsRule j) throws Exception {
        Folder outer = j.jenkins.createProject(Folder.class, "outer");
        Folder inner = outer.createProject(Folder.class, "inner");
        FreeStyleProject job = inner.createProject(FreeStyleProject.class, "job");
        BitbucketPushTrigger trigger = new BitbucketPushTrigger();
        withTrigger(job, trigger);

        HttpResponse<String> response =
                post(j, "bitbucket/outer/inner/job", "repo:push", PUSH_MAIN, trigger.getSecretText());

        assertEquals("triggered", response.body());
        j.waitUntilNoActivity();
        assertNotNull(job.getLastBuild());
    }

    @Test
    void unknownPathAndFolderTargetsReturn404(JenkinsRule j) throws Exception {
        j.jenkins.createProject(Folder.class, "folder");

        assertEquals(404, post(j, "bitbucket/missing", "repo:push", PUSH_MAIN, "x").statusCode());
        assertEquals(404, post(j, "bitbucket/folder", "repo:push", PUSH_MAIN, "x").statusCode());
    }

    @Test
    void rejectsMissingOrWrongSignature(JenkinsRule j) throws Exception {
        BitbucketPushTrigger trigger = new BitbucketPushTrigger();
        FreeStyleProject job = withTrigger(j.createFreeStyleProject("job"), trigger);

        assertEquals(403, post(j, "bitbucket/job", "repo:push", PUSH_MAIN, null).statusCode());
        assertEquals(403, post(j, "bitbucket/job", "repo:push", PUSH_MAIN, "wrong-secret").statusCode());
        j.waitUntilNoActivity();
        assertEquals(null, job.getLastBuild());
    }

    @Test
    void jobWithoutTriggerIsSkipped(JenkinsRule j) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("job");

        HttpResponse<String> response = post(j, "bitbucket/job", "repo:push", PUSH_MAIN, "x");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().startsWith("skipped"));
        assertEquals(null, job.getLastBuild());
    }

    @Test
    void eventAndBranchFiltersSkipBuilds(JenkinsRule j) throws Exception {
        BitbucketPushTrigger trigger = new BitbucketPushTrigger();
        trigger.setRules(List.of(rule("repo:push", "release/*", "release/old*")));
        FreeStyleProject job = withTrigger(j.createFreeStyleProject("job"), trigger);
        String secret = trigger.getSecretText();

        assertTrue(post(j, "bitbucket/job", "repo:push", PUSH_MAIN, secret).body().startsWith("skipped"));
        assertTrue(post(j, "bitbucket/job", "pullrequest:created", PUSH_MAIN, secret)
                .body()
                .startsWith("skipped"));
        String oldRelease = PUSH_MAIN.replace("main", "release/old1");
        assertTrue(post(j, "bitbucket/job", "repo:push", oldRelease, secret).body().startsWith("skipped"));
        j.waitUntilNoActivity();
        assertEquals(null, job.getLastBuild());

        String release = PUSH_MAIN.replace("main", "release/1.0");
        assertEquals("triggered", post(j, "bitbucket/job", "repo:push", release, secret).body());
        j.waitUntilNoActivity();
        assertNotNull(job.getLastBuild());
    }

    @Test
    void pullRequestEventMatchesSourceBranch(JenkinsRule j) throws Exception {
        BitbucketPushTrigger trigger = new BitbucketPushTrigger();
        trigger.setRules(List.of(rule("pullrequest:created", "feature/*", "")));
        FreeStyleProject job = withTrigger(j.createFreeStyleProject("job"), trigger);
        String pr = "{\"pullrequest\":{\"source\":{\"branch\":{\"name\":\"feature/x\"}},"
                + "\"destination\":{\"branch\":{\"name\":\"main\"}}}}";

        assertEquals("triggered", post(j, "bitbucket/job", "pullrequest:created", pr, trigger.getSecretText()).body());
        j.waitUntilNoActivity();
        assertNotNull(job.getLastBuild());
    }

    @Test
    void worksWhenAnonymousCannotReadAndRequiresNoCrumb(JenkinsRule j) throws Exception {
        BitbucketPushTrigger trigger = new BitbucketPushTrigger();
        FreeStyleProject job = withTrigger(j.createFreeStyleProject("job"), trigger);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().to("admin"));

        assertEquals("triggered", post(j, "bitbucket/job", "repo:push", PUSH_MAIN, trigger.getSecretText()).body());
        j.waitUntilNoActivity();
        assertNotNull(job.getLastBuild());
    }

    @Test
    void anyMatchingRuleTriggersTheBuild(JenkinsRule j) throws Exception {
        BitbucketPushTrigger trigger = new BitbucketPushTrigger();
        trigger.setRules(List.of(rule("repo:push", "main", ""), rule("pullrequest:created", "feature/*", "")));
        FreeStyleProject job = withTrigger(j.createFreeStyleProject("job"), trigger);
        String secret = trigger.getSecretText();
        String pr = "{\"pullrequest\":{\"source\":{\"branch\":{\"name\":\"feature/x\"}}}}";

        assertEquals("triggered", post(j, "bitbucket/job", "repo:push", PUSH_MAIN, secret).body());
        j.waitUntilNoActivity();
        assertEquals("triggered", post(j, "bitbucket/job", "pullrequest:created", pr, secret).body());
        j.waitUntilNoActivity();
        assertTrue(post(j, "bitbucket/job", "repo:push", PUSH_MAIN.replace("main", "dev"), secret)
                .body()
                .startsWith("skipped"));
        j.waitUntilNoActivity();
        assertEquals(2, job.getLastBuild().getNumber());
    }

    @Test
    void configRoundTripKeepsRulesAndGeneratesSecret(JenkinsRule j) throws Exception {
        BitbucketPushTrigger trigger = new BitbucketPushTrigger();
        trigger.setRules(List.of(rule("repo:push", "main", ""), rule("pullrequest:created", "", "wip/*")));
        trigger.setSecretText("");
        FreeStyleProject project = j.createFreeStyleProject("job");
        project.addTrigger(trigger);

        project = j.configRoundtrip(project);
        BitbucketPushTrigger saved = project.getTrigger(BitbucketPushTrigger.class);

        assertNotNull(saved);
        assertEquals(2, saved.getRules().size());
        assertEquals("main", saved.getRules().get(0).getIncludeBranches());
        assertEquals("pullrequest:created", saved.getRules().get(1).getEvents());
        assertEquals("wip/*", saved.getRules().get(1).getExcludeBranches());
        assertEquals(trigger.getSecretText(), saved.getSecretText());
        assertNotEquals("", saved.getSecretText());
    }

    private static BitbucketRule rule(String events, String include, String exclude) {
        BitbucketRule rule = new BitbucketRule();
        rule.setEvents(events);
        rule.setIncludeBranches(include);
        rule.setExcludeBranches(exclude);
        return rule;
    }
}
