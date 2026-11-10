package io.jenkins.plugins.bitbucketcloud.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BitbucketPipelineTriggerTest {
    @Test
    void pipelineDeclaredTriggerReceivesWebhook(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "pipe");
        job.setDefinition(new CpsFlowDefinition(
                "properties([pipelineTriggers([bitbucket(secretText: 'pipeline-secret', "
                        + "rules: [rule(events: 'repo:push', includeBranches: 'main')])])])\n"
                        + "echo 'hello'",
                true));
        // The first run registers the trigger declared in the script.
        j.buildAndAssertSuccess(job);
        BitbucketPushTrigger trigger = job.getTriggers().values().stream()
                .filter(BitbucketPushTrigger.class::isInstance)
                .map(BitbucketPushTrigger.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("pipeline-secret", trigger.getSecretText());

        String body = "{\"push\":{\"changes\":[{\"new\":{\"name\":\"main\"}}]}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(j.getURL() + "bitbucket/pipe"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("X-Event-Key", "repo:push")
                .header("X-Hub-Signature", SignatureVerifier.sign("pipeline-secret", body.getBytes(StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals("triggered", response.body());
        j.waitUntilNoActivity();
        assertEquals(2, job.getLastBuild().getNumber());
        assertNotNull(job.getLastBuild().getCause(BitbucketWebHookCause.class));
    }
}
