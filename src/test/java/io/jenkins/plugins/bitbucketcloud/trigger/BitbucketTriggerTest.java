package io.jenkins.plugins.bitbucketcloud.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.bitbucketcloud.event.PullRequestEvent;
import io.jenkins.plugins.bitbucketcloud.event.PushEvent;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.CaptureEnvironmentBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BitbucketTriggerTest {

    private static PushEvent push(String branch, String hash) {
        return new PushEvent("ws/repo", "Alice", branch, hash);
    }

    private static PullRequestEvent pr(String type, boolean draft, boolean fork) {
        return new PullRequestEvent("ws/repo", "Bob", type, "7", "feat/x", "main", "abc", draft, fork);
    }

    @Test
    void acceptsByEventFlags(JenkinsRule j) {
        BitbucketTrigger t = new BitbucketTrigger();
        assertTrue(t.accepts(push("main", "1")));
        assertTrue(t.accepts(pr("created", false, false)));
        assertTrue(t.accepts(pr("updated", false, false)));
        t.setPushEvents(false);
        assertFalse(t.accepts(push("main", "1")));
        t.setPullRequestCreated(false);
        assertFalse(t.accepts(pr("created", false, false)));
        assertTrue(t.accepts(pr("updated", false, false)));
        t.setPullRequestUpdated(false);
        assertFalse(t.accepts(pr("updated", false, false)));
    }

    @Test
    void draftAndForkHandling(JenkinsRule j) {
        BitbucketTrigger t = new BitbucketTrigger();
        assertFalse(t.accepts(pr("created", true, false)));
        assertFalse(t.accepts(pr("created", false, true)));
        t.setIgnoreDraft(false);
        t.setAllowForkPullRequests(true);
        assertTrue(t.accepts(pr("created", true, true)));
    }

    @Test
    void branchFilters(JenkinsRule j) {
        BitbucketTrigger t = new BitbucketTrigger();
        t.setSourceBranchIncludes("feat/**");
        t.setTargetBranchIncludes("main");
        t.setTargetBranchExcludes("main");
        assertFalse(t.accepts(pr("created", false, false)));
        t.setTargetBranchExcludes("");
        assertTrue(t.accepts(pr("created", false, false)));
        assertFalse(t.accepts(push("main", "1")));
        assertTrue(t.accepts(push("feat/a", "1")));
    }

    @Test
    void duplicateCommitSkipped(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        BitbucketTrigger t = new BitbucketTrigger();
        t.start(p, false);
        assertTrue(t.fire(push("main", "h1")));
        j.waitUntilNoActivity();
        assertFalse(t.fire(push("main", "h1")));
        assertTrue(t.fire(push("main", "h2")));
        j.waitUntilNoActivity();
        t.setSkipDuplicateCommit(false);
        assertTrue(t.fire(push("main", "h2")));
        j.waitUntilNoActivity();
    }

    @Test
    void differentCommitsQueuedWhileBlockedAreNotFolded(JenkinsRule j) throws Exception {
        j.jenkins.setNumExecutors(0);
        FreeStyleProject p = j.createFreeStyleProject();
        BitbucketTrigger t = new BitbucketTrigger();
        t.start(p, false);
        assertTrue(t.fire(push("main", "h1")));
        assertTrue(t.fire(push("main", "h2")));
        hudson.model.Queue.Item[] items = j.jenkins.getQueue().getItems();
        assertEquals(2, items.length);
        java.util.Set<String> hashes = new java.util.HashSet<>();
        for (hudson.model.Queue.Item i : items) {
            hashes.add(i.getAction(BitbucketBuildAction.class).getCommitHash());
        }
        assertEquals(java.util.Set.of("h1", "h2"), hashes);
        j.jenkins.getQueue().clear();
    }

    @Test
    void envVarsExported(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        CaptureEnvironmentBuilder capture = new CaptureEnvironmentBuilder();
        p.getBuildersList().add(capture);
        BitbucketTrigger t = new BitbucketTrigger();
        t.start(p, false);
        assertTrue(t.fire(pr("created", false, false)));
        j.waitUntilNoActivity();
        FreeStyleBuild b = p.getLastBuild();
        assertNotNull(b);
        j.assertBuildStatusSuccess(b);
        assertEquals("feat/x", capture.getEnvVars().get("bitbucketSourceBranch"));
        assertEquals("main", capture.getEnvVars().get("bitbucketTargetBranch"));
        assertEquals("7", capture.getEnvVars().get("bitbucketPullRequestId"));
        assertEquals("ws/repo", capture.getEnvVars().get("bitbucketRepoFullName"));
        assertEquals("abc", capture.getEnvVars().get("bitbucketCommitHash"));
        assertEquals("Bob", capture.getEnvVars().get("bitbucketActor"));
        assertTrue(b.getCauses().stream().anyMatch(c -> c instanceof BitbucketCause));
    }

    @Test
    void pushEnvOmitsPrId(JenkinsRule j) {
        java.util.Map<String, String> env = new hudson.EnvVars();
        new BitbucketBuildAction(push("main", "h")).buildEnvironment(null, (hudson.EnvVars) env);
        assertEquals("main", env.get("bitbucketSourceBranch"));
        assertEquals("main", env.get("bitbucketTargetBranch"));
        assertNull(env.get("bitbucketPullRequestId"));
    }

    @Test
    void pipelineSymbol(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(
                "properties([pipelineTriggers([bitbucketCloud(pushEvents: true, ignoreDraft: false)])])", true));
        j.assertBuildStatusSuccess(job.scheduleBuild2(0));
        BitbucketTrigger t = job.getTriggers().values().stream()
                .filter(BitbucketTrigger.class::isInstance)
                .map(BitbucketTrigger.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(t.isPushEvents());
        assertFalse(t.isIgnoreDraft());
    }
}
