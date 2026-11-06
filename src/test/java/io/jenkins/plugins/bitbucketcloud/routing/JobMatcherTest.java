package io.jenkins.plugins.bitbucketcloud.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.plugins.git.GitSCM;
import hudson.security.ACL;
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
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

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
        assertEquals(List.of(t1, t2), JobMatcher.findTriggers(new RepoKey("ws", "repo")));
    }

    @Test
    void explicitRepositoryOverridesScmUrl(JenkinsRule j) throws Exception {
        BitbucketTrigger t = new BitbucketTrigger();
        t.setRepository("ws/elsewhere");
        freestyle(j, "explicit", URL, t);
        assertTrue(JobMatcher.findTriggers(new RepoKey("ws", "repo")).isEmpty());
        assertEquals(List.of(t), JobMatcher.findTriggers(new RepoKey("ws", "elsewhere")));
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

    @Test
    void dispatchFiresUnderSystemWhenCallerIsAnonymous(JenkinsRule j) throws Exception {
        AuthRecordingTrigger trigger = new AuthRecordingTrigger();
        FreeStyleProject p = freestyle(j, "hidden", URL, trigger);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy());
        Authentication anon = new AnonymousAuthenticationToken(
                "anonymous", "anonymous", AuthorityUtils.createAuthorityList("anonymous"));
        try (var ignored = ACL.as2(anon)) {
            assertEquals(anon, SecurityContextHolder.getContext().getAuthentication());
            new TriggerEventDispatcher().dispatch(new PushEvent("ws/repo", "me", "main", "abc"));
        }
        j.waitUntilNoActivity();
        assertEquals(2, p.getNextBuildNumber());
        assertEquals(ACL.SYSTEM2, trigger.acceptsAuth);
    }

    public static class AuthRecordingTrigger extends BitbucketTrigger {
        Authentication acceptsAuth;

        @Override
        public boolean accepts(BitbucketEvent e) {
            acceptsAuth = SecurityContextHolder.getContext().getAuthentication();
            return super.accepts(e);
        }

        @Override
        public TriggerDescriptor getDescriptor() {
            return Jenkins.get().getDescriptorByType(BitbucketTrigger.DescriptorImpl.class);
        }
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
