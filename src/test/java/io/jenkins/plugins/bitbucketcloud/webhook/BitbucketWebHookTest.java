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
        trigger.setIncludeBranches("release/*");
        trigger.setExcludeBranches("release/old*");
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
        trigger.setAllowedEvents("pullrequest:created");
        trigger.setIncludeBranches("feature/*");
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
    void configRoundTripKeepsSettingsAndGeneratesSecret(JenkinsRule j) throws Exception {
        BitbucketPushTrigger trigger = new BitbucketPushTrigger();
        trigger.setIncludeBranches("main");
        trigger.setSecretText("");
        FreeStyleProject project = j.createFreeStyleProject("job");
        project.addTrigger(trigger);

        project = j.configRoundtrip(project);
        BitbucketPushTrigger saved = project.getTrigger(BitbucketPushTrigger.class);

        assertNotNull(saved);
        assertEquals("main", saved.getIncludeBranches());
        assertEquals(trigger.getSecretText(), saved.getSecretText());
        assertNotEquals("", saved.getSecretText());
    }
}
