package io.jenkins.plugins.bitbucketcloud.routing;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.plugins.git.GitSCM;
import io.jenkins.plugins.bitbucketcloud.trigger.BitbucketTrigger;
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
        FreeStyleProject match = freestyle(j, "match", URL, t);
        FreeStyleProject other = freestyle(j, "other", "https://bitbucket.org/ws/other.git", new BitbucketTrigger());
        FreeStyleProject none = freestyle(j, "notrigger", URL, null);
        RepoKey repo = RepoKey.fromFullName("ws/Repo");
        assertSame(t, JobMatcher.findTrigger(match, repo).orElseThrow());
        assertTrue(JobMatcher.findTrigger(other, repo).isEmpty());
        assertTrue(JobMatcher.findTrigger(none, repo).isEmpty());
    }

    @Test
    void matchesPipelineFromScmBeforeFirstRun(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "pipe");
        job.setDefinition(new CpsScmFlowDefinition(scm(URL), "Jenkinsfile"));
        BitbucketTrigger t = new BitbucketTrigger();
        job.addTrigger(t);
        assertSame(t, JobMatcher.findTrigger(job, new RepoKey("ws", "repo")).orElseThrow());
    }

    @Test
    void matchesInlinePipelineByExplicitRepository(JenkinsRule j) throws Exception {
        WorkflowJob byName = j.createProject(WorkflowJob.class, "inline-name");
        BitbucketTrigger t1 = new BitbucketTrigger();
        t1.setRepository("WS/Repo");
        byName.addTrigger(t1);
        WorkflowJob byUrl = j.createProject(WorkflowJob.class, "inline-url");
        BitbucketTrigger t2 = new BitbucketTrigger();
        t2.setRepository(URL);
        byUrl.addTrigger(t2);
        WorkflowJob other = j.createProject(WorkflowJob.class, "inline-other");
        BitbucketTrigger t3 = new BitbucketTrigger();
        t3.setRepository("ws/other");
        other.addTrigger(t3);
        WorkflowJob unset = j.createProject(WorkflowJob.class, "inline-unset");
        unset.addTrigger(new BitbucketTrigger());
        RepoKey repo = new RepoKey("ws", "repo");
        assertSame(t1, JobMatcher.findTrigger(byName, repo).orElseThrow());
        assertSame(t2, JobMatcher.findTrigger(byUrl, repo).orElseThrow());
        assertTrue(JobMatcher.findTrigger(other, repo).isEmpty());
        assertTrue(JobMatcher.findTrigger(unset, repo).isEmpty());
    }

    @Test
    void explicitRepositoryOverridesScmUrl(JenkinsRule j) throws Exception {
        BitbucketTrigger t = new BitbucketTrigger();
        t.setRepository("ws/elsewhere");
        FreeStyleProject p = freestyle(j, "explicit", URL, t);
        assertTrue(JobMatcher.findTrigger(p, new RepoKey("ws", "repo")).isEmpty());
        assertSame(t, JobMatcher.findTrigger(p, new RepoKey("ws", "elsewhere")).orElseThrow());
    }
}
