package io.jenkins.plugins.bitbucketcloud.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.plugins.git.GitSCM;
import hudson.triggers.TriggerDescriptor;
import io.jenkins.plugins.bitbucketcloud.event.BitbucketEvent;
import io.jenkins.plugins.bitbucketcloud.event.PushEvent;
import io.jenkins.plugins.bitbucketcloud.trigger.BitbucketTrigger;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class JobMatcherTest {

    private static GitSCM scm(String url) {
        return new GitSCM(url);
    }

    private static final String URL = "https://bitbucket.org/ws/repo.git";

    private static FreeStyleProject freestyle(JenkinsRule j, String name, String url, BitbucketTrigger t)
            throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(name);
        p.setScm(scm(url));
        if (t != null) {
            p.addTrigger(t);
            t.start(p, true);
        }
        return p;
    }

    @Test
    void matchesFreestyleByRepo(JenkinsRule j) throws Exception {
        BitbucketTrigger t = new BitbucketTrigger();
        freestyle(j, "match", URL, t);
        freestyle(j, "other", "https://bitbucket.org/ws/other.git", new BitbucketTrigger());
        freestyle(j, "notrigger", URL, null);
        List<BitbucketTrigger> found = JobMatcher.findTriggers(RepoKey.fromFullName("ws/Repo"));
        assertEquals(1, found.size());
        assertSame(t, found.get(0));
    }

    @Test
    void matchesPipelineFromScmBeforeFirstRun(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "pipe");
        job.setDefinition(new CpsScmFlowDefinition(scm(URL), "Jenkinsfile"));
        BitbucketTrigger t = new BitbucketTrigger();
        job.addTrigger(t);
        assertEquals(List.of(t), JobMatcher.findTriggers(new RepoKey("ws", "repo")));
    }

    @Test
    void throwingTriggerDoesNotStopOthers(JenkinsRule j) throws Exception {
        BitbucketTrigger bad = new ThrowingTrigger();
        freestyle(j, "a-bad", URL, bad);
        FreeStyleProject good = freestyle(j, "b-good", URL, new BitbucketTrigger());
        new TriggerEventDispatcher().dispatch(new PushEvent("ws/repo", "me", "main", "abc"));
        j.waitUntilNoActivity();
        assertEquals(2, good.getNextBuildNumber());
    }

    @Test
    void malformedRepoNameIsIgnored(JenkinsRule j) throws Exception {
        FreeStyleProject p = freestyle(j, "p", URL, new BitbucketTrigger());
        new TriggerEventDispatcher().dispatch(new PushEvent("nonsense", "me", "main", "abc"));
        j.waitUntilNoActivity();
        assertTrue(p.getBuilds().isEmpty());
    }

    public static class ThrowingTrigger extends BitbucketTrigger {
        @Override
        public boolean accepts(BitbucketEvent e) {
            throw new IllegalStateException("boom");
        }

        @Override
        public TriggerDescriptor getDescriptor() {
            return Jenkins.get().getDescriptorByType(BitbucketTrigger.DescriptorImpl.class);
        }
    }
}
